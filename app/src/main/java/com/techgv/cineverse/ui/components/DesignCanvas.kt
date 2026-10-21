package com.techgv.cineverse.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.Density

/** Spec dp are units of the 1280×720 prototype canvas (§6). */
const val DESIGN_WIDTH = 1280f

/** Pixels per design dp for a window [widthPx] wide: 1080p → 1.5, 720p → 1.0, 4K → 3.0. */
fun designDensity(widthPx: Float): Float = widthPx / DESIGN_WIDTH

/**
 * Lays [content] out on the 1280-wide design canvas whatever the TV's resolution / density, keeping the
 * user's font scale. Dialog and Popup windows start a new composition root with the system density:
 * wrap every Dialog's content in [DesignCanvas] too.
 */
@Composable
fun DesignCanvas(content: @Composable () -> Unit) {
    // The window's real width; LocalDensity may already be a design density (nested canvas).
    val widthPx = LocalConfiguration.current.screenWidthDp * LocalResources.current.displayMetrics.density
    val density = Density(designDensity(widthPx), LocalDensity.current.fontScale)
    CompositionLocalProvider(LocalDensity provides density, content = content)
}
