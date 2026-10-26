package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.library.ImportOfferRecord
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.presentation.ImportPrompt
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WebAccountOwnerTest {
    private val logout = "/api/auth/logout"
    private val watchlist = "/api/account/watchlist"

    @Test fun capturedSessionConfirmsOnceAndOnlyAuthoritativeReadShowsSignedOut() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            f.owner.startSignOut(WebAccountFixture.A)
            assertEquals(WebSignOutPhase.Confirm, f.owner.state.value.signOut)
            assertEquals("Fixture viewer", f.owner.state.value.accountName)
            assertTrue(f.owner.state.value.canConfirmSignOut)
            assertEquals(0, f.count(logout))
            f.owner.confirmSignOut()
            f.signOutSettled(WebSignOutPhase.SignedOut)
            assertEquals(1, f.count(logout))
            assertEquals(WebSessionState.Ready(null), f.runtime.session.value)
            assertFalse(f.owner.state.value.toString().contains("fixture-account"))
            f.owner.dismiss()
            assertEquals(WebSignOutPhase.Closed, f.owner.state.value.signOut)
        } finally { f.close() }
    }

    @Test fun changedGenerationBeforeOrAfterConfirmDialogSendsNothing() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            f.owner.startSignOut(WebAccountFixture.A_NEXT) // Same account, different opaque generation.
            assertEquals(WebSignOutPhase.Changed, f.owner.state.value.signOut)
            f.owner.confirmSignOut()
            f.owner.dismiss()
            f.owner.startSignOut(WebAccountFixture.A)
            assertEquals(WebSignOutPhase.Confirm, f.owner.state.value.signOut)
            f.sessionBody = WebAccountFixture.SIGNED_A_NEXT
            f.refresh()
            f.signOutSettled(WebSignOutPhase.Changed)
            f.owner.confirmSignOut()
            assertEquals(0, f.count(logout))
            assertEquals(WebSignOutPhase.Changed, f.owner.state.value.signOut)
        } finally { f.close() }
    }

    @Test fun unknownSessionDisablesConfirmWithoutSending() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            f.owner.startSignOut(WebAccountFixture.A)
            f.sessionStatus = HttpStatusCode.ServiceUnavailable
            f.refresh()
            assertIs<WebSessionState.Failed>(f.runtime.session.value)
            assertEquals(WebSignOutPhase.Confirm, f.owner.state.value.signOut)
            assertFalse(f.owner.state.value.canConfirmSignOut)
            f.owner.confirmSignOut()
            assertEquals(0, f.count(logout))
        } finally { f.close() }
    }

    @Test fun repeatedClicksCoalesceWhileSigningOut() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            val gate = f.gate()
            f.beforeReply = { _, path -> if (path == logout) gate.await() }
            f.owner.startSignOut(WebAccountFixture.A)
            f.owner.confirmSignOut(); f.owner.confirmSignOut()
            f.owner.startSignOut(WebAccountFixture.A); f.owner.confirmSignOut(); f.owner.dismiss()
            while (f.count(logout) == 0) yield()
            assertEquals(WebSignOutPhase.SigningOut, f.owner.state.value.signOut)
            assertFalse(f.owner.state.value.canConfirmSignOut)
            gate.complete(Unit)
            f.signOutSettled(WebSignOutPhase.SignedOut)
            assertEquals(1, f.count(logout))
        } finally { f.close() }
    }

    @Test fun ambiguousLogoutThatLandedIsConfirmedByReadNotReplayed() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            f.logoutStatus = HttpStatusCode.ServiceUnavailable // Possibly landed: the fake server did clear it.
            f.owner.startSignOut(WebAccountFixture.A); f.owner.confirmSignOut()
            f.signOutSettled(WebSignOutPhase.SignedOut)
            assertEquals(1, f.count(logout))
        } finally { f.close() }
    }

    @Test fun ambiguousLogoutThatDidNotLandStaysSignedInWithoutReplay() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            f.logoutStatus = HttpStatusCode.ServiceUnavailable
            f.logoutLands = false
            f.owner.startSignOut(WebAccountFixture.A); f.owner.confirmSignOut()
            f.signOutSettled(WebSignOutPhase.StillSignedIn)
            assertEquals(1, f.count(logout))
            assertEquals(WebSessionState.Ready(WebAccountFixture.A), f.runtime.session.value)
        } finally { f.close() }
    }

    @Test fun successfulLogoutWhoseReadStillShowsTheSessionResolvesStillSignedInNotHang() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            f.logoutLands = false // 2xx logout, but the follow-up read still shows A: the holder must report false, not nothing.
            f.owner.startSignOut(WebAccountFixture.A); f.owner.confirmSignOut()
            f.signOutSettled(WebSignOutPhase.StillSignedIn)
            assertEquals(1, f.count(logout))
            f.owner.dismiss()
            assertEquals(WebSignOutPhase.Closed, f.owner.state.value.signOut)
        } finally { f.close() }
    }

    @Test fun unknownSessionAtStartKeepsConfirmDisabledAndImportMakesNoClaim() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            f.sessionStatus = HttpStatusCode.ServiceUnavailable
            f.refresh()
            assertIs<WebSessionState.Failed>(f.runtime.session.value)
            f.owner.startSignOut(WebAccountFixture.A)
            assertEquals(WebSignOutPhase.Confirm, f.owner.state.value.signOut) // not "Changed": nothing is known
            assertFalse(f.owner.state.value.canConfirmSignOut)
            f.owner.dismiss()
            f.owner.startImport(WebAccountFixture.A)
            assertNull(f.owner.state.value.importNotice)
            assertEquals(0, f.count(logout))
        } finally { f.close() }
    }

    @Test fun unreadableOutcomeIsUnknownAndCheckOnlyReads() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            f.logoutStatus = HttpStatusCode.ServiceUnavailable
            f.sessionStatus = HttpStatusCode.ServiceUnavailable
            f.owner.startSignOut(WebAccountFixture.A); f.owner.confirmSignOut()
            f.signOutSettled(WebSignOutPhase.Unknown)
            f.owner.checkSignOut()
            f.signOutSettled(WebSignOutPhase.Unknown)
            f.sessionStatus = HttpStatusCode.OK
            f.owner.checkSignOut()
            f.signOutSettled(WebSignOutPhase.SignedOut)
            assertEquals(1, f.count(logout))
        } finally { f.close() }
    }

    @Test fun otherIdentityAfterLogoutIsReportedAsChanged() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            f.beforeReply = { method, path -> if (method == HttpMethod.Post && path == logout) f.sessionBody = WebAccountFixture.SIGNED_B }
            f.owner.startSignOut(WebAccountFixture.A); f.owner.confirmSignOut()
            f.signOutSettled(WebSignOutPhase.Changed)
            assertEquals(1, f.count(logout))
        } finally { f.close() }
    }

    @Test fun reentrantCloseDuringSigningOutPublicationPreventsLogout() = runTest { coroutineScope {
        val f = WebAccountFixture()
        try {
            f.start()
            f.owner.startSignOut(WebAccountFixture.A)
            var revoked = false
            val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                f.owner.state.first { it.signOut == WebSignOutPhase.SigningOut }
                f.owner.close()
                revoked = true
            }
            f.owner.confirmSignOut()
            assertTrue(revoked, "Observer must revoke within SigningOut publication")
            assertEquals(0, f.count(logout))
            observer.join()
        } finally { f.close() }
    } }

    @Test fun disposalDuringSignOutStopsFeedbackOnly() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            val gate = f.gate()
            f.beforeReply = { _, path -> if (path == logout) withContext(NonCancellable) { gate.await() } }
            f.owner.startSignOut(WebAccountFixture.A); f.owner.confirmSignOut()
            while (f.count(logout) == 0) yield()
            f.owner.close()
            assertEquals(WebAccountState(), f.owner.state.value)
            gate.complete(Unit)
            f.runtime.session.first { it == WebSessionState.Ready(null) }
            assertEquals(WebAccountState(), f.owner.state.value)
            f.owner.startSignOut(WebAccountFixture.A); f.owner.startImport(WebAccountFixture.A)
            assertEquals(WebAccountState(), f.owner.state.value)
            assertEquals(1, f.count(logout))
        } finally { f.close() }
    }

    @Test fun parentScopeCancellationAloneGatesEveryAction() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            f.prompt { it.importPrompt is ImportPrompt.Ask }
            f.owner.startSignOut(WebAccountFixture.A)
            f.ownerScope.cancel()
            val snapshot = f.owner.state.value
            val calls = f.calls.toList()
            f.owner.confirmSignOut(); f.owner.importLibrary(); f.owner.keepSeparate(); f.owner.startImport(WebAccountFixture.A)
            f.owner.dismiss(); f.owner.checkSignOut()
            assertEquals(calls, f.calls)
            assertEquals(snapshot, f.owner.state.value)
            f.sessionBody = WebAccountFixture.SIGNED_B
            f.refresh()
            assertEquals(snapshot, f.owner.state.value)
            assertEquals(0, f.count(logout))
            assertNull(f.offers[WebAccountFixture.A.accountObjectId.value])
        } finally { f.close() }
    }

    @Test fun askImportRunsOnceAndReportsImported() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            val ask = f.prompt { it.importPrompt is ImportPrompt.Ask }.importPrompt as ImportPrompt.Ask
            assertEquals(ImportPrompt.Ask("Fixture viewer", 1), ask)
            f.owner.importLibrary(); f.owner.importLibrary(); f.owner.keepSeparate()
            f.prompt { it.importNotice == WebImportNotice.Imported }
            assertEquals(1, f.count(watchlist))
            assertEquals(ImportOfferRecord("fixture-account-a", imported = true, complete = true), f.offers["fixture-account-a"])
            f.owner.dismiss()
            assertNull(f.owner.state.value.importNotice)
        } finally { f.close() }
    }

    @Test fun laterKeepsOfferWhileKeepSeparateIsDurableAndStartImportReportsNothing() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            f.prompt { it.importPrompt is ImportPrompt.Ask }
            f.owner.later()
            assertNull(f.owner.state.value.importPrompt)
            assertNull(f.offers["fixture-account-a"])
            f.owner.startImport(WebAccountFixture.A)
            f.prompt { it.importPrompt is ImportPrompt.Ask }
            f.owner.keepSeparate()
            f.prompt { it.importPrompt == null }
            assertEquals(ImportOfferRecord("fixture-account-a", imported = false, complete = true), f.offers["fixture-account-a"])
            f.owner.startImport(WebAccountFixture.A)
            f.prompt { it.importNotice == WebImportNotice.NothingToImport }
            assertEquals(0, f.count(watchlist))
        } finally { f.close() }
    }

    @Test fun startImportWithChangedIdentityReportsChangedWithoutTouchingHolder() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            f.prompt { it.importPrompt is ImportPrompt.Ask }
            f.owner.later()
            f.owner.startImport(WebAccountFixture.A_NEXT)
            assertEquals(WebImportNotice.Changed, f.owner.state.value.importNotice)
            assertNull(f.owner.state.value.importPrompt)
            f.sessionBody = WebAccountFixture.SIGNED_B
            f.refresh()
            f.prompt { it.importNotice == null }
        } finally { f.close() }
    }

    @Test fun unconfirmedNeedsExplicitFinishOrRetry() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            f.prompt { it.importPrompt is ImportPrompt.Ask }
            f.writeStatus = HttpStatusCode.ServiceUnavailable
            f.owner.importLibrary()
            val unconfirmed = f.prompt { it.importPrompt is ImportPrompt.Unconfirmed }.importPrompt
            assertEquals(ImportPrompt.Unconfirmed(0, listOf("Fixture film")), unconfirmed)
            assertEquals(1, f.count(watchlist))
            f.owner.dismiss() // Escape is Later, never the durable finish.
            assertEquals(ImportOfferRecord("fixture-account-a", imported = true, complete = false), f.offers["fixture-account-a"])
            f.owner.startImport(WebAccountFixture.A)
            f.prompt { it.importPrompt == ImportPrompt.Ask("Fixture viewer", 1, resume = true) }
            f.owner.importLibrary()
            f.prompt { it.importPrompt is ImportPrompt.Unconfirmed }
            assertEquals(1, f.count(watchlist)) // Attempting + absent stays unconfirmed: no blind replay.
            f.writeStatus = HttpStatusCode.OK
            f.owner.retryImport() // Explicit, warned retry.
            f.prompt { it.importNotice == WebImportNotice.Imported }
            assertEquals(2, f.count(watchlist))
        } finally { f.close() }
    }

    @Test fun unconfirmedFinishIsDurable() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            f.prompt { it.importPrompt is ImportPrompt.Ask }
            f.writeStatus = HttpStatusCode.ServiceUnavailable
            f.owner.importLibrary()
            f.prompt { it.importPrompt is ImportPrompt.Unconfirmed }
            f.owner.finishImport(); f.owner.finishImport()
            f.prompt { it.importPrompt == null }
            assertEquals(ImportOfferRecord("fixture-account-a", imported = true, complete = true), f.offers["fixture-account-a"])
            assertEquals(1, f.count(watchlist))
        } finally { f.close() }
    }

    @Test fun identitySwitchMidImportSupersedesWithoutLateFeedback() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            f.prompt { it.importPrompt is ImportPrompt.Ask }
            val gate = f.gate()
            f.beforeReply = { method, path -> if (method == HttpMethod.Post && path == watchlist) gate.await() }
            f.owner.importLibrary()
            while (f.count(watchlist) == 0) yield()
            assertEquals(ImportPrompt.Running, f.owner.state.value.importPrompt)
            f.sessionBody = WebAccountFixture.SIGNED_B
            f.refresh()
            gate.complete(Unit)
            val other = f.prompt { (it.importPrompt as? ImportPrompt.Ask)?.accountName == "Other viewer" }
            assertNull(other.importNotice)
            assertFalse(other.importBusy)
            assertEquals(1, f.count(watchlist))
        } finally { f.close() }
    }

    @Test fun storageFailureIsReportedAndRetryRunsOnlyTheFailedOperation() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            f.prompt { it.importPrompt is ImportPrompt.Ask }
            f.failOfferWrites = true
            f.owner.keepSeparate()
            f.prompt { it.importPrompt == ImportPrompt.Failed }
            assertNotNull(f.runtime.storageFailure.value)
            assertFalse(f.owner.state.value.importBusy)
            f.failOfferWrites = false
            f.owner.retryImport()
            f.prompt { it.importPrompt == null }
            assertEquals(ImportOfferRecord("fixture-account-a", imported = false, complete = true), f.offers["fixture-account-a"])
            assertEquals(0, f.count(watchlist))
        } finally { f.close() }
    }

    @Test fun playerDefersPromptAndStartImport() = runTest {
        val f = WebAccountFixture()
        try {
            f.start()
            f.prompt { it.importPrompt is ImportPrompt.Ask }
            f.owner.setPlayerActive(true)
            f.prompt { it.importPrompt == null }
            f.owner.startImport(WebAccountFixture.A); f.owner.importLibrary()
            assertNull(f.owner.state.value.importNotice)
            f.owner.setPlayerActive(false)
            f.prompt { it.importPrompt is ImportPrompt.Ask }
            assertEquals(0, f.count(watchlist))
        } finally { f.close() }
    }

    @Test fun reentrantCloseDuringImportPublicationPreventsImport() = runTest { coroutineScope {
        val f = WebAccountFixture()
        try {
            f.start()
            f.prompt { it.importPrompt is ImportPrompt.Ask }
            var revoked = false
            val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                f.owner.state.first { it.importBusy }
                f.owner.close()
                revoked = true
            }
            f.owner.importLibrary()
            assertTrue(revoked, "Observer must revoke within importBusy publication")
            assertEquals(0, f.count(watchlist))
            assertNull(f.offers["fixture-account-a"])
            observer.join()
        } finally { f.close() }
    } }

    @Test fun queuedStartImportRechecksAuthorityBeforeOffer() = runTest {
        val ownerScheduler = TestCoroutineScheduler()
        val f = WebAccountFixture(StandardTestDispatcher(ownerScheduler))
        suspend fun drive(match: (WebAccountState) -> Boolean): WebAccountState {
            while (!match(f.owner.state.value)) { ownerScheduler.runCurrent(); yield() }
            return f.owner.state.value
        }
        try {
            f.start()
            drive { it.importPrompt is ImportPrompt.Ask }
            f.owner.later()
            drive { it.importPrompt == null }
            f.owner.startImport(WebAccountFixture.A) // Queued on the owner dispatcher.
            f.sessionBody = WebAccountFixture.SIGNED_B
            f.refresh()
            ownerScheduler.runCurrent()
            val state = drive { (it.importPrompt as? ImportPrompt.Ask)?.accountName == "Other viewer" }
            assertNull(state.importNotice)
            assertNull(f.offers["fixture-account-a"])
        } finally { f.close() }
    }
}
