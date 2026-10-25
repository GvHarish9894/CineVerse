package com.techgv.cineverse.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.CvDialog
import com.techgv.cineverse.ui.components.PillButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class DialogFocusTest {
    private fun SemanticsNode.label(): String =
        config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()

    @Test
    fun tabStaysInsideEscClosesFocusReturnsToTrigger() = runComposeUiTest {
        setThemedContent(ShellStyle.Web) {
            // A hardware keyboard is in use (on Android this leaves touch mode so controls are focusable).
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
            var open by remember { mutableStateOf(false) }
            val trigger = remember { FocusRequester() }
            Column {
                PillButton("Before", {})
                PillButton("Open dialog", { open = true }, Modifier.focusRequester(trigger))
                PillButton("After", {})
            }
            if (open) {
                CvDialog(onDismissRequest = { open = false }, title = "Rate it", restoreFocus = trigger) {
                    PillButton("Remove", {})
                    PillButton("Submit", {})
                }
            }
        }
        // Keyboard user: focus the first control, Tab to the trigger, Enter opens the dialog.
        onNodeWithText("Before").requestFocus()
        onNodeWithText("Before").performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        onNodeWithText("Open dialog").assertIsFocused()
        onNodeWithText("Open dialog").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        onNodeWithContentDescription("Close").assertIsFocused()

        // Each window keeps its own focused node; the dialog's is the one inside the dialog root.
        val dialogFocus = isFocused() and hasAnyAncestor(isDialog())
        val inside = setOf("Close", "Remove", "Submit")
        val visited = mutableSetOf<String>()
        repeat(7) {
            onNode(dialogFocus).performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            val focused = onAllNodes(isFocused()).fetchSemanticsNodes().map { it.label() }
            val inDialog = onNode(dialogFocus).fetchSemanticsNode().label()
            assertTrue(inDialog in inside, "focus left the dialog: '$inDialog'")
            assertTrue(focused.none { it == "Before" || it == "After" }, "background content took focus: $focused")
            visited += inDialog
        }
        assertEquals(inside, visited, "Tab cycles through every control in the dialog")

        onNode(dialogFocus).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithText("Submit").assertDoesNotExist()
        onNodeWithText("Open dialog").assertIsFocused()
    }
}
