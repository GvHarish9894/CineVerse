package com.techgv.cineverse.ui.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
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
        if (enabled) AndroidHaptics(view, strength, context.defaultVibrator()?.takeIf { it.hasVibrator() }?.let { it::vibrateAsTouch }) else Haptics.None
    }
}

private fun Context.defaultVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
    else getSystemService(Vibrator::class.java)

/** Touch usage (API 33+): the system also silences it when Touch feedback is off. */
private fun Vibrator.vibrateAsTouch(effect: VibrationEffect) {
    if (Build.VERSION.SDK_INT >= 33) vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)) else vibrate(effect)
}

/** The policy `performHapticFeedback` applies: the view's flag and the system Touch feedback setting. */
internal fun View.touchFeedbackEnabled(): Boolean =
    isHapticFeedbackEnabled && Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

/** [vibrate] = the device vibrator (null when there is none); a parameter so tests can record instead. */
internal class AndroidHaptics(private val view: View, private val strength: HapticStrength, private val vibrate: ((VibrationEffect) -> Unit)?) : Haptics {
    override fun perform(kind: HapticKind) {
        val constant = androidFeedbackConstant(kind, Build.VERSION.SDK_INT)
        // Constants respect the system touch-feedback setting; VibrationEffect only for the strength variants (kit).
        if (constant != null && strength == HapticStrength.Medium) {
            view.performHapticFeedback(constant)
            return
        }
        // Direct vibration skips the view's policy, so apply the same policy here.
        if (vibrate == null || !view.touchFeedbackEnabled()) return
        runCatching { vibrate(VibrationEffect.createWaveform(androidWaveformTimings(kind, strength), -1)) }
    }
}
