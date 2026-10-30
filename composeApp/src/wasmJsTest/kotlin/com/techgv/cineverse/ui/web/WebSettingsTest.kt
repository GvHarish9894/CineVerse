package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import kotlinx.coroutines.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WebSettingsTest {
    private fun fixture() = WebSettingsFixture(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    @Test fun observedControlsExposeSelectedValuesAndRealDirectEditsAtPhoneWidth() = runComposeUiTest {
        val f = fixture()
        try {
            setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
                Box(Modifier.size(390.dp, 800.dp)) { WebSettings(f.owner, WebSessionState.Checking, null, {}) }
            }
            onNodeWithText("Ambient glow").assertIsOff().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
            waitUntil { f.owner.state.value.settings?.glow == true }
            onNodeWithText("Ambient glow").assertIsOn()
            onNodeWithText("Settings").assertExists()
            onNodeWithText("Haptic strength").assertExists()
            onNodeWithText("Saved preference · web taps use a fixed 10 ms").assertExists()
            onNodeWithTag("web-settings").performScrollToNode(hasTestTag("settings-language-ta-IN"))
            onNodeWithTag("settings-language-ta-IN").assertIsSelected().assert(hasText("Tamil · ta-IN"))
            // Latin chip labels only: the bundled fonts cannot draw the native scripts.
            onNodeWithTag("settings-language-hi-IN").assert(hasText("Hindi · hi-IN"))
            onNodeWithText("हिन्दी", substring = true).assertDoesNotExist()
            onNodeWithText("language=ta-IN").assertExists()
            onNodeWithTag("settings-language-es-ES").performScrollTo().performClick()
            waitUntil { f.owner.state.value.settings?.language == "es-ES" }
            onNodeWithTag("settings-language-es-ES").assertIsSelected()
            onNodeWithText("language=es-ES").assertExists()
            onNodeWithText("Content language · language=es-ES").assertDoesNotExist()
            onNodeWithTag("settings-region-US").performScrollTo().performClick()
            waitUntil { f.owner.state.value.settings?.region == "US" }
            onNodeWithTag("settings-region-US").assertIsSelected()
            onNodeWithText("Include adult titles").performScrollTo().assertIsOn().performClick()
            waitUntil { f.owner.state.value.settings?.includeAdult == false }
            onNodeWithText("Search · include_adult=false").assertExists()
            onNodeWithText("Autoplay trailers").assertDoesNotExist()
            onNodeWithText("Saved").assertDoesNotExist()
        } finally { f.close() }
    }

    @Test fun accountUsesAuthoritativeStatesAndActualCallbacksNotHolderHint() = runComposeUiTest {
        val f = fixture()
        var retries = 0
        try {
            setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
                val session by f.session.collectAsState()
                Box(Modifier.size(1200.dp, 900.dp)) { WebSettings(f.owner, session, null) { retries++ } }
            }
            onNodeWithTag("web-settings").performScrollToNode(hasText("Checking account. Account actions are unavailable."))
            onNodeWithText("Sign in").assertDoesNotExist()
            onNodeWithText("Not authoritative").assertDoesNotExist()
            runOnIdle { f.session.value = WebSessionState.Failed(TmdbError.Network) }
            onNodeWithText("Retry account check").performClick()
            assertEquals(1, retries)
            onNodeWithText("Sign in").assertDoesNotExist()
            runOnIdle { f.session.value = WebSessionState.Ready(null) }
            onNodeWithText("Guest · preferences are saved in this browser").assertExists()
            onNodeWithText("Sign in").performClick()
            assertEquals(1, f.signIns)
            val account = AuthSession(AccountObjectId("fixture-account"), "Actual account name", 14)
            runOnIdle { f.session.value = WebSessionState.Ready(account) }
            onNodeWithText("Signed in as Actual account name").assertExists()
            onNodeWithText("Sign out").performClick()
            onNodeWithText("Import guest library").performScrollTo().performClick()
            assertEquals(listOf(account), f.signOuts)
            assertEquals(listOf(account), f.imports)
            onNodeWithText("Sign in").assertDoesNotExist()
            assertEquals(0, f.store.writes)
        } finally { f.close() }
    }

    @Test fun keyboardFocusEnterAndTabReachHouseChoiceAndToggleControls() = runComposeUiTest {
        val f = fixture()
        try {
            setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
                Box(Modifier.size(1200.dp, 900.dp)) { WebSettings(f.owner, WebSessionState.Checking, null, {}) }
            }
            val glow = onNodeWithText("Ambient glow")
            glow.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            glow.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            waitUntil { f.owner.state.value.settings?.glow == true }
            glow.performKeyInput { pressKey(Key.Tab) }
            onNodeWithText("Reduce motion").assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
            waitUntil { f.owner.state.value.settings?.motion == false }
            onNodeWithTag("web-settings").performScrollToNode(hasTestTag("settings-language-hi-IN"))
            onNodeWithTag("settings-language-hi-IN").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onNodeWithTag("settings-language-hi-IN").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            waitUntil { f.owner.state.value.settings?.language == "hi-IN" }
            onNodeWithTag("settings-language-hi-IN").assertIsSelected()
        } finally { f.close() }
    }

    @Test fun adaptivePanelsStackAtPhoneWidthAndShareRowOnDesktop() = runComposeUiTest {
        val f = fixture()
        var wide by mutableStateOf(true)
        try {
            setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
                Box(Modifier.size(if (wide) 1200.dp else 390.dp, 1000.dp)) { WebSettings(f.owner, WebSessionState.Checking, null, {}) }
            }
            val lookWide = onNodeWithTag("settings-look").fetchSemanticsNode().boundsInRoot
            val contentWide = onNodeWithTag("settings-content").fetchSemanticsNode().boundsInRoot
            assertEquals(lookWide.top, contentWide.top)
            assertTrue(contentWide.left > lookWide.right)
            runOnIdle { wide = false }
            onNodeWithTag("web-settings").performScrollToNode(hasTestTag("settings-content"))
            val lookNarrow = onNodeWithTag("settings-look").fetchSemanticsNode().boundsInRoot
            val contentNarrow = onNodeWithTag("settings-content").fetchSemanticsNode().boundsInRoot
            assertEquals(lookNarrow.left, contentNarrow.left)
            assertTrue(contentNarrow.top > lookNarrow.top)
            onNodeWithTag("settings-language-ta-IN").performScrollTo().assertIsSelected()
        } finally { f.close() }
    }

    @Test fun initialTypedReadFailureIsUnknownAndExplicitReadRetryDoesNotReplayWrite() = runComposeUiTest {
        val f = fixture()
        try {
            // Fail an active observed reader, then use a fresh route owner to exercise initial failure.
            f.store.readFailure = BrowserStorageFailure.Corrupt
            f.store.signalRead()
            f.owner.close()
            val owner = f.owner()
            try {
                setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
                    WebSettings(owner, WebSessionState.Checking, null, {})
                }
                onNodeWithText("Preferences are unknown. Waiting for a successful read.").assertExists()
                onNodeWithText("Ambient glow").assertDoesNotExist()
                onNodeWithText("Sign in").assertDoesNotExist()
                runOnIdle { f.store.readFailure = null }
                onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("settings-read-recovery"))).performClick()
                onNodeWithText("Ambient glow").assertExists()
                assertEquals(1, f.upstreamRetries)
                runOnIdle { f.store.writeFailure = BrowserStorageFailure.Quota }
                onNodeWithText("Ambient glow").performClick()
                onNodeWithTag("web-settings").performScrollToNode(hasText("Preference write failed", ignoreCase = true))
                onNodeWithText("Saved").assertDoesNotExist()
                runOnIdle { f.store.writeFailure = null }
                onNodeWithText("Retry reads").performClick()
                assertEquals(1, f.store.writes)
                assertEquals(false, owner.state.value.settings?.glow)
                onNodeWithText("Dismiss write error").performClick()
            } finally { owner.close() }
        } finally { f.close() }
    }
}
