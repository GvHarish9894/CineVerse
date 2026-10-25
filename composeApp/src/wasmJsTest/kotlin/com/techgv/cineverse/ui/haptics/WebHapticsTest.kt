package com.techgv.cineverse.ui.haptics

import kotlin.test.Test
import kotlin.test.assertEquals

class WebHapticsTest {
    @Test
    fun onlySaveRateToggleVibrate() {
        assertEquals(setOf(HapticKind.Success, HapticKind.ToggleOn, HapticKind.ToggleOff), HapticKind.entries.filter(::webVibrates).toSet())
        assertEquals(10, WEB_VIBRATE_MS)
    }
}
