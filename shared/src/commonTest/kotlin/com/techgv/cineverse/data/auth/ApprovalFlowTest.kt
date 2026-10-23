package com.techgv.cineverse.data.auth

import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.AccountDto
import com.techgv.cineverse.presentation.FakeAuthRepository
import com.techgv.cineverse.presentation.session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §3 ApprovalFlow rules: callback = hint bound to the pending attempt; consumed outcomes clear it. */
class ApprovalFlowTest {
    private val store = InMemoryPendingAttemptStore()
    private val auth = FakeAuthRepository()
    private val flow = PollingApprovalFlow(auth, store)
    private val first = ApprovalAttempt.of(RequestCode("t1"))
    private val second = ApprovalAttempt.of(RequestCode("t2"))

    @Test fun aCallbackOnlyCountsForThePendingAttempt() = runTest {
        store.save(second)
        assertTrue(store.isPendingCallback(approvalCallbackUri(second)))
        assertFalse(store.isPendingCallback(approvalCallbackUri(first)), "an older attempt's redirect")
        assertFalse(store.isPendingCallback("cineverse://other?attempt=${second.id}"))
        assertFalse(store.isPendingCallback("https://evil.example/?attempt=${second.id}"))
        assertTrue(store.isPendingCallback("cineverse://auth?x=1&attempt=${second.id}"))
        store.clear()
        assertFalse(store.isPendingCallback(approvalCallbackUri(second)), "nothing pending")
    }

    @Test fun pollingIsBoundToThePendingAttemptAndIgnoresCallbacks() = runTest {
        auth.approval = { SignInOutcome.TimedOut }
        flow.start(first)
        flow.start(second) // a newer attempt replaces the first
        assertEquals(SignInOutcome.Superseded, flow.awaitApproval(first))
        assertEquals(SignInOutcome.TimedOut, flow.awaitApproval(second))
        assertEquals(second.id, store.load()?.id, "no answer yet: the same attempt stays pending")
        assertFalse(flow.onCallback(approvalCallbackUri(second)), "TV approval happens on another device")
        assertEquals(listOf("t2"), auth.waits)
    }

    @Test fun deniedExpiredOrSignedInAttemptsAreConsumed() = runTest {
        for (outcome in listOf(SignInOutcome.Rejected(TmdbError.ExchangeRejected(401, 17)), SignInOutcome.CodeExpired)) {
            auth.approval = { outcome }
            flow.start(first)
            assertEquals(outcome, flow.awaitApproval(first))
            assertNull(store.load())
            assertEquals(SignInOutcome.Superseded, flow.awaitApproval(first), "a consumed attempt is never polled again")
        }
    }

    /** Ported from the L4 review probe ReviewApprovalSupersessionTest. */
    @Test fun completionOfAnOldWaitCannotConsumeTheNewPendingAttempt() = runTest {
        val gate = CompletableDeferred<Unit>()
        auth.approval = { gate.await(); SignInOutcome.CodeExpired }
        flow.start(first)
        val old = async { flow.awaitApproval(first) }
        runCurrent()
        flow.start(second)
        gate.complete(Unit)
        assertEquals(SignInOutcome.Superseded, old.await())
        assertEquals(second.id, store.load()?.id, "A's late terminal result cleared B's pending attempt")
    }

    @Test fun everyOutcomeOfAWaitSupersededDuringItIsSupersededAndKeepsTheNewAttempt() = runTest {
        val outcomes = listOf(
            SignInOutcome.SignedIn(session(), AccountDto(1)),
            SignInOutcome.CodeExpired,
            SignInOutcome.Rejected(TmdbError.ExchangeRejected(401, 17)),
            SignInOutcome.Failed(TmdbError.Network),
            SignInOutcome.TimedOut,
        )
        for (outcome in outcomes) {
            val gate = CompletableDeferred<Unit>()
            auth.approval = { gate.await(); outcome }
            val older = ApprovalAttempt.of(RequestCode("old"))
            val newer = ApprovalAttempt.of(RequestCode("new"))
            flow.start(older)
            val old = async { flow.awaitApproval(older) }
            runCurrent()
            flow.start(newer)
            gate.complete(Unit)
            assertEquals(SignInOutcome.Superseded, old.await(), "late $outcome")
            assertEquals(newer.id, store.load()?.id, "late $outcome must keep the newer attempt pending")
            auth.approval = { outcome }
            assertEquals(outcome, flow.awaitApproval(newer), "the newer attempt still completes normally")
        }
    }

    @Test fun attemptIdsAreUniqueAndNeverPrintTheToken() {
        assertTrue(first.id != second.id)
        assertFalse("t1" in first.toString())
    }
}
