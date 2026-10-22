package com.techgv.cineverse.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Spec dp are units of the 1280×720 prototype canvas (§6). */
const val DESIGN_WIDTH = 1280f

/** Pixels per design dp for a window [widthPx] wide: 1080p → 1.5, 720p → 1.0, 4K → 3.0. */
fun designDensity(widthPx: Float): Float = widthPx / DESIGN_WIDTH

/** The outermost canvas's density; custom locals reach Dialog / Popup content, platform ones do not. */
private val LocalDesignDensity = compositionLocalOf<Float?> { null }

/**
 * Lays [content] out on the 1280-wide design canvas whatever the TV's resolution / density, keeping the
 * user's font scale. The outermost canvas (MainActivity) measures its container's real pixel width.
 * Dialog and Popup windows start a new composition root with the system density: wrap every Dialog's
 * content in [DesignCanvas] too; it reuses the outer canvas's density instead of the dialog's width.
 */
@Composable
fun DesignCanvas(content: @Composable () -> Unit) {
    val inherited = LocalDesignDensity.current
    if (inherited != null) {
        ProvideDesignDensity(inherited, content)
    } else {
        BoxWithConstraints { ProvideDesignDensity(designDensity(constraints.maxWidth.toFloat()), content) }
    }
}

@Composable
private fun ProvideDesignDensity(density: Float, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDensity provides Density(density, LocalDensity.current.fontScale),
        LocalDesignDensity provides density,
        content = content,
    )
}
