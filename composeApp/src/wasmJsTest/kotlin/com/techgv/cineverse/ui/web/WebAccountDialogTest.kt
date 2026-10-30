@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.presentation.ImportPrompt
import com.techgv.cineverse.ui.adaptive.*
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import io.ktor.http.HttpStatusCode
import kotlin.test.*

class WebAccountDialogTest {
    @Test fun signOutConfirmShowsOnlyDisplayNameAndEscapeReturnsFocusWithoutLogout() = runComposeUiTest {
        val f = WebAccountFixture()
        try {
            f.runtime.start()
            waitUntil { f.runtime.session.value is WebSessionState.Ready }
            setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
                val trigger = remember { FocusRequester() }
                val inputMode = LocalInputModeManager.current
                LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
                PillButton("Sign out trigger", { f.owner.startSignOut(WebAccountFixture.A) }, Modifier.focusRequester(trigger))
                WebSignOutDialog(f.owner, trigger)
            }
            onNodeWithText("Sign out trigger").requestFocus()
            onNodeWithText("Sign out trigger").performKeyInput { pressKey(Key.Enter) }
            onNodeWithContentDescription("Close").assertIsFocused()
            onNodeWithText("Fixture viewer", substring = true).assertExists()
            onNodeWithText("fixture-account", substring = true).assertDoesNotExist()
            onNodeWithText("9223372036854775808", substring = true).assertDoesNotExist()
            onNodeWithContentDescription("Close").performKeyInput { pressKey(Key.Escape) }
            waitUntil { f.owner.state.value.signOut == WebSignOutPhase.Closed }
            onNodeWithTag("web-sign-out-dialog").assertDoesNotExist()
            onNodeWithText("Sign out trigger").assertIsFocused()
            assertEquals(0, f.count("/api/auth/logout"))
        } finally { f.dispose() }
    }

    @Test fun signOutKeyboardTrapKeepsSixTabCyclesInsideCvDialog() = runComposeUiTest {
        val f = WebAccountFixture()
        try {
            f.runtime.start()
            waitUntil { f.runtime.session.value is WebSessionState.Ready }
            setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
                val trigger = remember { FocusRequester() }
                val inputMode = LocalInputModeManager.current
                LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
                Column {
                    PillButton("Before account", {})
                    PillButton("Sign out trigger", { f.owner.startSignOut(WebAccountFixture.A) }, Modifier.focusRequester(trigger))
                    PillButton("After account", {})
                }
                WebSignOutDialog(f.owner, trigger)
            }
            onNodeWithText("Sign out trigger").requestFocus()
            onNodeWithText("Sign out trigger").performKeyInput { pressKey(Key.Enter) }
            onNodeWithContentDescription("Close").assertIsFocused()
            val focusedInDialog = isFocused() and hasAnyAncestor(isDialog())
            repeat(6) {
                onNode(focusedInDialog).performKeyInput { pressKey(Key.Tab) }
                onNode(focusedInDialog).assertExists()
                onNodeWithText("Before account").assertIsNotFocused()
                onNodeWithText("After account").assertIsNotFocused()
            }
            assertEquals(0, f.count("/api/auth/logout"))
        } finally { f.dispose() }
    }

    @Test fun signOutButtonsDisabledWhilePendingThenReadConfirmedCopy() = runComposeUiTest {
        val f = WebAccountFixture()
        try {
            f.runtime.start()
            waitUntil { f.runtime.session.value is WebSessionState.Ready }
            val gate = f.gate()
            f.beforeReply = { _, path -> if (path == "/api/auth/logout") gate.await() }
            f.owner.startSignOut(WebAccountFixture.A)
            setThemedContent(ShellStyle.Web) { WebSignOutDialog(f.owner, remember { FocusRequester() }) }
            onNodeWithTag("web-sign-out-content").performScrollToNode(hasText("Sign out"))
            onNodeWithText("Sign out").performClick()
            waitUntil { f.count("/api/auth/logout") == 1 }
            onNodeWithText("Signing out", substring = true).assertExists()
            onNodeWithText("Sign out").assertIsNotEnabled()
            onNodeWithText("Close").assertIsNotEnabled()
            runOnIdle { gate.complete(Unit) }
            waitUntil { f.owner.state.value.signOut == WebSignOutPhase.SignedOut }
            onNodeWithText("verified as signed out", substring = true).assertExists()
            assertEquals(1, f.count("/api/auth/logout"))
        } finally { f.dispose() }
    }

    @Test fun unknownSignOutOffersOnlyReadRecovery() = runComposeUiTest {
        val f = WebAccountFixture()
        try {
            f.runtime.start()
            waitUntil { f.runtime.session.value is WebSessionState.Ready }
            f.logoutStatus = HttpStatusCode.ServiceUnavailable
            f.sessionStatus = HttpStatusCode.ServiceUnavailable
            f.owner.startSignOut(WebAccountFixture.A)
            setThemedContent(ShellStyle.Web) { WebSignOutDialog(f.owner, remember { FocusRequester() }) }
            onNodeWithTag("web-sign-out-content").performScrollToNode(hasText("Sign out"))
            onNodeWithText("Sign out").performClick()
            waitUntil { f.owner.state.value.signOut == WebSignOutPhase.Unknown }
            onNodeWithText("never repeated automatically", substring = true).assertExists()
            onNodeWithText("Sign out").assertDoesNotExist()
            runOnIdle { f.sessionStatus = HttpStatusCode.OK }
            onNodeWithTag("web-sign-out-content").performScrollToNode(hasText("Check session"))
            onNodeWithText("Check session").performClick()
            waitUntil { f.owner.state.value.signOut == WebSignOutPhase.SignedOut }
            assertEquals(1, f.count("/api/auth/logout"))
        } finally { f.dispose() }
    }

    @Test fun importAskOnPhoneAndDesktopAndEscapeIsLaterNotKeepSeparate() = runComposeUiTest {
        val f = WebAccountFixture()
        var width by mutableStateOf(WindowClass.Phone)
        try {
            f.runtime.start()
            waitUntil { f.owner.state.value.importPrompt is ImportPrompt.Ask }
            setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
                CompositionLocalProvider(LocalWindowClass provides width) {
                    WebImportDialog(f.owner, remember { FocusRequester() })
                }
            }
            onNodeWithText("Fixture viewer", substring = true).assertExists()
            onNodeWithTag("web-import-content").performScrollToNode(hasText("Keep separate"))
            onNodeWithText("Keep separate").assertIsDisplayed()
            runOnIdle { width = WindowClass.Desktop }
            onNodeWithTag("web-import-content").performScrollToNode(hasText("Import"))
            onNodeWithText("Import").assertIsDisplayed()
            onNodeWithContentDescription("Close").requestFocus()
            onNodeWithContentDescription("Close").performKeyInput { pressKey(Key.Escape) }
            waitUntil { f.owner.state.value.importPrompt == null }
            onNodeWithTag("web-import-dialog").assertDoesNotExist()
            assertNull(f.offers["fixture-account-a"])
            assertEquals(0, f.count("/api/account/watchlist"))
        } finally { f.dispose() }
    }

    @Test fun unconfirmedImportShowsTitlesWarningAndExplicitDone() = runComposeUiTest {
        val f = WebAccountFixture()
        try {
            f.runtime.start()
            waitUntil { f.owner.state.value.importPrompt is ImportPrompt.Ask }
            f.writeStatus = HttpStatusCode.ServiceUnavailable
            setThemedContent(ShellStyle.Web) { WebImportDialog(f.owner, remember { FocusRequester() }) }
            onNodeWithTag("web-import-content").performScrollToNode(hasText("Import"))
            onNodeWithText("Import").performClick()
            waitUntil { f.owner.state.value.importPrompt is ImportPrompt.Unconfirmed }
            onNodeWithText("Couldn't confirm 1 item").assertExists()
            onNodeWithText("Fixture film", substring = true).assertExists()
            onNodeWithText("may re-add", substring = true).assertExists()
            onNodeWithTag("web-import-content").performScrollToNode(hasText("Done"))
            onNodeWithText("Done").performClick()
            waitUntil { f.owner.state.value.importPrompt == null }
            assertEquals(true, f.offers["fixture-account-a"]?.complete)
            assertEquals(1, f.count("/api/account/watchlist"))
        } finally { f.dispose() }
    }

    @Test fun signOutDialogSuppressesImportDialog() = runComposeUiTest {
        val f = WebAccountFixture()
        try {
            f.runtime.start()
            waitUntil { f.owner.state.value.importPrompt is ImportPrompt.Ask }
            f.owner.startSignOut(WebAccountFixture.A)
            setThemedContent(ShellStyle.Web) {
                WebSignOutDialog(f.owner, remember { FocusRequester() })
                WebImportDialog(f.owner, remember { FocusRequester() })
            }
            onNodeWithTag("web-sign-out-dialog").assertExists()
            onNodeWithTag("web-import-dialog").assertDoesNotExist()
            runOnIdle { f.owner.dismiss() }
            onNodeWithTag("web-import-dialog").assertExists()
        } finally { f.dispose() }
    }
}
