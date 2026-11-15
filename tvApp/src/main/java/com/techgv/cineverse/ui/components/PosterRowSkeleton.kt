package com.techgv.cineverse.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.ui.feedback.LocalFeedback

/** Pagination placeholder: a row of non-focusable poster-sized tiles that pulse (static under reduce motion). */
@Composable
fun PosterRowSkeleton(count: Int, modifier: Modifier = Modifier) {
    val alpha = skeletonAlpha()
    Row(modifier.semantics { contentDescription = "Loading more" }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(count) {
            Box(Modifier.size(POSTER_WIDTH, POSTER_HEIGHT).alpha(alpha).clip(CardShape).background(SkeletonColor))
        }
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
