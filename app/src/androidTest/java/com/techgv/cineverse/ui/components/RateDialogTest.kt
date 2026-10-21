package com.techgv.cineverse.ui.components

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** §7.11 Rate dialog with real D-pad keys. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RateDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private var submitted: Double? = null
    private var cancelled = false

    private fun show(initial: Double?) = compose.setContent {
        RateDialog("Dune", initial, onSubmit = { submitted = it }, onRemove = {}, onCancel = { cancelled = true })
    }

    @Test
    fun sliderStartsAtSevenAdjustsInHalfStepsAndOkSubmits() {
        show(initial = null)
        compose.onNodeWithTag(RATING_SLIDER_TAG).assertIsFocused()
        compose.onNodeWithText("7.0").assertExists()
        compose.onNodeWithTag(RATING_SLIDER_TAG).performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionLeft)
        }
        compose.onNodeWithText("8.0").assertExists()
        compose.onNodeWithText("Great · out of 10").assertExists()
        compose.onNodeWithText("Remove rating").assertDoesNotExist()
        compose.onNodeWithTag(RATING_SLIDER_TAG).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(8.0, submitted)
    }

    @Test
    fun existingRatingIsDefaultClampsAtTenAndOffersRemove() {
        show(initial = 9.5)
        compose.onNodeWithText("Remove rating").assertExists()
        compose.onNodeWithTag(RATING_SLIDER_TAG).performKeyInput { repeat(3) { pressKey(Key.DirectionRight) } }
        compose.onNodeWithText("10.0").assertExists()
    }

    @Test
    fun backCancelsWithoutSubmitting() {
        show(initial = 6.0)
        compose.onNodeWithTag(RATING_SLIDER_TAG).assertIsFocused()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertTrue(cancelled)
        assertNull(submitted)
    }
}
