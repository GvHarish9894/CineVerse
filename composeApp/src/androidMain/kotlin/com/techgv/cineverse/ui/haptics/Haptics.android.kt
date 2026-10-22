package com.techgv.cineverse.ui.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/** INTERACTIONS.md constant per event; null = no constant on this API level → kit waveform instead. */
internal fun androidFeedbackConstant(kind: HapticKind, sdkInt: Int): Int? = when (kind) {
    HapticKind.Tap -> HapticFeedbackConstants.KEYBOARD_TAP
    HapticKind.Tick -> if (sdkInt >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    HapticKind.Select -> HapticFeedbackConstants.CONTEXT_CLICK
    HapticKind.ToggleOn -> if (sdkInt >= 34) HapticFeedbackConstants.TOGGLE_ON else null
    HapticKind.ToggleOff -> if (sdkInt >= 34) HapticFeedbackConstants.TOGGLE_OFF else null
    HapticKind.Success -> if (sdkInt >= 30) HapticFeedbackConstants.CONFIRM else null
    HapticKind.Warn -> if (sdkInt >= 30) HapticFeedbackConstants.REJECT else null
    HapticKind.Back -> HapticFeedbackConstants.VIRTUAL_KEY
}

/** VibrationEffect.createWaveform timings: a leading 0 ms delay, then the kit's on/off segments. */
internal fun androidWaveformTimings(kind: HapticKind, strength: HapticStrength): LongArray =
    (listOf(0) + kind.pattern.timingsMs(strength.baseMs)).map { it.toLong() }.toLongArray()

@Composable
actual fun rememberPlatformHaptics(enabled: Boolean, strength: HapticStrength): Haptics {
    val view = LocalView.current
    val context = LocalContext.current
    return remember(view, context, enabled, strength) {
        if (enabled) AndroidHaptics(view, context.defaultVibrator(), strength) else Haptics.None
    }
}

private fun Context.defaultVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
    else getSystemService(Vibrator::class.java)

private class AndroidHaptics(private val view: View, private val vibrator: Vibrator?, private val strength: HapticStrength) : Haptics {
    override fun perform(kind: HapticKind) {
        val constant = androidFeedbackConstant(kind, Build.VERSION.SDK_INT)
        // Constants respect the system touch-feedback setting; VibrationEffect only for the strength variants (kit).
        if (constant != null && strength == HapticStrength.Medium) {
            view.performHapticFeedback(constant)
            return
        }
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        runCatching { v.vibrate(VibrationEffect.createWaveform(androidWaveformTimings(kind, strength), -1)) }
    }
}
