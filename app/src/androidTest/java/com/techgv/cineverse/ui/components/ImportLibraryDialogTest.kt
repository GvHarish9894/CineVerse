package com.techgv.cineverse.ui.components

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
