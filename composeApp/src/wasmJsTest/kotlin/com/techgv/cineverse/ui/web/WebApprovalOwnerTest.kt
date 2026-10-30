package com.techgv.cineverse.ui.web

import com.techgv.cineverse.di.WebSessionState
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WebApprovalOwnerTest {
    @Test fun unknownFailedAndSignedAreNotGuestStartAuthority() = runTest {
        val f = WebApprovalFixture()
        try {
            f.owner.show()
            f.owner.continueToTmdb()
            assertFalse(f.owner.state.value.canStart)
            assertEquals(0, f.count("/api/auth/start"))
            f.sessionStatus = HttpStatusCode.ServiceUnavailable
            f.start()
            assertIs<WebSessionState.Failed>(f.owner.state.value.session)
            f.owner.continueToTmdb()
            assertEquals(0, f.count("/api/auth/start"))
            f.sessionStatus = HttpStatusCode.OK
            f.refresh()
            assertTrue(f.owner.state.value.canStart)
            f.sessionBody = WebApprovalFixture.SIGNED
            f.refresh()
            f.settled(WebApprovalPhase.Closed)
            f.owner.show(); f.owner.continueToTmdb()
            assertEquals(WebApprovalPhase.Closed, f.owner.state.value.phase)
            assertEquals(0, f.count("/api/auth/start"))
            assertTrue(f.tabs.isEmpty(), "No placeholder tab without verified guest authority")
        } finally { f.close() }
    }

    @Test fun oneGestureOpensThePendingTabFirstThenNavigatesOnlyToTheValidatedUrlAndCoalesces() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show()
            val gate = f.gate()
            f.beforeReply = { if (it == "/api/auth/start") gate.await() }
            f.owner.continueToTmdb()
            assertEquals(1, f.tabs.size) // Synchronous inside the gesture, before any yielding/awaiting.
            f.owner.continueToTmdb(); f.owner.openTmdbAgain()
            while (f.count("/api/auth/start") == 0) yield()
            assertEquals(WebApprovalPhase.Starting, f.owner.state.value.phase)
            assertEquals(1, f.count("/api/auth/start"))
            assertEquals(1, f.tabs.size)
            assertTrue(f.tabs[0].navigated.isEmpty())
            assertTrue(f.opened.isEmpty())
            gate.complete(Unit)
            f.settled(WebApprovalPhase.Waiting)
            assertEquals(listOf(WebApprovalFixture.APPROVE_URL), f.tabs[0].navigated)
            assertFalse(f.tabs[0].closed)
            f.owner.state.first { it.canOpen }
            assertFalse(f.owner.state.value.canStart)
            f.owner.openTmdbAgain() // A later explicit gesture reopens the same descriptor.
            assertEquals(WebApprovalFixture.APPROVE_URL, f.opened.single().approveUrl)
            f.owner.openTmdbAgain()
            assertSame(f.opened[0], f.opened[1])
            assertEquals(1, f.count("/api/auth/start"))
            assertEquals(1, f.tabs.size)
            assertFalse(f.owner.state.value.toString().contains("isolated-fixture"))
            assertFalse(f.owner.state.value.toString().contains("server-fixture"))
        } finally { f.close() }
    }

    @Test fun malformedApprovalNeverReachesOpenerAndRemainsPossiblyLanded() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show()
            f.startBody = """{"approveUrl":"https://example.invalid/auth/access?request_token=fixture","attemptId":"fixture"}"""
            f.owner.continueToTmdb()
            f.settled(WebApprovalPhase.Uncertain)
            f.owner.openTmdbAgain(); f.owner.continueToTmdb()
            assertTrue(f.opened.isEmpty())
            assertTrue(f.tabs.single().closed)
            assertTrue(f.tabs.single().navigated.isEmpty())
            assertFalse(f.owner.state.value.canStart)
            assertEquals(1, f.count("/api/auth/start"))
            f.owner.dismiss()
            f.settled(WebApprovalPhase.Closed)
            assertEquals(1, f.count("/api/auth/cancel"))
        } finally { f.close() }
    }

    @Test fun returnHintsAndGuestReadsNeverProveSignInOrCreateAnotherAttempt() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show(); f.owner.continueToTmdb()
            f.settled(WebApprovalPhase.Waiting)
            val reads = f.count("/api/session")
            val gate = f.gate()
            f.beforeReply = { if (it == "/api/session") gate.await() }
            // Same method for a root focus, visibility or signedIn=1 query hint; it accepts no proof flag.
            f.owner.approvalReturned(); f.owner.approvalReturned(); f.owner.continueToTmdb()
            while (f.count("/api/session") == reads) yield()
            assertEquals(WebApprovalPhase.Checking, f.owner.state.value.phase)
            assertFalse(f.owner.state.value.canOpen)
            gate.complete(Unit)
            f.settled(WebApprovalPhase.Waiting)
            f.runtime.session.first { it is WebSessionState.Ready }
            assertNull((f.runtime.session.value as WebSessionState.Ready).session)
            assertTrue(f.opened.isEmpty())
            assertEquals(1, f.tabs.size)
            assertEquals(1, f.count("/api/auth/start"))
            assertEquals(0, f.count("/api/auth/cancel"))
            f.sessionBody = WebApprovalFixture.SIGNED
            f.owner.approvalReturned()
            f.settled(WebApprovalPhase.Closed)
            assertNotNull((f.runtime.session.value as WebSessionState.Ready).session)
            assertEquals(0, f.count("/api/auth/logout"))
        } finally { f.close() }
    }

    @Test fun readRetryPreservesAmbiguousStartAndNeverReplaysMutation() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show()
            f.startStatus = HttpStatusCode.ServiceUnavailable
            f.startBody = """{"error":"upstream_error"}"""
            f.owner.continueToTmdb()
            f.settled(WebApprovalPhase.Uncertain)
            f.sessionStatus = HttpStatusCode.ServiceUnavailable
            f.owner.approvalReturned()
            f.owner.state.first { it.phase == WebApprovalPhase.Uncertain && it.canCheck }
            assertEquals(WebApprovalIssue.StartUnknown, f.owner.state.value.issue)
            f.sessionStatus = HttpStatusCode.OK
            f.owner.approvalReturned()
            f.owner.state.first { it.phase == WebApprovalPhase.Uncertain && it.canCheck }
            assertFalse(f.owner.state.value.canStart)
            f.owner.continueToTmdb()
            assertEquals(1, f.count("/api/auth/start"))
            assertEquals(0, f.count("/api/auth/cancel"))
        } finally { f.close() }
    }

    @Test fun definitelyUnsentStartAllowsOnlyExplicitNewStart() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show()
            f.startStatus = HttpStatusCode.TooManyRequests
            f.startBody = """{"error":"rate_limited"}"""
            f.owner.continueToTmdb()
            f.settled(WebApprovalPhase.Error)
            f.owner.state.first { it.canStart }
            f.owner.approvalReturned()
            f.owner.state.first { it.canStart }
            assertEquals(1, f.count("/api/auth/start"))
            f.startStatus = HttpStatusCode.OK; f.startBody = WebApprovalFixture.APPROVAL
            f.owner.continueToTmdb()
            f.settled(WebApprovalPhase.Waiting)
            assertEquals(2, f.count("/api/auth/start"))
        } finally { f.close() }
    }

    @Test fun closeDuringStartWaitsForItsCookieThenExplicitlyCancelsOnce() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show()
            val gate = f.gate()
            f.beforeReply = { if (it == "/api/auth/start") gate.await() }
            f.owner.continueToTmdb()
            while (f.count("/api/auth/start") == 0) yield()
            f.owner.dismiss(); f.owner.dismiss(); f.owner.approvalReturned()
            assertEquals(WebApprovalPhase.Cancelling, f.owner.state.value.phase)
            assertEquals(0, f.count("/api/auth/cancel"))
            gate.complete(Unit)
            f.settled(WebApprovalPhase.Closed)
            assertEquals(1, f.count("/api/auth/cancel"))
            assertTrue(f.opened.isEmpty())
            assertTrue(f.tabs.single().closed) // The late descriptor never reaches the placeholder tab.
            assertTrue(f.tabs.single().navigated.isEmpty())
            f.owner.openTmdbAgain()
            assertTrue(f.opened.isEmpty())
        } finally { f.close() }
    }

    @Test fun closeDuringCheckDropsItsLateFeedbackAndCoalescesCancellation() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show(); f.owner.continueToTmdb()
            f.settled(WebApprovalPhase.Waiting)
            val readGate = f.gate()
            val cancelGate = f.gate()
            val reads = f.count("/api/session")
            f.beforeReply = {
                when (it) {
                    "/api/session" -> readGate.await()
                    "/api/auth/cancel" -> cancelGate.await()
                    else -> Unit
                }
            }
            f.owner.approvalReturned()
            while (f.count("/api/session") == reads) yield()
            f.owner.dismiss(); f.owner.dismiss()
            readGate.complete(Unit)
            while (f.count("/api/auth/cancel") == 0) yield()
            assertEquals(WebApprovalPhase.Cancelling, f.owner.state.value.phase)
            assertFalse(f.owner.state.value.canOpen)
            f.owner.dismiss(); f.owner.approvalReturned()
            assertEquals(1, f.count("/api/auth/cancel"))
            cancelGate.complete(Unit)
            f.settled(WebApprovalPhase.Closed)
            assertTrue(f.opened.isEmpty())
        } finally { f.close() }
    }

    @Test fun ambiguousCancelSurvivesReadRetryAndNeedsExplicitCancelChoice() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show(); f.owner.continueToTmdb()
            f.settled(WebApprovalPhase.Waiting)
            f.cancelStatus = HttpStatusCode.ServiceUnavailable
            f.owner.dismiss()
            f.settled(WebApprovalPhase.Uncertain)
            assertEquals(WebApprovalIssue.CancelUnknown, f.owner.state.value.issue)
            f.owner.approvalReturned()
            f.owner.state.first { it.phase == WebApprovalPhase.Uncertain && it.canCheck }
            assertEquals(WebApprovalIssue.CancelUnknown, f.owner.state.value.issue)
            assertEquals(1, f.count("/api/auth/cancel"))
            assertFalse(f.owner.state.value.canOpen)
            assertFalse(f.owner.state.value.canStart)
            f.cancelStatus = HttpStatusCode.NoContent
            f.owner.dismiss()
            f.settled(WebApprovalPhase.Closed)
            assertEquals(2, f.count("/api/auth/cancel"))
        } finally { f.close() }
    }

    @Test fun cancelCanCommitButReadFailWithoutClaimingGuestOrCancelSuccess() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show(); f.owner.continueToTmdb()
            f.settled(WebApprovalPhase.Waiting)
            f.sessionStatus = HttpStatusCode.ServiceUnavailable
            f.owner.dismiss()
            f.settled(WebApprovalPhase.Uncertain)
            assertEquals(WebApprovalIssue.CancelUnknown, f.owner.state.value.issue)
            assertIs<WebSessionState.Failed>(f.runtime.session.first { it is WebSessionState.Failed })
            assertEquals(1, f.count("/api/auth/cancel"))
            f.sessionStatus = HttpStatusCode.OK
            f.owner.approvalReturned()
            f.owner.state.first { it.phase == WebApprovalPhase.Uncertain && it.canCheck }
            assertEquals(1, f.count("/api/auth/cancel"))
        } finally { f.close() }
    }

    @Test fun committedIdentityDuringExplicitCancelClosesWithoutLogout() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show(); f.owner.continueToTmdb()
            f.settled(WebApprovalPhase.Waiting)
            f.sessionBody = WebApprovalFixture.SIGNED
            f.owner.dismiss()
            f.settled(WebApprovalPhase.Closed)
            f.runtime.session.first { it is WebSessionState.Ready && it.session != null }
            assertEquals(1, f.count("/api/auth/cancel"))
            assertEquals(0, f.count("/api/auth/logout"))
            assertNotNull((f.owner.state.value.session as WebSessionState.Ready).session)
        } finally { f.close() }
    }

    @Test fun sessionReplacementAndLateStartCannotReviveClosedModal() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show()
            val gate = f.gate()
            f.beforeReply = { if (it == "/api/auth/start") gate.await() }
            f.owner.continueToTmdb()
            while (f.count("/api/auth/start") == 0) yield()
            f.sessionBody = WebApprovalFixture.SIGNED
            f.refresh(); f.settled(WebApprovalPhase.Closed)
            f.sessionBody = WebApprovalFixture.GUEST
            f.refresh()
            gate.complete(Unit)
            yield()
            assertEquals(WebApprovalPhase.Closed, f.owner.state.value.phase)
            f.owner.openTmdbAgain()
            assertTrue(f.opened.isEmpty())
            assertTrue(f.tabs.single().closed)
            assertTrue(f.tabs.single().navigated.isEmpty())
            assertEquals(0, f.count("/api/auth/cancel"))
        } finally { f.close() }
    }

    @Test fun disposalStopsFeedbackWithoutServerCancel() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show()
            val gate = f.gate()
            f.beforeReply = { if (it == "/api/auth/start") withContext(NonCancellable) { gate.await() } }
            f.owner.continueToTmdb()
            while (f.count("/api/auth/start") == 0) yield()
            f.owner.close()
            gate.complete(Unit)
            yield()
            f.owner.show(); f.owner.continueToTmdb(); f.owner.dismiss(); f.owner.approvalReturned()
            assertEquals(WebApprovalPhase.Closed, f.owner.state.value.phase)
            assertTrue(f.opened.isEmpty())
            assertTrue(f.tabs.single().closed) // Local placeholder only; disposal never cancels server attempts.
            assertTrue(f.tabs.single().navigated.isEmpty())
            assertEquals(0, f.count("/api/auth/cancel"))
            assertEquals(0, f.count("/api/auth/logout"))
        } finally { f.close() }
    }

    @Test fun noLocalDescriptorDismissCancelsOtherBrowserAttemptOnce() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start()
            f.runtime.startApproval() // A previous app/tab owns the attempt, not this owner.
            f.owner.show()
            val gate = f.gate()
            f.beforeReply = { if (it == "/api/auth/cancel") gate.await() }
            f.owner.dismiss(); f.owner.dismiss(); f.owner.approvalReturned()
            assertEquals(WebApprovalPhase.Cancelling, f.owner.state.value.phase)
            while (f.count("/api/auth/cancel") == 0) yield()
            assertEquals(1, f.count("/api/auth/cancel"))
            assertEquals(1, f.count("/api/auth/start"))
            assertTrue(f.opened.isEmpty())
            gate.complete(Unit)
            f.settled(WebApprovalPhase.Closed)
            assertNull((f.runtime.session.value as WebSessionState.Ready).session)
            assertEquals(0, f.count("/api/auth/logout"))
        } finally { f.close() }
    }

    @Test fun startingObserverCloseClosesPendingTabBeforeAnyStart() = runTest {
        assertStartingRevocation { it.owner.close() }
    }

    @Test fun startingObserverDismissClosesPendingTabBeforeAnyStart() = runTest {
        assertStartingRevocation { it.owner.dismiss() }
    }

    @Test fun startingObserverParentCancellationClosesPendingTabBeforeAnyStart() = runTest {
        assertStartingRevocation { it.ownerScope.cancel() }
    }

    @Test fun startingObserverSessionInvalidationClosesPendingTabBeforeAnyStart() = runTest {
        assertStartingRevocation { it.runtime.revalidateSession() }
    }

    /** Revocation inside the Starting publication (after the gesture's tab, before the POST) starts and navigates nothing. */
    private suspend fun assertStartingRevocation(revoke: (WebApprovalFixture) -> Unit) = coroutineScope {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show()
            val gate = f.gate()
            f.beforeReply = { if (it == "/api/session") gate.await() }
            var revoked = false
            val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                f.owner.state.first { it.phase == WebApprovalPhase.Starting }
                revoke(f)
                revoked = true
            }
            f.owner.continueToTmdb()
            assertTrue(revoked, "Observer must revoke within the Starting publication")
            assertTrue(f.tabs.single().closed)
            assertTrue(f.tabs.single().navigated.isEmpty())
            assertEquals(0, f.count("/api/auth/start"))
            assertTrue(f.opened.isEmpty())
            observer.join()
        } finally { f.close() }
    }

    @Test fun queuedStartRevokedByCheckingReleasesBookkeepingForExplicitStart() = runTest {
        val ownerScheduler = TestCoroutineScheduler()
        val f = WebApprovalFixture(StandardTestDispatcher(ownerScheduler))
        try {
            f.start(); ownerScheduler.runCurrent(); f.owner.show(); f.owner.continueToTmdb()
            val gate = f.gate()
            f.beforeReply = { if (it == "/api/session") gate.await() }
            f.runtime.revalidateSession()
            assertIs<WebSessionState.Checking>(f.runtime.session.value)
            assertEquals(WebApprovalPhase.Starting, f.owner.state.value.phase)
            val reads = f.count("/api/session")
            ownerScheduler.runCurrent()
            assertEquals(reads, f.count("/api/session"))
            assertEquals(0, f.count("/api/auth/start"))
            assertTrue(f.tabs.single().closed)
            assertEquals(WebApprovalPhase.Ready, f.owner.state.value.phase)
            assertTrue(f.owner.state.value.canCheck)
            assertFalse(f.owner.state.value.canStart)
            gate.complete(Unit)
            f.runtime.session.first { it is WebSessionState.Ready }
            ownerScheduler.runCurrent()
            assertTrue(f.owner.state.value.canStart)
            assertEquals(0, f.count("/api/auth/start")) // Recovery cannot replay the skipped mutation.
            f.owner.continueToTmdb()
            while (!f.owner.state.value.canOpen) { ownerScheduler.runCurrent(); yield() }
            assertEquals(WebApprovalPhase.Waiting, f.owner.state.value.phase)
            assertEquals(listOf(WebApprovalFixture.APPROVE_URL), f.tabs.last().navigated)
            assertEquals(1, f.count("/api/auth/start"))
            assertEquals(0, f.count("/api/auth/cancel"))
        } finally { f.close() }
    }

    @Test fun queuedStartRevokedByFailedSessionReleasesBookkeepingForExplicitStart() = runTest {
        val ownerScheduler = TestCoroutineScheduler()
        val f = WebApprovalFixture(StandardTestDispatcher(ownerScheduler))
        try {
            f.start(); ownerScheduler.runCurrent(); f.owner.show(); f.owner.continueToTmdb()
            f.sessionStatus = HttpStatusCode.ServiceUnavailable
            f.refresh()
            assertIs<WebSessionState.Failed>(f.runtime.session.value)
            assertEquals(WebApprovalPhase.Starting, f.owner.state.value.phase)
            val reads = f.count("/api/session")
            ownerScheduler.runCurrent()
            assertEquals(reads, f.count("/api/session"))
            assertEquals(0, f.count("/api/auth/start"))
            assertTrue(f.tabs.single().closed)
            assertEquals(WebApprovalPhase.Ready, f.owner.state.value.phase)
            assertTrue(f.owner.state.value.canCheck)
            assertFalse(f.owner.state.value.canStart)
            f.sessionStatus = HttpStatusCode.OK
            f.refresh(); ownerScheduler.runCurrent()
            assertTrue(f.owner.state.value.canStart)
            assertEquals(0, f.count("/api/auth/start"))
            f.owner.continueToTmdb()
            while (!f.owner.state.value.canOpen) { ownerScheduler.runCurrent(); yield() }
            assertEquals(WebApprovalPhase.Waiting, f.owner.state.value.phase)
            assertEquals(listOf(WebApprovalFixture.APPROVE_URL), f.tabs.last().navigated)
            assertEquals(1, f.count("/api/auth/start"))
            assertEquals(0, f.count("/api/auth/cancel"))
        } finally { f.close() }
    }

    @Test fun queuedStartRevokedBySignedSessionCannotCreateAttemptOrStickBusy() = runTest {
        val ownerScheduler = TestCoroutineScheduler()
        val f = WebApprovalFixture(StandardTestDispatcher(ownerScheduler))
        try {
            f.start(); ownerScheduler.runCurrent(); f.owner.show(); f.owner.continueToTmdb()
            f.sessionBody = WebApprovalFixture.SIGNED
            f.refresh()
            assertNotNull((f.runtime.session.value as WebSessionState.Ready).session)
            assertEquals(WebApprovalPhase.Starting, f.owner.state.value.phase)
            val reads = f.count("/api/session")
            ownerScheduler.runCurrent()
            assertEquals(reads, f.count("/api/session"))
            assertEquals(0, f.count("/api/auth/start"))
            assertTrue(f.tabs.single().closed)
            assertEquals(WebApprovalPhase.Closed, f.owner.state.value.phase)
            f.sessionBody = WebApprovalFixture.GUEST
            f.refresh(); ownerScheduler.runCurrent()
            f.owner.show()
            assertTrue(f.owner.state.value.canStart)
            assertEquals(0, f.count("/api/auth/start"))
            f.owner.continueToTmdb()
            while (!f.owner.state.value.canOpen) { ownerScheduler.runCurrent(); yield() }
            assertEquals(WebApprovalPhase.Waiting, f.owner.state.value.phase)
            assertEquals(listOf(WebApprovalFixture.APPROVE_URL), f.tabs.last().navigated)
            assertEquals(1, f.count("/api/auth/start"))
            assertEquals(0, f.count("/api/auth/cancel"))
            assertEquals(0, f.count("/api/auth/logout"))
        } finally { f.close() }
    }

    @Test fun parentScopeCancellationAloneGatesActionsAndSessionFeedback() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show(); f.owner.continueToTmdb()
            f.settled(WebApprovalPhase.Waiting)
            f.owner.state.first { it.canOpen }
            f.ownerScope.cancel() // No owner.close(): the parent lifetime itself is authoritative.
            val snapshot = f.owner.state.value
            val calls = f.calls.toList()
            f.owner.openTmdbAgain(); f.owner.show(); f.owner.continueToTmdb()
            f.owner.approvalReturned(); f.owner.dismiss()
            assertTrue(f.opened.isEmpty())
            assertEquals(1, f.tabs.size)
            assertEquals(calls, f.calls)
            assertEquals(snapshot, f.owner.state.value)
            f.sessionBody = WebApprovalFixture.SIGNED
            f.refresh()
            assertEquals(snapshot, f.owner.state.value)
            assertEquals(0, f.count("/api/auth/cancel"))
            assertEquals(0, f.count("/api/auth/logout"))
        } finally { f.close() }
    }

    @Test fun parentScopeCancellationDropsLateStartWithoutAutomaticCleanup() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show()
            val gate = f.gate()
            f.beforeReply = { if (it == "/api/auth/start") withContext(NonCancellable) { gate.await() } }
            f.owner.continueToTmdb()
            while (f.count("/api/auth/start") == 0) yield()
            f.ownerScope.cancel()
            val snapshot = f.owner.state.value
            f.owner.dismiss(); f.owner.approvalReturned(); f.owner.continueToTmdb(); f.owner.openTmdbAgain()
            gate.complete(Unit)
            f.ownerScope.coroutineContext.job.join()
            assertEquals(snapshot, f.owner.state.value)
            assertTrue(f.opened.isEmpty())
            assertTrue(f.tabs.single().closed)
            assertTrue(f.tabs.single().navigated.isEmpty())
            assertEquals(1, f.count("/api/auth/start"))
            assertEquals(0, f.count("/api/auth/cancel"))
            assertEquals(0, f.count("/api/auth/logout"))
        } finally { f.close() }
    }

    @Test fun failedOpenUsesOnlyExplicitGestureToRetrySameDescriptor() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show()
            f.throwOnOpen = true
            f.owner.continueToTmdb() // No tab: nothing is started on the server.
            assertEquals(WebApprovalIssue.Open, f.owner.state.value.issue)
            assertEquals(0, f.count("/api/auth/start"))
            assertTrue(f.tabs.isEmpty())
            assertTrue(f.owner.state.value.canStart)
            f.throwOnOpen = false
            f.throwOnNavigate = true
            f.owner.continueToTmdb()
            f.settled(WebApprovalPhase.Error)
            assertEquals(WebApprovalIssue.Open, f.owner.state.value.issue)
            assertTrue(f.tabs.single().closed)
            assertTrue(f.owner.state.value.canOpen)
            assertFalse(f.owner.state.value.canStart)
            f.owner.openTmdbAgain()
            f.owner.openTmdbAgain()
            assertEquals(WebApprovalFixture.APPROVE_URL, f.opened[0].approveUrl)
            assertSame(f.opened[0], f.opened[1])
            assertEquals(1, f.count("/api/auth/start"))
            assertEquals(WebApprovalPhase.Waiting, f.owner.state.value.phase)
        } finally { f.close() }
    }

    @Test fun focusAndVisibilityHintsDuringPendingStartNeitherRevokeItNorTouchChecking() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show()
            val sessions = mutableListOf<WebSessionState>()
            val recorder = f.ownerScope.launch(start = CoroutineStart.UNDISPATCHED) { f.runtime.session.collect { sessions += it } }
            val gate = f.gate()
            f.beforeReply = { if (it == "/api/auth/start") gate.await() }
            f.owner.continueToTmdb()
            while (f.count("/api/auth/start") == 0) yield()
            val reads = f.count("/api/session")
            dispatchWindowFocus(); dispatchPageVisible() // The user switched tabs while the POST is pending.
            while (f.count("/api/session") == reads) yield()
            gate.complete(Unit)
            f.settled(WebApprovalPhase.Waiting)
            assertEquals(listOf(WebApprovalFixture.APPROVE_URL), f.tabs.single().navigated)
            assertEquals(listOf<WebSessionState>(WebSessionState.Ready(null)), sessions)
            assertEquals(1, f.count("/api/auth/start"))
            assertEquals(0, f.count("/api/auth/cancel"))
            recorder.cancel()
        } finally { f.close() }
    }
}
