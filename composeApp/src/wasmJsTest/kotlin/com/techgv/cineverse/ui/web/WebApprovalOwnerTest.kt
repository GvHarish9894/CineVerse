package com.techgv.cineverse.ui.web

import com.techgv.cineverse.di.WebSessionState
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class WebApprovalOwnerTest {
    @Test fun unknownFailedAndSignedAreNotGuestStartAuthority() = runTest {
        val f = WebApprovalFixture()
        try {
            f.owner.show()
            f.owner.startApproval()
            assertFalse(f.owner.state.value.canStart)
            assertEquals(0, f.count("/api/auth/start"))
            f.sessionStatus = HttpStatusCode.ServiceUnavailable
            f.start()
            assertIs<WebSessionState.Failed>(f.owner.state.value.session)
            f.owner.startApproval()
            assertEquals(0, f.count("/api/auth/start"))
            f.sessionStatus = HttpStatusCode.OK
            f.refresh()
            assertTrue(f.owner.state.value.canStart)
            f.sessionBody = WebApprovalFixture.SIGNED
            f.refresh()
            f.settled(WebApprovalPhase.Closed)
            f.owner.show(); f.owner.startApproval()
            assertEquals(WebApprovalPhase.Closed, f.owner.state.value.phase)
            assertEquals(0, f.count("/api/auth/start"))
        } finally { f.close() }
    }

    @Test fun startCoalescesAndOnlySeparateGestureOpensTheValidatedDescriptor() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show()
            val gate = f.gate()
            f.beforeReply = { if (it == "/api/auth/start") gate.await() }
            f.owner.startApproval(); f.owner.startApproval(); f.owner.continueToTmdb()
            while (f.count("/api/auth/start") == 0) yield()
            assertEquals(WebApprovalPhase.Starting, f.owner.state.value.phase)
            assertEquals(1, f.count("/api/auth/start"))
            assertTrue(f.opened.isEmpty())
            gate.complete(Unit)
            f.settled(WebApprovalPhase.Waiting)
            f.owner.state.first { it.canOpen }
            assertTrue(f.opened.isEmpty())
            f.owner.continueToTmdb()
            assertEquals(1, f.opened.size) // Synchronous, before any yielding/awaiting.
            assertTrue(f.owner.state.value.openRequested)
            f.owner.continueToTmdb()
            assertSame(f.opened[0], f.opened[1])
            assertEquals(1, f.count("/api/auth/start"))
            assertFalse(f.owner.state.value.toString().contains("isolated-fixture"))
            assertFalse(f.owner.state.value.toString().contains("server-fixture"))
        } finally { f.close() }
    }

    @Test fun malformedApprovalNeverReachesOpenerAndRemainsPossiblyLanded() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show()
            f.startBody = """{"approveUrl":"https://example.invalid/auth/access?request_token=fixture","attemptId":"fixture"}"""
            f.owner.startApproval()
            f.settled(WebApprovalPhase.Uncertain)
            f.owner.continueToTmdb(); f.owner.startApproval()
            assertTrue(f.opened.isEmpty())
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
            f.start(); f.owner.show(); f.owner.startApproval()
            f.settled(WebApprovalPhase.Waiting)
            val reads = f.count("/api/session")
            val gate = f.gate()
            f.beforeReply = { if (it == "/api/session") gate.await() }
            // Same method for a root focus, visibility or signedIn=1 query hint; it accepts no proof flag.
            f.owner.approvalReturned(); f.owner.approvalReturned(); f.owner.startApproval()
            while (f.count("/api/session") == reads) yield()
            assertEquals(WebApprovalPhase.Checking, f.owner.state.value.phase)
            assertFalse(f.owner.state.value.canOpen)
            gate.complete(Unit)
            f.settled(WebApprovalPhase.Waiting)
            f.runtime.session.first { it is WebSessionState.Ready }
            assertNull((f.runtime.session.value as WebSessionState.Ready).session)
            assertTrue(f.opened.isEmpty())
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
            f.owner.startApproval()
            f.settled(WebApprovalPhase.Uncertain)
            f.sessionStatus = HttpStatusCode.ServiceUnavailable
            f.owner.approvalReturned()
            f.owner.state.first { it.phase == WebApprovalPhase.Uncertain && it.canCheck }
            assertEquals(WebApprovalIssue.StartUnknown, f.owner.state.value.issue)
            f.sessionStatus = HttpStatusCode.OK
            f.owner.approvalReturned()
            f.owner.state.first { it.phase == WebApprovalPhase.Uncertain && it.canCheck }
            assertFalse(f.owner.state.value.canStart)
            f.owner.startApproval()
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
            f.owner.startApproval()
            f.settled(WebApprovalPhase.Error)
            f.owner.state.first { it.canStart }
            f.owner.approvalReturned()
            f.owner.state.first { it.canStart }
            assertEquals(1, f.count("/api/auth/start"))
            f.startStatus = HttpStatusCode.OK; f.startBody = WebApprovalFixture.APPROVAL
            f.owner.startApproval()
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
            f.owner.startApproval()
            while (f.count("/api/auth/start") == 0) yield()
            f.owner.dismiss(); f.owner.dismiss(); f.owner.approvalReturned()
            assertEquals(WebApprovalPhase.Cancelling, f.owner.state.value.phase)
            assertEquals(0, f.count("/api/auth/cancel"))
            gate.complete(Unit)
            f.settled(WebApprovalPhase.Closed)
            assertEquals(1, f.count("/api/auth/cancel"))
            assertTrue(f.opened.isEmpty())
            f.owner.continueToTmdb()
            assertTrue(f.opened.isEmpty())
        } finally { f.close() }
    }

    @Test fun closeDuringCheckDropsItsLateFeedbackAndCoalescesCancellation() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show(); f.owner.startApproval()
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
            f.start(); f.owner.show(); f.owner.startApproval()
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
            f.start(); f.owner.show(); f.owner.startApproval()
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
            f.start(); f.owner.show(); f.owner.startApproval()
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
            f.owner.startApproval()
            while (f.count("/api/auth/start") == 0) yield()
            f.sessionBody = WebApprovalFixture.SIGNED
            f.refresh(); f.settled(WebApprovalPhase.Closed)
            f.sessionBody = WebApprovalFixture.GUEST
            f.refresh()
            gate.complete(Unit)
            yield()
            assertEquals(WebApprovalPhase.Closed, f.owner.state.value.phase)
            f.owner.continueToTmdb()
            assertTrue(f.opened.isEmpty())
            assertEquals(0, f.count("/api/auth/cancel"))
        } finally { f.close() }
    }

    @Test fun disposalStopsFeedbackWithoutServerCancelAndSimpleCloseWithoutAttemptIsLocal() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show(); f.owner.dismiss()
            assertEquals(WebApprovalPhase.Closed, f.owner.state.value.phase)
            assertEquals(0, f.count("/api/auth/cancel"))
            f.owner.show()
            val gate = f.gate()
            f.beforeReply = { if (it == "/api/auth/start") withContext(NonCancellable) { gate.await() } }
            f.owner.startApproval()
            while (f.count("/api/auth/start") == 0) yield()
            f.owner.close()
            gate.complete(Unit)
            yield()
            f.owner.show(); f.owner.startApproval(); f.owner.dismiss(); f.owner.approvalReturned()
            assertEquals(WebApprovalPhase.Closed, f.owner.state.value.phase)
            assertTrue(f.opened.isEmpty())
            assertEquals(0, f.count("/api/auth/cancel"))
            assertEquals(0, f.count("/api/auth/logout"))
        } finally { f.close() }
    }

    @Test fun failedOpenUsesOnlyExplicitGestureToRetrySameDescriptor() = runTest {
        val f = WebApprovalFixture()
        try {
            f.start(); f.owner.show(); f.owner.startApproval()
            f.settled(WebApprovalPhase.Waiting)
            f.throwOnOpen = true
            f.owner.continueToTmdb()
            assertEquals(WebApprovalIssue.Open, f.owner.state.value.issue)
            f.throwOnOpen = false
            f.owner.continueToTmdb()
            assertSame(f.opened[0], f.opened[1])
            assertEquals(1, f.count("/api/auth/start"))
            assertEquals(WebApprovalPhase.Waiting, f.owner.state.value.phase)
        } finally { f.close() }
    }
}
