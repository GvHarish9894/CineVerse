@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.CvDialog
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import kotlin.test.Test
import kotlin.test.assertEquals

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
        onNodeWithText("Actual account trigger").assertIsFocused()
        onNodeWithText("Header fallback").assertIsNotFocused()
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
            if (open) CvDialog({ open = false }, "Dialog $tick", onRestoreFocus = { returned += tick }) {}
        }
        runOnIdle { tick++ }
        runOnIdle { assertEquals(emptyList(), returned) }
        onNodeWithContentDescription("Close").performClick()
        runOnIdle { assertEquals(listOf(1), returned) }
    }
}
