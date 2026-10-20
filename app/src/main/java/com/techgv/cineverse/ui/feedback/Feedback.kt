package com.techgv.cineverse.ui.feedback

import androidx.compose.runtime.staticCompositionLocalOf

/** §9 feedback hooks. Phase 7 provides the haptics / sounds implementation; until then nothing happens. */
interface Feedback {
    fun focusMove() {}
    fun rowEdge() {}
    fun confirm() {}
    val reduceMotion: Boolean get() = false
    val ambientGlow: Boolean get() = true
}

object NoFeedback : Feedback

val LocalFeedback = staticCompositionLocalOf<Feedback> { NoFeedback }
