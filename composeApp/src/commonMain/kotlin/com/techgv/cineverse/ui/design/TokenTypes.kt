package com.techgv.cineverse.ui.design

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

/** A tokens.json motion entry: duration + optional cubic-bezier, with the verbatim [spec] text. */
data class MotionSpec(val durationMillis: Int, val easing: Easing?, val spec: String) {
    fun <T> tween(): TweenSpec<T> = tween(durationMillis, easing = easing ?: LinearEasing)
}

/** One vibration segment: `baseMultiplier × base + offsetMs` (`base`, `base+8`, `base*2`, or a fixed `45`). */
data class HapticStep(val baseMultiplier: Int, val offsetMs: Int) {
    fun millis(baseMs: Int): Int = baseMultiplier * baseMs + offsetMs
}

/** A tokens.json / kit haptic pattern: alternating on/off segments starting with "on". */
data class HapticTokenPattern(val steps: List<HapticStep>, val spec: String) {
    fun timingsMs(baseMs: Int): List<Int> = steps.map { it.millis(baseMs) }
}
