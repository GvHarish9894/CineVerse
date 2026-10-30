package com.techgv.cineverse.ui.haptics

import kotlin.test.Test
import kotlin.test.assertEquals

class IosHapticsTest {
    @Test
    fun kitMapping() {
        assertEquals(IosFeedback.ImpactLight, iosFeedback(HapticKind.Tap))
        assertEquals(IosFeedback.Selection, iosFeedback(HapticKind.Tick))
        assertEquals(IosFeedback.ImpactMedium, iosFeedback(HapticKind.Select))
        assertEquals(IosFeedback.ImpactRigid, iosFeedback(HapticKind.ToggleOn))
        assertEquals(IosFeedback.ImpactRigid, iosFeedback(HapticKind.ToggleOff))
        assertEquals(IosFeedback.Success, iosFeedback(HapticKind.Success))
        assertEquals(IosFeedback.Warning, iosFeedback(HapticKind.Warn))
        assertEquals(IosFeedback.ImpactSoft, iosFeedback(HapticKind.Back))
    }

    @Test
    fun intensityScalesWithStrength() {
        assertEquals(6.0 / 16, iosImpactIntensity(HapticStrength.Light))
        assertEquals(10.0 / 16, iosImpactIntensity(HapticStrength.Medium))
        assertEquals(1.0, iosImpactIntensity(HapticStrength.Strong))
    }
}
