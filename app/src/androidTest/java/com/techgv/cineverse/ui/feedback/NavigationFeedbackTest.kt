package com.techgv.cineverse.ui.feedback

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.techgv.cineverse.ui.components.ExitDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** §9 root hook with real D-pad keys: move → focusMove, nothing further → rowEdge, OK → confirm. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NavigationFeedbackTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()
    private val feedback = object : Feedback {
        override fun focusMove(direction: FocusDirection) {
            events += "move $direction"
        }
        override fun rowEdge(direction: FocusDirection) {
            events += "edge $direction"
        }
        override fun confirm() {
            events += "confirm"
        }
    }

    @Test
    fun dpadMoves_edge_andOk_reachFeedback() {
        compose.setContent {
            Row(Modifier.navigationFeedback(feedback, LocalFocusManager.current)) {
                Box(Modifier.testTag("a").size(40.dp).focusable())
                Box(Modifier.testTag("b").size(40.dp).focusable())
            }
        }
        compose.onNodeWithTag("a").requestFocus()

        compose.onNodeWithTag("a").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("b").assertIsFocused()
        compose.onNodeWithTag("b").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("b").assertIsFocused()
        compose.onNodeWithTag("b").performKeyInput { pressKey(Key.DirectionCenter) }

        assertEquals(listOf("move ${FocusDirection.Right}", "edge ${FocusDirection.Right}", "confirm"), events)
    }

    @Test
    fun exitDialog_isItsOwnFeedbackRoot_eachKeyReportedOnce() {
        val main = mutableListOf<String>()
        val mainFeedback = object : Feedback {
            override fun focusMove(direction: FocusDirection) {
                main += "move $direction"
            }
            override fun confirm() {
                main += "confirm"
            }
        }
        var exits = 0
        compose.setContent {
            // The main window's hook (as in AppNav) must stay silent while the dialog window has the keys.
            Row(Modifier.navigationFeedback(mainFeedback, LocalFocusManager.current)) {
                Box(Modifier.size(40.dp).focusable())
                CompositionLocalProvider(LocalFeedback provides feedback) {
                    ExitDialog(onStay = {}, onExit = { exits++ })
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(isFocused() and hasText("Stay")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Stay").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Exit app").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertEquals(listOf("move ${FocusDirection.Right}", "confirm"), events)
        assertEquals(emptyList<String>(), main)
        assertEquals(1, exits)
    }
}
