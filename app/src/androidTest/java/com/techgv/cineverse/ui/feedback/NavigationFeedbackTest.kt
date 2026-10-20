package com.techgv.cineverse.ui.feedback

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
