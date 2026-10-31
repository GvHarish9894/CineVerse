package com.techgv.cineverse.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * Horizontal rows: the row stays still while the focused card is already inside the viewport, and scrolls
 * only when the card would leave it. [inset] keeps a margin at both edges so a focus-scaled card is not
 * clipped. Unlike [PinToStart], moving between visible cards never shifts the whole row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeepInView(inset: Dp, content: @Composable () -> Unit) {
    val insetPx = with(LocalDensity.current) { inset.toPx() }
    val spec = remember(insetPx) { KeepInViewSpec(insetPx) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

@OptIn(ExperimentalFoundationApi::class)
internal class KeepInViewSpec(private val insetPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = insetPx
        val end = containerSize - insetPx
        return when {
            size >= end - start -> offset - start // card wider than the usable viewport: align its start
            offset < start -> offset - start
            offset + size > end -> offset + size - end
            else -> 0f
        }
    }
}
