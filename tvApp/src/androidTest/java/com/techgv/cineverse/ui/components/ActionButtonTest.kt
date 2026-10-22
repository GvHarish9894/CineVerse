package com.techgv.cineverse.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.techgv.cineverse.ui.feedback.Feedback
import com.techgv.cineverse.ui.feedback.LocalFeedback
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** §9: one OK → one confirm (the root hook's, none from the button); a pending (disabled) button keeps focus and ignores OK. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ActionButtonTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun oneOk_oneConfirm_pendingKeepsFocusAndIgnoresOk() {
        var confirms = 0
        var clicks = 0
        var enabled by mutableStateOf(true)
        val feedback = object : Feedback {
            override fun confirm() {
                confirms++
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalFeedback provides feedback) {
                // Stand-in for the app root's §9 hook (ui/feedback navigationFeedback): OK key down → confirm.
                Box(Modifier.onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionCenter) feedback.confirm(); false }) {
                    ActionButton("Favourite", ActionIcon.HEART, onClick = { clicks++ }, enabled = enabled)
                }
            }
        }
        compose.onNodeWithText("Favourite").requestFocus()
        compose.onNodeWithText("Favourite").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(1, confirms)
        }

        enabled = false // the library write is pending
        compose.waitForIdle()
        compose.onNodeWithText("Favourite").assertIsFocused().assertIsNotEnabled().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
