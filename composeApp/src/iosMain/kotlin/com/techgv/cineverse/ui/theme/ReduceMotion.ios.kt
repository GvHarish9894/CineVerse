package com.techgv.cineverse.ui.theme

import androidx.compose.runtime.Composable
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

@Composable
actual fun systemReduceMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()
