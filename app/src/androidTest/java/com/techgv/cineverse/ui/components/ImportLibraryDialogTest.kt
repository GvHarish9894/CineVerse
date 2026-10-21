package com.techgv.cineverse.ui.components

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** §8.3: a running import and the resume prompt have a focused Later; BACK is Later. */
@RunWith(AndroidJUnit4::class)
class ImportLibraryDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun runningImportFocusesLaterAndBackIsLater() = laterIsFocusedAndBack(ImportDialogState.Running)

    @Test
    fun resumePromptFocusesLaterAndBackIsLater() = laterIsFocusedAndBack(ImportDialogState.Ask("Harish", 2, resume = true))

    /** Final review #3: 100 unconfirmed titles scroll in a bounded region; the warning, Done and Retry stay on screen. */
    @Test
    fun hundredUnconfirmedTitlesKeepWarningDoneAndRetryReachable() {
        var retry = 0
        var close = 0
        val state = ImportDialogState.Unconfirmed(written = 3, titles = (1..100).map { "Unconfirmed title $it" })
        compose.setContent {
            ImportLibraryDialog(state, onKeepSeparate = {}, onImport = {}, onRetry = { retry++ }, onClose = { close++ }, onLater = {})
        }
        compose.waitForIdle()
        compose.onNodeWithText("Couldn't confirm 100 items").assertIsDisplayed()
        compose.onNodeWithText("Retrying may re-add", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText("Retry").assertIsDisplayed()

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("Retry").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, retry)

        // UP into the titles, read them all by DOWN; the last DOWN lands back on the buttons.
        press(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNode(focusedScrollableText).assertExists()
        assertTrue(compose.readFocusedScrollableTextByDpad() > 1)
        compose.onNode((hasText("Done") or hasText("Retry")) and isFocused()).assertExists()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithText("Done").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, close)
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    private fun laterIsFocusedAndBack(state: ImportDialogState) {
        var later = 0
        compose.setContent {
            ImportLibraryDialog(state, onKeepSeparate = { error("keep separate") }, onImport = {}, onRetry = {}, onClose = {}, onLater = { later++ })
        }
        compose.waitForIdle()
        compose.onNodeWithText("Later").assertIsFocused()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertEquals(1, later)
    }
}
