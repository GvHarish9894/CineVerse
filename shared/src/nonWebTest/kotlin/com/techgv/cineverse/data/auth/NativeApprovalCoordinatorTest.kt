package com.techgv.cineverse.data.auth

import com.techgv.cineverse.data.tmdb.AccountDto
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.presentation.FakeAuthRepository
import com.techgv.cineverse.presentation.session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeApprovalCoordinatorTest {
    private class Opener(private val onOpen: (String) -> Unit = {}) : NativeApprovalOpener {
        var callback: ((String) -> Unit)? = null
        var cancelled: (() -> Unit)? = null
        var opens = 0
        var cancels = 0
        override fun open(url: String, onCallback: (String) -> Unit, onCancel: () -> Unit) {
            opens++
            onOpen(url)
            callback = onCallback
            cancelled = onCancel
        }
        override fun cancel() { cancels++ }
    }

    @Test fun idExistsAtCreationAndPendingIsDurableBeforeOpening() = runTest {
        val events = mutableListOf<String>()
        var saved: ApprovalAttempt? = null
        val store = object : PendingAttemptStore {
            override suspend fun save(attempt: ApprovalAttempt) { events += "save"; saved = attempt }
            override suspend fun load() = saved
            override suspend fun clear() { saved = null }
        }
        var createdId: String? = null
        val opener = Opener { url ->
            events += "open"
            assertEquals(createdId, saved?.id)
            assertTrue(url.contains("request_token=token"))
        }
        val flow = NativeApprovalCoordinator(FakeAuthRepository(), store, opener, { id ->
            events += "create"
            assertTrue(id.matches(Regex("[a-f0-9]{32}")))
            createdId = id
            RequestCode("token")
        }, backgroundScope)
        val attempt = flow.begin()
        assertEquals(createdId, attempt.id)
        assertEquals(listOf("create", "save", "open"), events)
        flow.close()
    }

    @Test fun coldStartCallbackVerifiesAndStaleOrMalformedCallbacksDoNothing() = runTest {
        val store = InMemoryPendingAttemptStore()
        val current = ApprovalAttempt.of(RequestCode("current"))
        store.save(current) // prior process
        val auth = FakeAuthRepository().apply { approval = { SignInOutcome.CodeExpired } }
        val opener = Opener()
        val flow = NativeApprovalCoordinator(auth, store, opener, { RequestCode("unused") }, backgroundScope)
        assertFalse(flow.onCallback("cineverse://auth?attempt=old"))
        assertFalse(flow.onCallback("${approvalCallbackUri(current)}&attempt=${current.id}"))
        assertTrue(auth.waits.isEmpty())
        assertTrue(flow.onCallback(approvalCallbackUri(current)))
        assertEquals(listOf("current"), auth.waits)
        assertNull(store.load())
        assertEquals(0, opener.opens)
        flow.close()
    }

    @Test fun manualReturnTimeoutRetryAndConsumedOutcomes() = runTest {
        val auth = FakeAuthRepository()
        val store = InMemoryPendingAttemptStore()
        val flow = NativeApprovalCoordinator(auth, store, Opener(), { RequestCode("token") }, backgroundScope)
        for (terminal in listOf(SignInOutcome.CodeExpired, SignInOutcome.Rejected(TmdbError.ExchangeRejected(401, 17)),
            SignInOutcome.Failed(TmdbError.Network), SignInOutcome.SignedIn(session(), AccountDto(1)))) {
            val attempt = flow.begin()
            auth.approval = { SignInOutcome.TimedOut }
            assertEquals(SignInOutcome.TimedOut, flow.checkAgain())
            assertEquals(attempt.id, store.load()?.id)
            auth.approval = { terminal }
            assertEquals(terminal, flow.checkAgain())
            assertNull(store.load())
            val calls = auth.waits.size
            assertNull(flow.checkAgain())
            assertEquals(calls, auth.waits.size, "never re-exchange a consumed token")
        }
        flow.close()
    }

    @Test fun callbackAndManualCheckShareAnInFlightExchange() = runTest {
        val auth = FakeAuthRepository()
        val gate = CompletableDeferred<Unit>()
        auth.approval = { gate.await(); SignInOutcome.TimedOut }
        val store = InMemoryPendingAttemptStore()
        val flow = NativeApprovalCoordinator(auth, store, Opener(), { RequestCode("token") }, backgroundScope)
        val attempt = flow.begin()
        val first = async { flow.checkAgain() }
        runCurrent()
        val second = async { flow.onCallback(approvalCallbackUri(attempt)) }
        val third = async { flow.checkAgain() }
        runCurrent()
        assertEquals(1, auth.waits.size)
        gate.complete(Unit)
        assertEquals(SignInOutcome.TimedOut, first.await())
        assertTrue(second.await())
        assertEquals(SignInOutcome.TimedOut, third.await())
        flow.close()
    }

    @Test fun replacingWaitCancelsAndJoinsBeforeCreatingOrOpeningTheNewAttempt() = runTest {
        val auth = FakeAuthRepository()
        val leaving = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        auth.approval = {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { leaving.await(); events += "old joined" }
            }
        }
        val store = InMemoryPendingAttemptStore()
        val flow = NativeApprovalCoordinator(auth, store, Opener { events += "open" }, {
            events += "create"; RequestCode("token")
        }, backgroundScope)
        val old = flow.begin()
        val wait = async { flow.checkAgain() }
        runCurrent()
        events.clear()
        val replacement = async { flow.begin() }
        runCurrent()
        assertTrue(events.isEmpty(), "replacement must join the cancelled old exchange")
        assertFalse(flow.onCallback(approvalCallbackUri(old)), "old callback must not cancel replacement creation")
        leaving.complete(Unit)
        val new = replacement.await()
        assertTrue(wait.isCancelled)
        assertEquals(listOf("old joined", "create", "open"), events)
        assertEquals(new.id, store.load()?.id)
        flow.close()
    }

    @Test fun rapidReplacementsStillJoinTheOriginalNonCancellableCommit() = runTest {
        val auth = FakeAuthRepository()
        val leaving = CompletableDeferred<Unit>()
        auth.approval = {
            try { awaitCancellation() } finally { withContext(NonCancellable) { leaving.await() } }
        }
        val opener = Opener()
        val flow = NativeApprovalCoordinator(auth, InMemoryPendingAttemptStore(), opener, { RequestCode("token") }, backgroundScope)
        flow.begin()
        val old = async { flow.checkAgain() }
        runCurrent()
        val skipped = async { flow.begin() }
        val latest = async { flow.begin() }
        runCurrent()
        assertEquals(1, opener.opens)
        leaving.complete(Unit)
        latest.await()
        assertTrue(old.isCancelled)
        assertTrue(skipped.isCancelled)
        assertEquals(2, opener.opens)
        flow.close()
    }

    @Test fun cancellationRetainsPendingAndSessionTruthIsNotDerivedFromSuperseded() = runTest {
        val auth = FakeAuthRepository()
        val store = InMemoryPendingAttemptStore()
        val opener = Opener()
        val flow = NativeApprovalCoordinator(auth, store, opener, { RequestCode("token") }, backgroundScope)
        val attempt = flow.begin()
        auth.approval = { awaitCancellation() }
        val wait = async { flow.checkAgain() }
        runCurrent()
        opener.cancelled!!.invoke()
        runCurrent()
        assertTrue(wait.isCancelled)
        assertEquals(NativeApprovalStatus.Cancelled, flow.status.value)
        assertEquals(attempt.id, store.load()?.id)
        assertTrue(flow.session === auth.session)
        auth.approval = { SignInOutcome.Superseded }
        assertEquals(SignInOutcome.Superseded, flow.checkAgain())
        assertNotNull(store.load())
        flow.close()
    }

    @Test fun oldCallbacksAndManualChecksCannotInterruptNewTokenCreation() = runTest {
        val store = InMemoryPendingAttemptStore()
        val old = ApprovalAttempt.of(RequestCode("old"))
        store.save(old)
        val gate = CompletableDeferred<Unit>()
        val flow = NativeApprovalCoordinator(FakeAuthRepository(), store, Opener(), {
            gate.await(); RequestCode("new")
        }, backgroundScope)
        val creating = async { flow.begin() }
        runCurrent()
        assertFalse(flow.onCallback(approvalCallbackUri(old)))
        assertNull(flow.checkAgain())
        assertFalse(creating.isCancelled)
        gate.complete(Unit)
        val newer = creating.await()
        assertEquals(newer.id, store.load()?.id)
        flow.close()
    }

    @Test fun oldPlatformCancellationCannotCancelANewerAttempt() = runTest {
        val auth = FakeAuthRepository().apply { approval = { awaitCancellation() } }
        val opener = Opener()
        val flow = NativeApprovalCoordinator(auth, InMemoryPendingAttemptStore(), opener, { RequestCode("token") }, backgroundScope)
        flow.begin()
        val oldCancel = opener.cancelled!!
        flow.begin()
        val wait = async { flow.checkAgain() }
        runCurrent()
        oldCancel()
        runCurrent()
        assertFalse(wait.isCancelled)
        assertEquals(NativeApprovalStatus.Checking, flow.status.value)
        wait.cancelAndJoin()
        flow.close()
    }

    @Test fun synchronousPlatformCallbackIsDeferredUntilOpeningCompletes() = runTest {
        val store = InMemoryPendingAttemptStore()
        val auth = FakeAuthRepository().apply { approval = { SignInOutcome.CodeExpired } }
        lateinit var attempt: ApprovalAttempt
        val opener = object : NativeApprovalOpener {
            override fun open(url: String, onCallback: (String) -> Unit, onCancel: () -> Unit) {
                onCallback(approvalCallbackUri(attempt))
            }
            override fun cancel() = Unit
        }
        val flow = NativeApprovalCoordinator(auth, store, opener, { RequestCode("unused") }, backgroundScope)
        attempt = ApprovalAttempt.of(RequestCode("token"))
        flow.start(attempt)
        runCurrent()
        assertEquals(listOf("token"), auth.waits)
        assertNull(store.load())
        flow.close()
    }

    @Test fun persistenceFailurePreventsOpeningAndPlatformFailureKeepsManualRecovery() = runTest {
        val ownedScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() +
            kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val auth = FakeAuthRepository().apply { approval = { SignInOutcome.TimedOut } }
        val opener = Opener()
        val broken = object : PendingAttemptStore {
            override suspend fun save(attempt: ApprovalAttempt) { error("fixture storage failure") }
            override suspend fun load(): ApprovalAttempt? = null
            override suspend fun clear() = Unit
        }
        val failed = NativeApprovalCoordinator(auth, broken, opener, { RequestCode("token") }, ownedScope)
        kotlin.test.assertFailsWith<IllegalStateException> { failed.begin() }
        assertEquals(0, opener.opens)
        assertEquals(NativeApprovalStatus.Failed, failed.status.value)
        val store = InMemoryPendingAttemptStore()
        val unavailable = object : NativeApprovalOpener {
            override fun open(url: String, onCallback: (String) -> Unit, onCancel: () -> Unit) { error("fixture presenter missing") }
            override fun cancel() = Unit
        }
        val recoverable = NativeApprovalCoordinator(auth, store, unavailable, { RequestCode("token") }, ownedScope)
        kotlin.test.assertFailsWith<IllegalStateException> { recoverable.begin() }
        assertNotNull(store.load())
        assertEquals(SignInOutcome.TimedOut, recoverable.checkAgain())
        recoverable.close()
    }

    @Test fun callerCancellationStopsItsWaitButKeepsPending() = runTest {
        val auth = FakeAuthRepository().apply { approval = { awaitCancellation() } }
        val store = InMemoryPendingAttemptStore()
        val flow = NativeApprovalCoordinator(auth, store, Opener(), { RequestCode("token") }, backgroundScope)
        flow.begin()
        val wait = async { flow.checkAgain() }
        runCurrent()
        wait.cancelAndJoin()
        assertNotNull(store.load())
        flow.close()
    }
}
