package com.techgv.cineverse.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.ui.feedback.LocalFeedback

/**
 * Loading placeholder: a row of non-focusable card-sized tiles that pulse (static under reduce motion);
 * poster-sized unless [width] / [height] / [shape] give another card's.
 */
@Composable
fun PosterRowSkeleton(
    count: Int,
    modifier: Modifier = Modifier,
    width: Dp = POSTER_WIDTH,
    height: Dp = POSTER_HEIGHT,
    shape: Shape = CardShape,
    label: String = "Loading more",
) {
    val alpha = skeletonAlpha()
    Row(modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(count) {
            Box(Modifier.size(width, height).alpha(alpha).clip(shape).background(SkeletonColor))
        }
    }
}

/** First-page placeholder of a poster grid: [rows] rows of [columns] tiles. */
@Composable
fun GridSkeleton(columns: Int, modifier: Modifier = Modifier, rows: Int = 2) {
    Column(modifier.semantics { contentDescription = "Loading" }, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        repeat(rows) { PosterRowSkeleton(columns, label = "Loading") }
    }
}

/** Fill of every skeleton block. */
val SkeletonColor = Color.White.copy(alpha = 0.08f)

/** Skeleton pulse: alpha 0.5 ↔ 1 every 800 ms; a steady 0.75 under Reduce motion. */
@Composable
fun skeletonAlpha(): Float {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        0.5f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "skeletonAlpha",
    )
    return if (LocalFeedback.current.reduceMotion) 0.75f else pulse
}
