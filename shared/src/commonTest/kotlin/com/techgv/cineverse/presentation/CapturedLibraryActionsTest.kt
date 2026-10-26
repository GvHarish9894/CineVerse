package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.AccountLibrarySource
import com.techgv.cineverse.data.library.GuestLibraryRow
import com.techgv.cineverse.data.library.GuestLibrarySource
import com.techgv.cineverse.data.library.GuestLibraryStore
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.library.LibraryWriteResult
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.web.WebProxyException
import com.techgv.cineverse.data.web.WebWriteOutcome
import com.techgv.cineverse.domain.LibraryActions
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real repository guards/locks, with only persistence and account IO replaced. No verified-browser claim. */
@OptIn(ExperimentalCoroutinesApi::class)
class CapturedLibraryActionsTest {
    private val a = session("account-a", generation = Long.MAX_VALUE)
    private val b = session("account-b", generation = Long.MIN_VALUE)
    private val movie = title(1)

    @Test fun delayedInvocationAfterActualAndForwardedSwitchNeverTargetsB() = runTest {
        val f = Fixture(this, a)
        f.switchTo(b)
        f.holder.toggleWatchlist(movie, a)
        f.holder.toggleFavourite(movie, a)
        f.holder.openRate(movie, a)
        runCurrent()
        f.assertUntouched()
        assertNull(f.holder.ratePrompt.first())
    }

    @Test fun delayedInvocationWithStaleForwardedIdentityStillUsesActualSession() = runTest {
        val f = Fixture(this, a)
        f.actual.value = b // onIdentity has not caught up
        f.holder.toggleWatchlist(movie, a)
        f.holder.toggleFavourite(movie, a)
        f.holder.openRate(movie, a)
        runCurrent()
        f.assertUntouched()
        assertNull(f.holder.ratePrompt.first())
    }

    @Test fun sameOpaqueAccountWithNewGenerationSupersedesCapturedIntent() = runTest {
        val f = Fixture(this, a)
        f.switchTo(a.copy(sessionGeneration = Long.MIN_VALUE))
        f.holder.toggleWatchlist(movie, a)
        f.holder.toggleFavourite(movie, a)
        f.holder.openRate(movie, a)
        runCurrent()
        f.assertUntouched()
        assertNull(f.holder.ratePrompt.first())
    }

    @Test fun sameDisplayNameWithDifferentOpaqueAccountSupersedesCapturedIntent() = runTest {
        val f = Fixture(this, a)
        f.switchTo(b.copy(displayName = a.displayName, sessionGeneration = a.sessionGeneration))
        f.holder.toggleWatchlist(movie, a)
        f.holder.toggleFavourite(movie, a)
        f.holder.openRate(movie, a)
        runCurrent()
        f.assertUntouched()
        assertNull(f.holder.ratePrompt.first())
    }

    @Test fun capturedGuestIsNotRetargetedToLaterSignedInSession() = runTest {
        val f = Fixture(this, null)
        f.switchTo(b)
        f.holder.toggleWatchlist(movie, null)
        f.holder.toggleFavourite(movie, null)
        f.holder.openRate(movie, null)
        runCurrent()
        f.assertUntouched()
        assertEquals(0, f.guestReads)
        assertNull(f.holder.ratePrompt.first())
    }

    @Test fun explicitExpectedIsNotReplacedByForwardedIdentity() = runTest {
        val f = Fixture(this, a)
        f.holder.onIdentity(b) // actual repository is still A
        f.holder.toggleWatchlist(movie, a)
        f.holder.toggleFavourite(movie, a)
        runCurrent()
        assertEquals(listOf(a, a), f.requests.map { it.session })
        assertEquals(listOf(LibraryKind.WATCHLIST, LibraryKind.FAVOURITE), f.requests.map { it.kind })
        assertTrue(f.outcomes.all { it.result is LibraryWriteResult.Done })
    }

