package com.techgv.cineverse.ui.mobile

import com.techgv.cineverse.data.auth.*
import com.techgv.cineverse.data.settings.AccountDefaults
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.AccountDto
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.presentation.ImportPrompt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MobileAccountOwnerTest {
    private val ana = AuthSession(AccountObjectId("ana"), "Ana", 1)
    private val hindiUs = AccountDto(1, language = "hi", region = "US")
    private val anaOffer = AccountDefaults("ana", "hi-IN", "US", false)

    private suspend fun MobileAccountFixture.dialog() = graph.account.dialog.first()

    private suspend fun TestScope.signInThroughApproval(f: MobileAccountFixture, outcome: suspend () -> SignInOutcome) {
        f.graph.account.onSignIn()
        runCurrent()
        f.auth.approval = outcome
        f.graph.account.checkAgain()
        runCurrent()
    }

    @Test fun duplicateSignInPressesStartOneApprovalOnlyForAKnownGuest() = runTest {
        val f = MobileAccountFixture(backgroundScope)
        runCurrent()
        f.graph.account.onSignIn() // identity unknown
        runCurrent()
        assertEquals(0, f.codes)
        f.auth.emissions.emit(null)
        runCurrent()
        f.graph.account.onSignIn()
        f.graph.account.onSignIn()
        runCurrent()
        f.graph.account.onSignIn()
        runCurrent()
        assertEquals(1, f.codes)
        assertEquals(1, f.opener.urls.size)
        assertEquals(MobileAccountDialog.Approval(MobileApprovalPhase.Waiting), f.dialog())
        f.auth.emissions.emit(ana)
        runCurrent()
        assertNull(f.dialog(), "An authoritative sign-in closes approval")
        f.graph.account.onSignIn()
        runCurrent()
        assertEquals(1, f.codes)
        f.graph.close()
    }

    @Test fun coldStartPendingAttemptPromptsAndManualCheckFinishesThroughAuthoritativeIdentity() = runTest {
        val pending = InMemoryPendingAttemptStore().apply { save(ApprovalAttempt.of(RequestCode("earlier"))) }
        val f = MobileAccountFixture(backgroundScope, pending)
        f.auth.emissions.emit(null)
        runCurrent()
        assertEquals(MobileAccountDialog.Approval(MobileApprovalPhase.Resume), f.dialog())
        assertEquals(0, f.codes)
        assertEquals(0, f.auth.waits, "A cold start only offers the check")
        f.auth.approval = { SignInOutcome.TimedOut }
        f.graph.account.checkAgain()
        runCurrent()
        assertEquals(MobileAccountDialog.Approval(MobileApprovalPhase.StillWaiting), f.dialog())
        f.auth.approval = { f.auth.emissions.emit(ana); SignInOutcome.SignedIn(ana, AccountDto(1)) }
        f.graph.account.checkAgain()
        runCurrent()
        assertEquals(MobileIdentity(ana), f.graph.identity.value)
        assertNull(f.dialog())
        assertEquals(2, f.auth.waits)
        assertEquals(0, f.codes)
        f.graph.close()
    }

    @Test fun browserCallbackCannotManufactureIdentityDefaultsOrImport() = runTest {
        val f = MobileAccountFixture(backgroundScope)
        f.guestTitle(7)
        f.auth.emissions.emit(null)
        runCurrent()
        f.graph.account.onSignIn()
        runCurrent()
        f.auth.approval = { SignInOutcome.SignedIn(ana, hindiUs) } // never committed to the session source
        f.opener.callback!!(approvalCallbackUri(f.pending.load()!!))
        runCurrent()
        assertEquals(1, f.auth.waits)
        assertEquals(MobileIdentity(null), f.graph.identity.value)
        assertTrue(f.settings.pendingAccountDefaults.first().isEmpty())
        assertTrue(f.imports.offerReads.isEmpty())
        assertNull(f.dialog())
        f.graph.close()
    }

    @Test fun staleSignedInOutcomeCannotOfferDefaults() = runTest {
        val f = MobileAccountFixture(backgroundScope)
        f.auth.emissions.emit(null)
        runCurrent()
        val replaced = ana.copy(sessionGeneration = 2)
        signInThroughApproval(f) { f.auth.emissions.emit(replaced); SignInOutcome.SignedIn(ana, hindiUs) }
        runCurrent()
        assertEquals(MobileIdentity(replaced), f.graph.identity.value)
        assertTrue(f.settings.pendingAccountDefaults.first().isEmpty())
        assertNull(f.dialog())
        f.graph.close()
    }

    @Test fun defaultsArePersistedBeforeDisplayAndApplyOrDismissRevalidateTheExactSession() = runTest {
        val f = MobileAccountFixture(backgroundScope)
        f.auth.emissions.emit(null)
        runCurrent()
        signInThroughApproval(f) { f.auth.emissions.emit(ana); SignInOutcome.SignedIn(ana, hindiUs) }
        assertEquals(mapOf("ana" to anaOffer), f.settings.pendingAccountDefaults.first())
        val rendered = f.dialog()
        assertEquals(MobileAccountDialog.Defaults(ana, anaOffer), rendered)
        rendered as MobileAccountDialog.Defaults
        val again = ana.copy(sessionGeneration = 2)
        f.auth.emissions.emit(again)
        runCurrent()
        f.graph.account.resolveDefaults(rendered, apply = true)
        runCurrent()
        assertEquals("en-US", f.settings.current().language, "A stale Apply writes nothing")
        val current = f.dialog() as MobileAccountDialog.Defaults
        assertEquals(again, current.session)
        f.graph.account.resolveDefaults(current, apply = true)
        runCurrent()
        assertEquals("hi-IN", f.settings.current().language)
        assertEquals("US", f.settings.current().region)
        assertNull(f.dialog())

        val bob = AuthSession(AccountObjectId("bob"), "Bob", 3)
        val bobOffer = AccountDefaults("bob", "es-ES", "GB", false)
        f.settings.offerAccountDefaults(bobOffer)
        f.auth.emissions.emit(bob)
        runCurrent()
        val bobDialog = f.dialog() as MobileAccountDialog.Defaults
        f.auth.emissions.emit(null)
        runCurrent()
        f.graph.account.resolveDefaults(bobDialog, apply = false)
        runCurrent()
        assertEquals(mapOf("bob" to bobOffer), f.settings.pendingAccountDefaults.first(), "A stale Dismiss resolves nothing")
        f.auth.emissions.emit(bob)
        runCurrent()
        f.graph.account.resolveDefaults(f.dialog() as MobileAccountDialog.Defaults, apply = false)
        runCurrent()
        assertTrue(f.settings.pendingAccountDefaults.first().isEmpty())
        assertEquals("hi-IN", f.settings.current().language, "Dismiss keeps current settings")
        f.graph.close()
    }

    @Test fun signOutConfirmationCapturesAndRevalidatesTheExactSession() = runTest {
        val f = MobileAccountFixture(backgroundScope)
        f.auth.emissions.emit(ana)
        runCurrent()
        f.graph.account.onSignOut(ana.copy(sessionGeneration = 9))
        assertNull(f.dialog())
        f.graph.account.onSignOut(ana)
        val stale = f.dialog()
        assertEquals(MobileAccountDialog.SignOut(ana, MobileSignOutPhase.Confirm), stale)
        val again = ana.copy(sessionGeneration = 2)
        f.auth.emissions.emit(again)
        runCurrent()
        f.graph.account.confirmSignOut(stale as MobileAccountDialog.SignOut)
        runCurrent()
        assertEquals(0, f.auth.signOuts)
        assertNull(f.dialog(), "The captured session no longer exists")
        f.graph.account.onSignOut(again)
        f.graph.account.confirmSignOut(f.dialog() as MobileAccountDialog.SignOut)
        runCurrent()
        assertEquals(1, f.auth.signOuts)
        assertEquals(MobileIdentity(null), f.graph.identity.value)
        assertNull(f.dialog())
        f.graph.close()
    }

    @Test fun storageAndNetworkFailuresAreReportedWithoutAutomaticReplay() = runTest {
        val f = MobileAccountFixture(backgroundScope)
        f.auth.emissions.emit(null)
        runCurrent()
        f.codeFailure = TmdbException(TmdbError.Network)
        f.graph.account.onSignIn()
        runCurrent()
        assertEquals(MobileAccountDialog.Approval(MobileApprovalPhase.StartFailed), f.dialog())
        assertEquals(1, f.codes)
        f.codeFailure = null
        f.graph.account.startAgain()
        runCurrent()
        assertEquals(2, f.codes)
        assertEquals(MobileAccountDialog.Approval(MobileApprovalPhase.Waiting), f.dialog())

        f.store.failWrites = true
        val edits = f.store.edits
        f.auth.approval = { f.auth.emissions.emit(ana); SignInOutcome.SignedIn(ana, hindiUs) }
        f.graph.account.checkAgain()
        runCurrent()
        assertEquals(edits + 1, f.store.edits)
        assertTrue(f.settings.pendingAccountDefaults.first().isEmpty())
        assertNull(f.dialog())
        f.store.failWrites = false

        f.auth.signOutFailure = StorageException("fake credential clear")
        f.graph.account.onSignOut(ana)
        f.graph.account.confirmSignOut(f.dialog() as MobileAccountDialog.SignOut)
        runCurrent()
        assertEquals(MobileAccountDialog.SignOut(ana, MobileSignOutPhase.Failed), f.dialog())
        assertEquals(1, f.auth.signOuts)
        assertEquals(edits + 1, f.store.edits)
        assertEquals(2, f.codes)
        f.graph.close()
    }

    @Test fun importPromptFollowsAuthoritativeIdentityDefersForTrailerAndToastsWhenImported() = runTest {
        val f = MobileAccountFixture(backgroundScope)
        val messages = mutableListOf<String>()
        backgroundScope.launch { f.graph.account.messages.collect { messages += it } }
        f.guestTitle(7)
        f.auth.emissions.emit(null)
        runCurrent()
        assertNull(f.dialog())
        f.auth.emissions.emit(ana)
        runCurrent()
        val stale = f.dialog()
        assertEquals(MobileAccountDialog.Import(ana, ImportPrompt.Ask("Ana", 1)), stale)
        f.graph.setPlayerActive(true)
        runCurrent()
        assertNull(f.dialog(), "Import waits while the Trailer is current")
        f.graph.setPlayerActive(false)
        runCurrent()
        val bob = AuthSession(AccountObjectId("bob"), "Bob", 2)
        f.auth.emissions.emit(bob)
        runCurrent()
        f.graph.account.importLibrary(stale as MobileAccountDialog.Import)
        runCurrent()
        assertTrue(f.account.writes.isEmpty(), "A stale prompt cannot import into the current account")
        val current = f.dialog() as MobileAccountDialog.Import
        assertEquals(bob, current.session)
        f.graph.account.importLibrary(current)
        runCurrent()
        assertEquals(listOf(bob to f.guest.rows.value.single().ref), f.account.writes)
        assertNull(f.dialog())
        assertEquals(listOf("Guest library imported"), messages)
        f.auth.emissions.emit(null)
        runCurrent()
        assertNull(f.dialog())
        assertEquals(listOf("ana", "bob"), f.imports.offerReads)
        f.graph.close()
    }

    @Test fun accountDialogPriorityIsSignOutThenApprovalThenDefaultsThenImport() = runTest {
        val f = MobileAccountFixture(backgroundScope)
        f.guestTitle(7)
        f.auth.emissions.emit(null)
        runCurrent()
        val seen = mutableListOf<MobileAccountDialog?>()
        backgroundScope.launch { f.graph.account.dialog.collect { seen += it } }
        signInThroughApproval(f) { f.auth.emissions.emit(ana); SignInOutcome.SignedIn(ana, hindiUs) }
        runCurrent()
        val defaults = f.dialog()
        assertEquals(MobileAccountDialog.Defaults(ana, anaOffer), defaults)
        assertTrue(seen.none { it is MobileAccountDialog.Import }, "Defaults precede import after sign-in: $seen")
        assertTrue(seen.filterNotNull().first() is MobileAccountDialog.Approval)
        f.graph.account.onSignOut(ana)
        val signOut = f.dialog()
        assertEquals(MobileAccountDialog.SignOut(ana, MobileSignOutPhase.Confirm), signOut)
        f.graph.account.dismissSignOut(signOut as MobileAccountDialog.SignOut)
        assertEquals(defaults, f.dialog())
        f.graph.account.resolveDefaults(defaults as MobileAccountDialog.Defaults, apply = false)
        runCurrent()
        assertEquals(MobileAccountDialog.Import(ana, ImportPrompt.Ask("Ana", 1)), f.dialog())
        f.graph.close()
    }
}
