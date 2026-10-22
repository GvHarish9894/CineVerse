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
 * §1.4: the focused card scrolls to the row's leading edge (prototype `translateX(-tx)`), [leadingInset]
 * from the viewport start. Wrap one LazyRow / LazyVerticalGrid; nested scrollables inherit it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PinToStart(leadingInset: Dp, content: @Composable () -> Unit) {
    val insetPx = with(LocalDensity.current) { leadingInset.toPx() }
    val spec = remember(insetPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = offset - insetPx
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
