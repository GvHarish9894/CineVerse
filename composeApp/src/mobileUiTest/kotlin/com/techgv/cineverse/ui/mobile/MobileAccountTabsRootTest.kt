package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.ui.adaptive.ProvideWindowClass
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.shell.MobileShell
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.theme.CineVerseTheme
import kotlin.test.*

/** Library and Profile mounted by the real [MobileRoot] over the real account owner/coordinator fixture. */
@OptIn(ExperimentalTestApi::class)
class MobileAccountTabsRootTest {
    private val ana = AuthSession(AccountObjectId("ana"), "Ana", 1)

    @Test fun libraryTabMountsRealGuestContentAndCardOpensDetailInItsStack() = runComposeUiTest {
        setContent { AccountTabsRoot(configure = { it.guestTitle(101); it.auth.emissions.tryEmit(null) }) }
        tab("Library").performClick()
        onNodeWithTag("mobile-library").assertIsDisplayed()
        onNodeWithText("Saved on this device · Guest").assertIsDisplayed()
        onNodeWithText("Internal placeholder · This tab is not implemented in M1.").assertDoesNotExist()
        onNodeWithText("Guest 101").performClick()
        onNodeWithText("Detail title 101").assertIsDisplayed()
        tab("Home").performClick()
        tab("Library").performClick()
        onNodeWithText("Detail title 101").assertIsDisplayed()
    }

    @Test fun libraryEmptyExploreSelectsExploreAndGuestSignInReachesTheAccountOwner() = runComposeUiTest {
        lateinit var state: AccountTabsState
        setContent { AccountTabsRoot(configure = { it.auth.emissions.tryEmit(null) }) { state = it } }
        tab("Library").performClick()
        // Scroll the empty-state item to the top; scrolling only until visible can leave the button under the floating tab bar.
        onNodeWithTag("mobile-library").performScrollToKey("browse")
        onNodeWithText("Explore movies").performClick()
        onNodeWithText("Movies · Popular").assertIsDisplayed()
        runOnIdle { assertEquals(0, state.fixture.codes) }
        tab("Library").performClick()
        onNodeWithTag("mobile-library").performScrollToNode(hasText("Sign in with TMDB"))
        onNode(hasText("Sign in with TMDB") and hasClickAction()).performClick()
        waitUntil { state.dialog == MobileAccountDialog.Approval(MobileApprovalPhase.Waiting) }
        runOnIdle {
            assertEquals(1, state.fixture.codes)
            assertEquals(1, state.fixture.opener.urls.size)
        }
    }

    @Test fun staleLibraryCallbacksAfterIdentityChangeDoNothing() = runComposeUiTest {
        lateinit var state: AccountTabsState
        setContent { AccountTabsRoot(configure = { it.guestTitle(101); it.auth.emissions.tryEmit(null) }) { state = it } }
        tab("Library").performClick()
        val signIn = onNode(hasText("Sign in with TMDB") and hasClickAction()).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val card = onNode(hasText("Guest 101") and hasClickAction()).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        runOnIdle { assertTrue(state.fixture.auth.emissions.tryEmit(ana)) }
        waitUntil { state.fixture.graph.identity.value == MobileIdentity(ana) }
        onNodeWithText("TMDB account · Ana").assertExists()
        runOnIdle {
            signIn()
            card()
        }
        waitForIdle()
        runOnIdle {
            assertEquals(0, state.fixture.codes, "A guest-rendered Sign in must not begin approval for a signed-in identity")
            assertTrue(state.fixture.opener.urls.isEmpty())
            assertFalse(state.dialog is MobileAccountDialog.Approval)
        }
        onNodeWithText("Detail title 101").assertDoesNotExist()
        onNodeWithTag("mobile-library").assertExists()
    }

