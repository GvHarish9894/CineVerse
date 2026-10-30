package com.techgv.cineverse.ui.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UISelectionFeedbackGenerator

/** INTERACTIONS.md iOS column. */
internal enum class IosFeedback { ImpactLight, ImpactMedium, ImpactRigid, ImpactSoft, Selection, Success, Warning }

internal fun iosFeedback(kind: HapticKind): IosFeedback = when (kind) {
    HapticKind.Tap -> IosFeedback.ImpactLight
    HapticKind.Tick -> IosFeedback.Selection
    HapticKind.Select -> IosFeedback.ImpactMedium
    HapticKind.ToggleOn, HapticKind.ToggleOff -> IosFeedback.ImpactRigid
    HapticKind.Success -> IosFeedback.Success
    HapticKind.Warn -> IosFeedback.Warning
    HapticKind.Back -> IosFeedback.ImpactSoft
}

/** Impact intensity scales with the kit's base duration (Strong = 1.0). */
internal fun iosImpactIntensity(strength: HapticStrength): Double = strength.baseMs.toDouble() / HapticStrength.Strong.baseMs

@Composable
actual fun rememberPlatformHaptics(enabled: Boolean, strength: HapticStrength): Haptics =
    remember(enabled, strength) { if (enabled) IosHaptics(strength) else Haptics.None }

private class IosHaptics(private val strength: HapticStrength) : Haptics {
    override fun perform(kind: HapticKind) {
        when (val feedback = iosFeedback(kind)) {
            IosFeedback.Selection -> UISelectionFeedbackGenerator().selectionChanged()
            IosFeedback.Success -> UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
            IosFeedback.Warning -> UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
            else -> UIImpactFeedbackGenerator(style = impactStyle(feedback)).impactOccurredWithIntensity(iosImpactIntensity(strength))
        }
    }

    private fun impactStyle(feedback: IosFeedback): UIImpactFeedbackStyle = when (feedback) {
        IosFeedback.ImpactMedium -> UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium
        IosFeedback.ImpactRigid -> UIImpactFeedbackStyle.UIImpactFeedbackStyleRigid
        IosFeedback.ImpactSoft -> UIImpactFeedbackStyle.UIImpactFeedbackStyleSoft
        else -> UIImpactFeedbackStyle.UIImpactFeedbackStyleLight
    }
}
