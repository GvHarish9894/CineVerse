@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.CvDialog
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import kotlin.test.Test
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WebModalFocusTest {
    @Test fun closeReturnsToActualTriggerRatherThanHeaderFallback() = runComposeUiTest {
        val fallback = FocusRequester()
        val returns = WebModalFocus(fallback)
        var open by mutableStateOf(false)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            CompositionLocalProvider(LocalWebModalFocus provides returns) {
                Column {
                    PillButton("Header fallback", {}, Modifier.focusRequester(fallback))
                    val trigger = rememberWebModalTrigger(WebModalKind.Account)
                    PillButton("Actual account trigger", { trigger.open { open = true } }, trigger.modifier)
                }
                if (open) CvDialog({ open = false }, "Account", onRestoreFocus = returns.restorer(WebModalKind.Account)) {}
            }
        }
        onNodeWithText("Actual account trigger").performClick()
        onNodeWithContentDescription("Close").performClick()
        yieldToWebBrowser()
        onNodeWithText("Actual account trigger").assertIsFocused()
        onNodeWithText("Header fallback").assertIsNotFocused()
    }

    @Test fun disabledTriggerUsesFallbackAfterDialogCloses() = runComposeUiTest {
        val fallback = FocusRequester()
        val returns = WebModalFocus(fallback)
        var open by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        lateinit var rateTrigger: WebModalTrigger
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            CompositionLocalProvider(LocalWebModalFocus provides returns) {
                Column {
                    PillButton("Header fallback", {}, Modifier.focusRequester(fallback))
                    val trigger = rememberWebModalTrigger(WebModalKind.Rate).also { rateTrigger = it }
                    PillButton("Rate trigger", { trigger.open { open = true } }, trigger.modifier, enabled = enabled)
                }
                if (open) CvDialog({ open = false }, "Rate", onRestoreFocus = returns.restorer(WebModalKind.Rate)) {}
            }
        }
        onNodeWithText("Rate trigger").performClick()
        runOnIdle { enabled = false }
        onNodeWithText("Rate trigger").assertIsNotEnabled()
        onNodeWithContentDescription("Close").performClick()
        yieldToWebBrowser()
        onNodeWithText("Header fallback").assertIsFocused()
        // A disabled clickable emits disabled() and drops the Focused key, so assertIsNotFocused can never hold;
        // assert the actual focus state the restorer checks, and that semantics never reports Focused=true.
        runOnIdle { assertFalse(rateTrigger.focused) }
        onNodeWithText("Rate trigger").assert(SemanticsMatcher("Focused != true") { it.config.getOrNull(SemanticsProperties.Focused) != true })
    }

    @Test fun removedTriggerUsesFallbackAndOtherDialogCaptureCannotRetargetReturn() = runComposeUiTest {
        val fallback = FocusRequester()
        val returns = WebModalFocus(fallback)
        var open by mutableStateOf(false)
        var showTrigger by mutableStateOf(true)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            CompositionLocalProvider(LocalWebModalFocus provides returns) {
                Column {
                    PillButton("Header fallback", {}, Modifier.focusRequester(fallback))
                    if (showTrigger) {
                        val trigger = rememberWebModalTrigger(WebModalKind.Trailer)
                        PillButton("Trailer trigger", { trigger.open { open = true } }, trigger.modifier)
                    }
                }
                if (open) CvDialog({ open = false }, "Trailer", onRestoreFocus = returns.restorer(WebModalKind.Trailer)) {}
            }
        }
        onNodeWithText("Trailer trigger").performClick()
        runOnIdle { showTrigger = false }
        onNodeWithContentDescription("Close").performClick()
        onNodeWithText("Header fallback").assertIsFocused()
    }

    @Test fun callbackRecompositionDoesNotRestoreUntilDialogLeaves() = runComposeUiTest {
        var open by mutableStateOf(true)
        var tick by mutableStateOf(0)
        val returned = mutableListOf<Int>()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            val revision = tick
            if (open) CvDialog({ open = false }, "Dialog $tick", onRestoreFocus = { returned += revision }) {}
        }
        runOnIdle { tick++ }
        runOnIdle { assertEquals(emptyList(), returned) }
        onNodeWithContentDescription("Close").performClick()
        runOnIdle { assertEquals(listOf(1), returned) }
    }

    @Test fun removingFocusedAsyncActionKeepsKeyboardFocusInsideTheSameModal() = runComposeUiTest {
        var action by mutableStateOf(true)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            CvDialog({}, "Async modal") {
                if (action) PillButton("Temporary action", { action = false })
            }
        }
        onNodeWithContentDescription("Close").performKeyInput { pressKey(Key.Tab) }
        onNodeWithText("Temporary action").assertIsFocused().performClick()
        onNodeWithContentDescription("Close").assertIsFocused()
    }

}
