package com.techgv.cineverse.ui.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Web kit: phones only, on save / rate / toggle; desktop browsers rely on visual feedback. */
internal fun webVibrates(kind: HapticKind): Boolean = kind == HapticKind.Success || kind == HapticKind.ToggleOn || kind == HapticKind.ToggleOff

internal const val WEB_VIBRATE_MS = 10

// Feature-detected; never throws (Safari has no navigator.vibrate, desktop pointers are fine but pointless).
@OptIn(ExperimentalWasmJsInterop::class)
private fun touchVibrationSupported(): Boolean =
    js("typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function' && typeof window.matchMedia === 'function' && window.matchMedia('(pointer: coarse)').matches")

@OptIn(ExperimentalWasmJsInterop::class)
private fun vibrate(ms: Int): Boolean = js("(function () { try { return navigator.vibrate(ms) === true; } catch (e) { return false; } })()")

@Composable
actual fun rememberPlatformHaptics(enabled: Boolean, strength: HapticStrength): Haptics = remember(enabled) {
    if (enabled && touchVibrationSupported()) Haptics { kind -> if (webVibrates(kind)) vibrate(WEB_VIBRATE_MS) } else Haptics.None
}
