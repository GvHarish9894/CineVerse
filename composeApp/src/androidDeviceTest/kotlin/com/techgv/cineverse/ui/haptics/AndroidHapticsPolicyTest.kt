package com.techgv.cineverse.ui.haptics

import android.os.VibrationEffect
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import com.techgv.cineverse.ui.withDeviceSetting
import kotlin.test.Test
import kotlin.test.assertEquals

/** Light/Strong and API-fallback haptics vibrate directly, so they must apply the touch-feedback policy themselves. */
class AndroidHapticsPolicyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Vibrations recorded for one Strong Tap (direct path) and one Light ToggleOn on [view]. */
    private fun vibrations(viewEnabled: Boolean): Int {
        val recorded = mutableListOf<VibrationEffect>()
        instrumentation.runOnMainSync {
            val view = View(instrumentation.targetContext).apply { isHapticFeedbackEnabled = viewEnabled }
            AndroidHaptics(view, HapticStrength.Strong) { recorded += it }.perform(HapticKind.Tap)
            AndroidHaptics(view, HapticStrength.Light) { recorded += it }.perform(HapticKind.ToggleOn)
        }
        return recorded.size
    }

    @Test
    fun directVibrationFollowsViewAndSystemTouchFeedback() {
        withDeviceSetting("system", "haptic_feedback_enabled", "1") {
            assertEquals(2, vibrations(viewEnabled = true), "touch feedback on: both direct haptics vibrate")
            assertEquals(0, vibrations(viewEnabled = false), "view opted out of haptic feedback")
        }
        withDeviceSetting("system", "haptic_feedback_enabled", "0") {
            assertEquals(0, vibrations(viewEnabled = true), "system Touch feedback is off")
        }
    }
}