    @Test fun explicitDispatchBeforeCoroutineSchedulingIsSuperseded() = runTest {
        val f = Fixture(this, a)
        f.holder.toggleWatchlist(movie, a)
        f.holder.toggleFavourite(movie, a)
        f.switchTo(b)
        runCurrent()
        f.assertUntouched()
    }

    @Test fun queuedExplicitWriteIsRejectedInsidePerTitleLockAndLateOutcomeIsSuppressed() = runTest {
        val f = Fixture(this, a)
        val gate = CompletableDeferred<Unit>().also { f.account.writeGate = it }
        f.holder.toggleWatchlist(movie, a)
        runCurrent()
        f.holder.toggleWatchlist(movie, a)
        runCurrent()
        assertEquals(1, f.requests.size)
        f.switchTo(b)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(a), f.requests.map { it.session })
        assertTrue(f.outcomes.isEmpty())
    }

    @Test fun switchDuringPrePostReadSendsNoMutation() = runTest {
        val f = Fixture(this, a)
        val gate = CompletableDeferred<Unit>()
        f.beforeAccountRead = { gate.await() }
        f.holder.toggleFavourite(movie, a)
        runCurrent()
        assertEquals(listOf(a), f.accountReads)
        f.switchTo(b)
        gate.complete(Unit)
        runCurrent()
        assertTrue(f.requests.isEmpty())
        assertTrue(f.outcomes.isEmpty())
    }

    @Test fun explicitTogglesKeepFifoOptimismAndConfirmation() = runTest {
        val f = Fixture(this, a)
        val history = mutableListOf<LibraryState>()
        backgroundScope.launch { f.holder.state(movie.ref).collect { history += it } }
        runCurrent()
        val gate = CompletableDeferred<Unit>().also { f.account.writeGate = it }
        f.holder.toggleWatchlist(movie, a)
        f.holder.toggleWatchlist(movie, a)
        runCurrent()
        assertEquals(setOf(LibraryKind.WATCHLIST), history.last().pending)
        assertTrue(history.last().watchlist)
        assertEquals(1, f.requests.size)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(true, false), f.requests.map { it.target.watchlist })
        assertEquals(listOf(a, a), f.requests.map { it.session })
        assertFalse(history.last().watchlist)
        assertTrue(history.last().pending.isEmpty())
        assertEquals(2, f.outcomes.size)
    }

    @Test fun legacyDomainOverridesStillCaptureIdentityAtInvocation() = runTest {
        val f = Fixture(this, a)
        val legacy: LibraryActions = f.holder
        legacy.toggleWatchlist(movie)
        legacy.toggleFavourite(movie)
        legacy.openRate(movie)
        f.switchTo(b)
        runCurrent()
        f.assertUntouched()
        assertNull(f.holder.ratePrompt.first())
        legacy.toggleFavourite(movie)
        runCurrent()
        assertEquals(listOf(b), f.requests.map { it.session })
    }

    @Test fun capturedGuestStillWritesLocallyWhenGuestRemainsCurrent() = runTest {
        val f = Fixture(this, null)
        f.holder.toggleWatchlist(movie, null)
        f.holder.toggleFavourite(movie, null)
        runCurrent()
        assertEquals(setOf(LibraryKind.WATCHLIST, LibraryKind.FAVOURITE), f.rows.all().map { it.kind }.toSet())
        assertTrue(f.requests.isEmpty())
        assertEquals(2, f.outcomes.size)
        assertTrue(f.outcomes.all { !(it.result as LibraryWriteResult.Done).synced })
    }

    @Test fun rateChecksActualSessionBeforeReadingGuestStorage() = runTest {
        val f = Fixture(this, null)
        f.holder.openRate(movie, null)
        f.actual.value = a
        runCurrent()
        assertEquals(0, f.guestReads)
        assertNull(f.holder.ratePrompt.first())
    }

    @Test fun rateSwitchDuringRatingLookupDropsPromptWithoutForwardedIdentity() = runTest {
        val f = Fixture(this, null)
        val gate = CompletableDeferred<Unit>()
        f.beforeGuestRead = { gate.await() }
        f.holder.openRate(movie, null)
        runCurrent()
        assertEquals(1, f.guestReads)
        f.actual.value = a
        gate.complete(Unit)
        runCurrent()
        assertNull(f.holder.ratePrompt.first())
        f.assertUntouched()
    }

    @Test fun rateRechecksActualSessionAfterRatingLookup() = runTest {
        val f = Fixture(this, null)
        var sessionReads = 0
        f.beforeSessionRead = { if (++sessionReads == 2) f.actual.value = a }
        f.holder.openRate(movie, null)
        runCurrent()
        assertEquals(1, f.guestReads)
        assertEquals(2, sessionReads)
        assertNull(f.holder.ratePrompt.first())
    }

    @Test fun closeRateBeforeDispatchSupersedesPendingOpen() = runTest {
        val f = Fixture(this, null)
        f.holder.openRate(movie, null)
        f.holder.closeRate()
        runCurrent()
        assertNull(f.holder.ratePrompt.first())
    }

    @Test fun closeRateDuringLookupDoesNotReopenPrompt() = runTest {
        val f = Fixture(this, null)
        val gate = CompletableDeferred<Unit>()
        f.beforeGuestRead = { gate.await() }
        f.holder.openRate(movie, null)
        runCurrent()
        f.holder.closeRate()
        gate.complete(Unit)
        runCurrent()
        assertNull(f.holder.ratePrompt.first())
    }

    @Test fun laterRateRequestWinsWhenEarlierLookupFinishesLast() = runTest {
        val f = Fixture(this, null)
        val gate = CompletableDeferred<Unit>()
        f.beforeGuestRead = { if (f.guestReads == 1) gate.await() }
        f.holder.openRate(movie, null)
        runCurrent()
        f.holder.openRate(title(2), null)
        runCurrent()
        assertEquals(title(2), f.holder.ratePrompt.first()?.title)
        gate.complete(Unit)
        runCurrent()
        assertEquals(title(2), f.holder.ratePrompt.first()?.title)
    }

    @Test fun identityRoundTripDuringLookupDoesNotResurrectOldPrompt() = runTest {
        val f = Fixture(this, null)
        val gate = CompletableDeferred<Unit>()
        f.beforeGuestRead = { gate.await() }
        f.holder.openRate(movie, null)
        runCurrent()
        f.switchTo(a)
        f.switchTo(null)
        gate.complete(Unit)
        runCurrent()
        assertNull(f.holder.ratePrompt.first())
    }

    @Test fun rateInitialAndSubmissionRemainBoundToExpectedSessionAcrossPlayerDeferral() = runTest {
        val f = Fixture(this, a)
        f.account.ratings[movie.ref] = 6.5
        f.repository.state(movie.ref).first { it.known }
        f.holder.setPlayerActive(true)
        f.holder.openRate(movie, a)
        runCurrent()
        assertNull(f.holder.ratePrompt.first())
        f.holder.setPlayerActive(false)
        assertEquals(RatePrompt(movie, 6.5, a), f.holder.ratePrompt.first())
        f.holder.submitRating(8.5)
        runCurrent()
        assertNull(f.holder.ratePrompt.first())
        assertEquals(listOf(a), f.requests.map { it.session })
        assertEquals(8.5, f.requests.single().target.rating)
        assertEquals(LibraryWrite.Rate(8.5), f.outcomes.single().write)
    }

    @Test fun removalRemainsBoundToPromptExpectedSession() = runTest {
        val f = Fixture(this, a)
        f.holder.openRate(movie, a)
        runCurrent()
        f.holder.removeRating()
        runCurrent()
        assertEquals(listOf(a), f.requests.map { it.session })
        assertEquals(LibraryKind.RATING, f.requests.single().kind)
        assertNull(f.requests.single().target.rating)
        assertEquals(LibraryWrite.Unrate, f.outcomes.single().write)
    }

    @Test fun switchAfterPromptBeforeSubmitOrRemoveSuppressesBothMutations() = runTest {
        val f = Fixture(this, a)
        f.holder.openRate(movie, a)
        runCurrent()
        f.holder.submitRating(7.5)
        f.actual.value = b
        runCurrent()
        f.assertUntouched()
        f.switchTo(a)
        f.holder.openRate(movie, a)
        runCurrent()
        f.actual.value = b // prompt still holds A until onIdentity catches up
        f.holder.removeRating()
        runCurrent()
        f.assertUntouched()
    }

    @Test fun forwardedIdentityChangeDropsPlayerDeferredPrompt() = runTest {
        val f = Fixture(this, a)
        f.holder.setPlayerActive(true)
        f.holder.openRate(movie, a)
        runCurrent()
        f.switchTo(b)
        f.holder.setPlayerActive(false)
        assertNull(f.holder.ratePrompt.first())
        f.holder.submitRating(7.5)
        f.holder.removeRating()
        runCurrent()
        f.assertUntouched()
    }

    @Test fun submittingExistingPromptSupersedesAnOutstandingLookup() = runTest {
        val f = Fixture(this, null)
        f.holder.openRate(movie, null)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        f.beforeGuestRead = { if (f.guestReads == 2) gate.await() }
        f.holder.openRate(title(2), null)
        runCurrent()
        f.holder.submitRating(7.5)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertNull(f.holder.ratePrompt.first())
        assertEquals(movie.ref, f.rows.all().single().ref)
        assertEquals(7.5, f.rows.all().single().value)
    }

    @Test fun removingExistingPromptSupersedesAnOutstandingLookup() = runTest {
        val f = Fixture(this, null)
        f.holder.openRate(movie, null)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        f.beforeGuestRead = { if (f.guestReads == 2) gate.await() }
        f.holder.openRate(title(2), null)
        runCurrent()
        f.holder.removeRating()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertNull(f.holder.ratePrompt.first())
        assertEquals(LibraryWrite.Unrate, f.outcomes.single().write)
    }

    @Test fun storageFailureAndReadRecoveryNeverReplayFailedMutationOrRateOpen() = runTest {
        val f = Fixture(this, null)
        val history = mutableListOf<LibraryState>()
        f.failGuestReads = true
        backgroundScope.launch { f.holder.state(movie.ref).collect { history += it } }
        f.holder.toggleFavourite(movie, null)
        f.holder.openRate(movie, null)
        runCurrent()
        assertEquals(LibraryState.Unknown, history.last())
        assertEquals(3, f.failures.size)
        assertEquals(LibraryWriteResult.Failed(null), f.outcomes.single().result)
        f.failGuestReads = false
        f.holder.retryStorage()
        runCurrent()
        assertTrue(history.last().known)
        assertTrue(f.rows.all().isEmpty())
        assertEquals(1, f.outcomes.size)
        assertNull(f.holder.ratePrompt.first())
        assertTrue(f.requests.isEmpty())
    }

    @Test fun guestPersistenceFailureIsReportedAndNeverReplayedOnRecovery() = runTest {
        val f = Fixture(this, null)
        f.failGuestWrites = true
        f.holder.toggleFavourite(movie, null)
        runCurrent()
        assertEquals(1, f.failures.size)
        assertEquals(LibraryWriteResult.Failed(null), f.outcomes.single().result)
        f.failGuestWrites = false
        f.holder.retryStorage()
        runCurrent()
        assertTrue(f.rows.all().isEmpty())
        assertEquals(1, f.outcomes.size)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun lateSupersededStorageFailureIsNotReportedOrReplayed() = runTest {
        val f = Fixture(this, null)
        val gate = CompletableDeferred<Unit>()
        f.beforeGuestRead = { gate.await() }
        f.holder.toggleWatchlist(movie, null)
        f.holder.openRate(movie, null)
        runCurrent()
        f.switchTo(a)
        f.failGuestReads = true
        gate.complete(Unit)
        runCurrent()
        assertTrue(f.failures.isEmpty())
        f.holder.retryStorage()
        runCurrent()
        f.assertUntouched()
        assertNull(f.holder.ratePrompt.first())
    }

    @Test fun expiredAndStaleWriteFailuresAreReportedWithoutRetry() = runTest {
        for (status in listOf(401, 409)) {
            val f = Fixture(this, a)
            val error = if (status == 401) TmdbError.SessionExpired else TmdbError.Http(409, null)
            f.writeFailure = WebProxyException("test_failure", status, null, WebWriteOutcome.DefinitelyUnsent, error)
            f.holder.toggleFavourite(movie, a)
            runCurrent()
            assertEquals(1, f.requests.size)
            assertEquals(LibraryWriteResult.Failed(error), f.outcomes.single().result)
            assertEquals(2, f.accountReads.size, "preflight and reconciliation only")
            f.holder.retryStorage()
            runCurrent()
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun possiblyLandedFailureReconcilesButNeverReplaysMutation() = runTest {
        val f = Fixture(this, a)
        f.landThenFail = true
        f.writeFailure = WebProxyException("test_ambiguous", null, null, WebWriteOutcome.PossiblyLanded, TmdbError.Network)
        f.holder.toggleWatchlist(movie, a)
        runCurrent()
        assertEquals(listOf(a), f.requests.map { it.session })
        assertEquals(LibraryWriteResult.Failed(TmdbError.Network), f.outcomes.single().result)
        assertTrue(f.repository.state(movie.ref).first { it.known }.watchlist)
        f.holder.retryStorage()
        runCurrent()
        assertEquals(1, f.requests.size)
    }

    private data class Request(val session: AuthSession, val ref: MediaRef, val kind: LibraryKind, val target: LibraryState)

    private class Fixture(scope: TestScope, initial: AuthSession?) {
        val actual = MutableStateFlow(initial)
        val account = FakeAccountLibrary()
        val rows = InMemoryGuestLibraryStore()
        val requests = mutableListOf<Request>()
        val accountReads = mutableListOf<AuthSession>()
        val outcomes = mutableListOf<LibraryWriteOutcome>()
        val failures = mutableListOf<StorageException>()
        var beforeGuestRead: suspend () -> Unit = {}
        var beforeAccountRead: suspend () -> Unit = {}
        var beforeSessionRead: suspend () -> Unit = {}
        var guestReads = 0
        var failGuestReads = false
        var failGuestWrites = false
        var writeFailure: WebProxyException? = null
        var landThenFail = false
        private val guest = object : GuestLibraryStore by rows {
            override fun observeTitle(ref: MediaRef): Flow<List<GuestLibraryRow>> = flow {
                guestReads++
                beforeGuestRead()
                if (failGuestReads) throw StorageException("test storage unavailable")
                emitAll(rows.observeTitle(ref))
            }
            override suspend fun upsert(row: GuestLibraryRow) {
                if (failGuestWrites) throw StorageException("test persistence unavailable")
                rows.upsert(row)
            }
        }
        private val source = object : AccountLibrarySource by account {
            override suspend fun states(session: AuthSession, ref: MediaRef): LibraryState {
                accountReads += session
                beforeAccountRead()
                return account.states(session, ref)
            }
            override suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState) {
                requests += Request(session, ref, kind, target)
                if (writeFailure == null || landThenFail) account.write(session, ref, kind, target)
                writeFailure?.let { throw it }
            }
        }
        val repository = LibraryRepository(flow { beforeSessionRead(); emitAll(actual) }, GuestLibrarySource(guest), source)
        val holder = LibraryActionsStateHolder(repository, scope.backgroundScope, scope.backgroundScope,
            { outcomes += it }, { failures += it }).also { it.onIdentity(initial) }

        fun switchTo(session: AuthSession?) {
            actual.value = session
            holder.onIdentity(session)
        }

        suspend fun assertUntouched() {
            assertTrue(requests.isEmpty(), "no account mutation")
            assertTrue(rows.all().isEmpty(), "no guest mutation")
            assertTrue(outcomes.isEmpty(), "no superseded outcome")
        }
    }
}