    @Test fun libraryRootReselectScrollsToTop() = runComposeUiTest {
        setContent { AccountTabsRoot(configure = { f -> (101..130).forEach(f::guestTitle); f.auth.emissions.tryEmit(null) }) }
        tab("Library").performClick()
        val grid = onNodeWithTag("mobile-library")
        grid.performScrollToNode(hasText("No tv shows in watchlist."))
        assertTrue(grid.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        tab("Library").performClick()
        waitUntil { grid.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() == 0f }
        onNodeWithText("Saved on this device · Guest").assertIsDisplayed()
        onNodeWithTag("mobile-library").assertExists()
    }

    @Test fun profileTabGuestSignInReachesTheAccountOwner() = runComposeUiTest {
        lateinit var state: AccountTabsState
        setContent { AccountTabsRoot(configure = { it.auth.emissions.tryEmit(null) }) { state = it } }
        tab("Profile").performClick()
        onNodeWithTag("mobile-profile").assertIsDisplayed()
        onNodeWithText("Guest").assertIsDisplayed()
        onNodeWithText("Sign in").performClick()
        waitUntil { state.dialog == MobileAccountDialog.Approval(MobileApprovalPhase.Waiting) }
        runOnIdle { assertEquals(1, state.fixture.codes) }
    }

    @Test fun profileSignOutCapturesTheExactRenderedSessionAndStaleSignOutDoesNothing() = runComposeUiTest {
        lateinit var state: AccountTabsState
        setContent { AccountTabsRoot(configure = { it.auth.emissions.tryEmit(ana) }) { state = it } }
        tab("Profile").performClick()
        onNodeWithText("TMDB account connected").assertIsDisplayed()
        onNodeWithText("Sign out").performClick()
        waitUntil { state.dialog is MobileAccountDialog.SignOut }
        runOnIdle {
            val dialog = state.dialog as MobileAccountDialog.SignOut
            assertSame(ana, dialog.session)
            assertEquals(MobileSignOutPhase.Confirm, dialog.phase)
            assertEquals(0, state.fixture.auth.signOuts, "Sign-out only opens the confirmation")
        }
        onNodeWithText("Cancel").performClick()
        waitUntil { state.dialog == null }

        val stale = onNode(hasText("Sign out") and hasClickAction()).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val replaced = ana.copy(sessionGeneration = 2)
        runOnIdle { assertTrue(state.fixture.auth.emissions.tryEmit(replaced)) }
        waitUntil { state.fixture.graph.identity.value == MobileIdentity(replaced) }
        waitForIdle()
        runOnIdle { stale() }
        waitForIdle()
        runOnIdle {
            assertNull(state.dialog, "A sign-out rendered for the replaced session must not open a confirmation")
            assertEquals(0, state.fixture.auth.signOuts)
        }
    }
}

/** The tab itself: Library/Profile screens also render their own titles. */
private fun SemanticsNodeInteractionsProvider.tab(label: String) =
    onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

private class AccountTabsState(val fixture: MobileAccountFixture) {
    var dialog: MobileAccountDialog? = null
}

@Composable
private fun AccountTabsRoot(configure: (MobileAccountFixture) -> Unit = {}, onReady: (AccountTabsState) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val state = remember { AccountTabsState(MobileAccountFixture(scope).also(configure)) }
    val owner = remember { AccountTabsOwner() }
    val model = remember { MobileHomeViewModel(state.fixture.graph, SavedStateHandle(), scope) }
    SideEffect { onReady(state) }
    LaunchedEffect(Unit) { state.fixture.graph.account.dialog.collect { state.dialog = it } }
    DisposableEffect(Unit) { onDispose { state.fixture.graph.close(); owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner) {
        CineVerseTheme(ShellStyle.Mobile, AppearanceSettings(haptics = false, reduceMotion = true)) {
            ProvideWindowClass { MobileShell { MobileRoot(state.fixture.graph, model, RecordingMobileExternalActions()) } }
        }
    }
}

private class AccountTabsOwner : LifecycleOwner, ViewModelStoreOwner {
    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    override val viewModelStore = ViewModelStore()
}
