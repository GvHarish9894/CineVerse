package com.techgv.cineverse.ui.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.techgv.cineverse.ui.design.HapticStep
import com.techgv.cineverse.ui.design.HapticTokenPattern

/**
 * The mobile kit's haptic events (docs/kits/mobile/INTERACTIONS.md + prototype `hap()`), not tokens.json's TV-flavoured
 * set — see docs/kits/TOKEN_CONFLICTS.md. Toggle is split by state because Android 14 has TOGGLE_ON / TOGGLE_OFF.
 */
enum class HapticKind(val pattern: HapticTokenPattern) {
    Tap(kitPattern("[base]", HapticStep(1, 0))),
    Tick(kitPattern("[base]", HapticStep(1, 0))),
    Select(kitPattern("[base+4]", HapticStep(1, 4))),
    ToggleOn(kitPattern("[base,30,base]", HapticStep(1, 0), HapticStep(0, 30), HapticStep(1, 0))),
    ToggleOff(kitPattern("[base,30,base]", HapticStep(1, 0), HapticStep(0, 30), HapticStep(1, 0))),
    Success(kitPattern("[base+4,50,base*2]", HapticStep(1, 4), HapticStep(0, 50), HapticStep(2, 0))),
    Warn(kitPattern("[base*2,60,base*2]", HapticStep(2, 0), HapticStep(0, 60), HapticStep(2, 0))),
    Back(kitPattern("[base]", HapticStep(1, 0))),
}

private fun kitPattern(spec: String, vararg steps: HapticStep) = HapticTokenPattern(steps.toList(), spec)

/** Mobile kit strength setting: `base` = 6 / 10 / 16 ms (Light / Medium / Strong). */
enum class HapticStrength(val baseMs: Int) { Light(6), Medium(10), Strong(16) }

/** Fire-and-forget haptic feedback. Implementations never throw and do nothing when disabled. */
fun interface Haptics {
    fun perform(kind: HapticKind)

    companion object {
        val None = Haptics { }
    }
}

val LocalHaptics = staticCompositionLocalOf { Haptics.None }

/**
 * Platform haptics honouring the app's Haptic feedback toggle and strength. Android: performHapticFeedback
 * constants with API fallbacks, VibrationEffect only for Light/Strong; iOS: UIKit feedback generators; web:
 * navigator.vibrate(10) on touch devices for save/rate/toggle only.
 */
@Composable
expect fun rememberPlatformHaptics(enabled: Boolean, strength: HapticStrength): Haptics
