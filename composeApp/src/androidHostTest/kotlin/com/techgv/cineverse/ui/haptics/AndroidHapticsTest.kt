package com.techgv.cineverse.ui.haptics

import android.view.HapticFeedbackConstants
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidHapticsTest {
    @Test
    fun api34UsesTheKitConstants() {
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, androidFeedbackConstant(HapticKind.Tap, 34))
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, androidFeedbackConstant(HapticKind.Tick, 34))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, androidFeedbackConstant(HapticKind.Select, 34))
        assertEquals(HapticFeedbackConstants.TOGGLE_ON, androidFeedbackConstant(HapticKind.ToggleOn, 34))
        assertEquals(HapticFeedbackConstants.TOGGLE_OFF, androidFeedbackConstant(HapticKind.ToggleOff, 34))
        assertEquals(HapticFeedbackConstants.CONFIRM, androidFeedbackConstant(HapticKind.Success, 34))
        assertEquals(HapticFeedbackConstants.REJECT, androidFeedbackConstant(HapticKind.Warn, 34))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, androidFeedbackConstant(HapticKind.Back, 34))
    }

    @Test
    fun olderApisFallBack() {
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, androidFeedbackConstant(HapticKind.Tick, 33))
        assertNull(androidFeedbackConstant(HapticKind.ToggleOn, 33))
        assertEquals(HapticFeedbackConstants.CONFIRM, androidFeedbackConstant(HapticKind.Success, 30))
        assertNull(androidFeedbackConstant(HapticKind.Success, 29))
        assertNull(androidFeedbackConstant(HapticKind.Warn, 26))
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, androidFeedbackConstant(HapticKind.Tap, 26))
    }

    @Test
    fun waveformStartsWithZeroDelayThenKitSegments() {
        assertContentEquals(longArrayOf(0, 6), androidWaveformTimings(HapticKind.Tap, HapticStrength.Light))
        assertContentEquals(longArrayOf(0, 20, 50, 32), androidWaveformTimings(HapticKind.Success, HapticStrength.Strong))
        assertContentEquals(longArrayOf(0, 20, 60, 20), androidWaveformTimings(HapticKind.Warn, HapticStrength.Medium))
    }
}
