package com.techgv.cineverse.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.ui.haptics.HapticKind
import com.techgv.cineverse.ui.haptics.HapticStrength
import com.techgv.cineverse.ui.haptics.Haptics
import com.techgv.cineverse.ui.haptics.rememberPlatformHaptics
import kotlin.test.Test
import kotlin.test.assertSame

/** The platform actual runs every kind at every strength without throwing (device vibrator / UIKit / browser). */
@OptIn(ExperimentalTestApi::class)
class HapticsSmokeTest {
    @Test
    fun everyKindAndStrengthRuns() = runComposeUiTest {
        val created = mutableListOf<Haptics>()
        setContent {
            HapticStrength.entries.forEach { created += rememberPlatformHaptics(enabled = true, strength = it) }
            created += rememberPlatformHaptics(enabled = false, strength = HapticStrength.Medium)
        }
        waitForIdle()
        runOnUiThread { created.forEach { haptics -> HapticKind.entries.forEach(haptics::perform) } }
        assertSame(Haptics.None, created.last())
    }
}
