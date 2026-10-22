package com.techgv.cineverse.ui.theme

import android.animation.ValueAnimator
import androidx.compose.runtime.Composable

/** "Remove animations" / animator duration scale 0 in developer or accessibility settings. */
@Composable
actual fun systemReduceMotion(): Boolean = !ValueAnimator.areAnimatorsEnabled()
