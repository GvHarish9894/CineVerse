package com.techgv.cineverse.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.CvBottomSheet
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.theme.AppearanceSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/** CvBottomSheet close paths: each reports exactly one dismissal under both motion settings. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class SheetDismissTest {
    private fun ComposeUiTest.openAndClose(reduceMotion: Boolean, escape: Boolean) {
        var dismissals = 0
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = reduceMotion)) {
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
            var open by remember { mutableStateOf(false) }
            val trigger = remember { FocusRequester() }
            PillButton("Open sheet", { open = true }, Modifier.focusRequester(trigger))
            if (open) {
                CvBottomSheet(onDismissRequest = { dismissals++; open = false }, restoreFocus = trigger) {
                    PillButton("Sheet action", {})
                }
            }
        }
        // Keyboard user opens the sheet from its trigger.
        onNodeWithText("Open sheet").requestFocus()
        onNodeWithText("Open sheet").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        onNodeWithText("Sheet action").assertExists()
        if (escape) {
            onNodeWithText("Sheet action").requestFocus()
            waitForIdle()
            onNodeWithText("Sheet action").assertIsFocused()
            onNodeWithText("Sheet action").performKeyInput { pressKey(Key.Escape) }
        } else {
            // Material's scrim.
            onNodeWithContentDescription("Close sheet").performClick()
        }
        waitForIdle()
        onNodeWithText("Sheet action").assertDoesNotExist()
        assertEquals(1, dismissals)
        onNodeWithText("Open sheet").assertIsFocused()
    }

    @Test fun reducedMotionSheetClosesOnEscapeAndRestoresFocus() = runComposeUiTest { openAndClose(reduceMotion = true, escape = true) }
    @Test fun normalMotionSheetClosesOnEscapeAndRestoresFocus() = runComposeUiTest { openAndClose(reduceMotion = false, escape = true) }
    @Test fun reducedMotionSheetClosesOnScrimAndRestoresFocus() = runComposeUiTest { openAndClose(reduceMotion = true, escape = false) }
    @Test fun normalMotionSheetClosesOnScrimAndRestoresFocus() = runComposeUiTest { openAndClose(reduceMotion = false, escape = false) }
}
