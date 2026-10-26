@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
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
import com.techgv.cineverse.ui.adaptive.*
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import io.ktor.http.HttpStatusCode
import kotlin.test.*

class WebApprovalDialogTest {
    @Test fun prepareAndContinueAreSeparateClicksAndNullStyleOpenerHasNoSuccessClaim() = runComposeUiTest {
        val f = WebApprovalFixture()
        try {
            f.runtime.start()
            waitUntil { f.runtime.session.value is WebSessionState.Ready }
            f.owner.show()
            setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
                WebApprovalDialog(f.owner, remember { FocusRequester() })
            }
            onNodeWithTag("web-approval-content").performScrollToNode(hasText("Prepare sign-in"))
            onNodeWithText("Prepare sign-in").performClick()
            waitUntil { f.owner.state.value.canOpen }
            assertTrue(f.opened.isEmpty())
            onNodeWithTag("web-approval-content").performScrollToNode(hasText("Continue to TMDB"))
            onNodeWithText("Continue to TMDB").performClick()
            assertEquals(1, f.opened.size)
            onNodeWithText("An open was requested.", substring = true).assertExists()
            onNodeWithTag("web-approval-content").performScrollToNode(hasText("Open TMDB again"))
            onNodeWithText("Open TMDB again").performClick()
            assertSame(f.opened[0], f.opened[1])
            assertEquals(1, f.count("/api/auth/start"))
            onNodeWithText("request_token", substring = true).assertDoesNotExist()
            onNodeWithText("server-fixture", substring = true).assertDoesNotExist()
            onNodeWithText("Signed in", substring = true).assertDoesNotExist()
        } finally { f.dispose() }
    }

    @Test fun keyboardTrapKeepsSixTabCyclesInsideCvDialog() = runComposeUiTest {
        val f = WebApprovalFixture()
        try {
            f.runtime.start()
            waitUntil { f.runtime.session.value is WebSessionState.Ready }
            setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
                val trigger = remember { FocusRequester() }
                val inputMode = LocalInputModeManager.current
                LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
                Column {
                    PillButton("Before approval", {})
                    PillButton("Sign in trigger", f.owner::show, Modifier.focusRequester(trigger))
                    PillButton("After approval", {})
                }
                WebApprovalDialog(f.owner, trigger)
            }
            onNodeWithText("Sign in trigger").requestFocus()
            onNodeWithText("Sign in trigger").performKeyInput { pressKey(Key.Enter) }
            onNodeWithContentDescription("Close").assertIsFocused()
            val focusedInDialog = isFocused() and hasAnyAncestor(isDialog())
            repeat(6) {
                onNode(focusedInDialog).performKeyInput { pressKey(Key.Tab) }
                onNode(focusedInDialog).assertExists()
                onNodeWithText("Before approval").assertIsNotFocused()
                onNodeWithText("After approval").assertIsNotFocused()
            }
        } finally { f.dispose() }
    }

    @Test fun escapeBeforeStartClosesLocallyAndReturnsFocus() = runComposeUiTest {
        val f = WebApprovalFixture()
        try {
            f.runtime.start()
            waitUntil { f.runtime.session.value is WebSessionState.Ready }
            setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
                val trigger = remember { FocusRequester() }
                val inputMode = LocalInputModeManager.current
                LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
                PillButton("Sign in trigger", f.owner::show, Modifier.focusRequester(trigger))
                WebApprovalDialog(f.owner, trigger)
            }
            onNodeWithText("Sign in trigger").requestFocus()
            onNodeWithText("Sign in trigger").performKeyInput { pressKey(Key.Enter) }
            onNodeWithContentDescription("Close").assertIsFocused()
            onNodeWithContentDescription("Close").performKeyInput { pressKey(Key.Escape) }
            onNodeWithText("Sign in trigger").assertIsFocused()
            assertEquals(0, f.count("/api/auth/cancel"))
        } finally { f.dispose() }
    }

    @Test fun escapeAfterStartExplicitlyCancelsAndReturnsFocus() = runComposeUiTest {
        val f = WebApprovalFixture()
        try {
            f.runtime.start()
            waitUntil { f.runtime.session.value is WebSessionState.Ready }
            setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
                val trigger = remember { FocusRequester() }
                val inputMode = LocalInputModeManager.current
                LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
                PillButton("Sign in trigger", f.owner::show, Modifier.focusRequester(trigger))
                WebApprovalDialog(f.owner, trigger)
            }
            onNodeWithText("Sign in trigger").performClick()
            runOnIdle { f.owner.startApproval() }
            waitUntil { f.owner.state.value.canOpen }
            onNodeWithContentDescription("Close").requestFocus()
            onNodeWithContentDescription("Close").performKeyInput { pressKey(Key.Escape) }
            waitUntil { f.owner.state.value.phase == WebApprovalPhase.Closed }
            onNodeWithTag("web-approval-dialog").assertDoesNotExist()
            onNodeWithText("Sign in trigger").assertIsFocused()
            assertEquals(1, f.count("/api/auth/cancel"))
        } finally { f.dispose() }
    }

    @Test fun unknownAndFailedVerificationOnlyExposeReadRecoveryNotGuestActions() = runComposeUiTest {
        val f = WebApprovalFixture()
        try {
            f.owner.show()
            setThemedContent(ShellStyle.Web) { WebApprovalDialog(f.owner, remember { FocusRequester() }) }
            onNodeWithText("Browser identity is being verified.").assertExists()
            onNodeWithText("Prepare sign-in").assertDoesNotExist()
            runOnIdle { f.sessionStatus = HttpStatusCode.ServiceUnavailable; f.runtime.start() }
            waitUntil { f.owner.state.value.session is WebSessionState.Failed }
            onNodeWithText("Browser identity is unknown.", substring = true).assertExists()
            onNodeWithText("Prepare sign-in").assertDoesNotExist()
            runOnIdle { f.sessionStatus = HttpStatusCode.OK }
            onNodeWithTag("web-approval-content").performScrollToNode(hasText("Check session"))
            onNodeWithText("Check session").performClick()
            waitUntil { f.owner.state.value.canStart }
            onNodeWithText("Prepare sign-in").assertExists()
            assertEquals(0, f.count("/api/auth/start"))
        } finally { f.dispose() }
    }

    @Test fun cancellingAndUncertainCopyRemainTruthfulAndReadDoesNotReplayCancel() = runComposeUiTest {
        val f = WebApprovalFixture()
        try {
            f.runtime.start()
            waitUntil { f.runtime.session.value is WebSessionState.Ready }
            f.owner.show(); f.owner.startApproval()
            waitUntil { f.owner.state.value.canOpen }
            val gate = f.gate()
            f.cancelStatus = HttpStatusCode.ServiceUnavailable
            f.beforeReply = { if (it == "/api/auth/cancel") gate.await() }
            setThemedContent(ShellStyle.Web) { WebApprovalDialog(f.owner, remember { FocusRequester() }) }
            onNodeWithTag("web-approval-content").performScrollToNode(hasText("Not now"))
            onNodeWithText("Not now").performClick()
            onNodeWithText("Cancelling browser sign-in requests.", substring = true).assertExists()
            onNodeWithText("Not now").assertIsNotEnabled()
            onNodeWithText("Check session").assertIsNotEnabled()
            runOnIdle { f.owner.dismiss(); gate.complete(Unit) }
            waitUntil { f.owner.state.value.phase == WebApprovalPhase.Uncertain && f.owner.state.value.canCheck }
            onNodeWithText("Cancellation could not be confirmed.", substring = true).assertExists()
            onNodeWithTag("web-approval-content").performScrollToNode(hasText("Check session"))
            onNodeWithText("Check session").performClick()
            waitUntil { f.owner.state.value.phase == WebApprovalPhase.Uncertain && f.owner.state.value.canCheck }
            assertEquals(1, f.count("/api/auth/cancel"))
            onNodeWithText("Continue to TMDB").assertDoesNotExist()
            onNodeWithText("Prepare sign-in").assertDoesNotExist()
        } finally { f.dispose() }
    }

    @Test fun rootOwnedDialogSurvivesRouteCompositionAndClosesOnlyForVerifiedIdentity() = runComposeUiTest {
        val f = WebApprovalFixture()
        var route by mutableStateOf("Home")
        try {
            f.runtime.start()
            waitUntil { f.runtime.session.value is WebSessionState.Ready }
            f.owner.show(); f.owner.startApproval()
            waitUntil { f.owner.state.value.canOpen }
            setThemedContent(ShellStyle.Web) {
                key(route) { Text("Route: $route") }
                WebApprovalDialog(f.owner, remember { FocusRequester() })
            }
            runOnIdle { route = "Settings" }
            onNodeWithTag("web-approval-dialog").assertExists()
            assertEquals(WebApprovalPhase.Waiting, f.owner.state.value.phase)
            assertEquals(1, f.count("/api/auth/start"))
            runOnIdle { f.owner.approvalReturned() } // A false callback hint while still guest.
            waitUntil { f.owner.state.value.canOpen }
            onNodeWithTag("web-approval-dialog").assertExists()
            runOnIdle { f.sessionBody = WebApprovalFixture.SIGNED; f.owner.approvalReturned() }
            waitUntil { f.owner.state.value.phase == WebApprovalPhase.Closed }
            onNodeWithTag("web-approval-dialog").assertDoesNotExist()
            assertEquals(0, f.count("/api/auth/cancel"))
        } finally { f.dispose() }
    }

    @Test fun phoneAndDesktopDialogKeepInstructionsAndActionsInScrollableContent() = runComposeUiTest {
        val f = WebApprovalFixture()
        var width by mutableStateOf(WindowClass.Phone)
        try {
            f.runtime.start()
            waitUntil { f.runtime.session.value is WebSessionState.Ready }
            f.owner.show()
            setThemedContent(ShellStyle.Web) {
                CompositionLocalProvider(LocalWindowClass provides width) {
                    WebApprovalDialog(f.owner, remember { FocusRequester() })
                }
            }
            onNodeWithText("1. Prepare a sign-in request.", substring = true).assertExists()
            onNodeWithTag("web-approval-content").performScrollToNode(hasText("Not now"))
            onNodeWithText("Not now").assertIsDisplayed()
            runOnIdle { width = WindowClass.Desktop }
            onNodeWithTag("web-approval-content").performScrollToNode(hasText("Prepare sign-in"))
            onNodeWithText("Prepare sign-in").assertIsDisplayed()
            onNodeWithContentDescription("Close").performClick()
            onNodeWithTag("web-approval-dialog").assertDoesNotExist()
            assertEquals(0, f.count("/api/auth/cancel"))
        } finally { f.dispose() }
    }
}
