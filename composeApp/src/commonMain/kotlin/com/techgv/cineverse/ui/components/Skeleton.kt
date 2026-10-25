package com.techgv.cineverse.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.ui.theme.LocalReduceMotion

private val ShimmerBase = Color.White.copy(alpha = 0.06f)
private val ShimmerHighlight = Color.White.copy(alpha = 0.12f)

/** Placeholder block with a moving highlight (a static tile under reduce motion). */
@Composable
fun Modifier.shimmer(shape: Shape = RoundedCornerShape(8.dp)): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val progress by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerProgress",
    )
    return clip(shape).background(ShimmerBase).drawBehind {
        if (!reduceMotion) {
            val x = size.width * progress
            drawRect(Brush.linearGradient(listOf(Color.Transparent, ShimmerHighlight, Color.Transparent), Offset(x - size.width / 2, 0f), Offset(x + size.width / 2, size.height)))
        }
    }
}

/** Loading state of [PosterCard]: same footprint (2:3 art + title + subtitle lines). */
@Composable
fun PosterCardSkeleton(modifier: Modifier = Modifier, width: Dp = defaultPosterWidth()) {
    Column(modifier.width(width).semantics { contentDescription = "Loading" }) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).shimmer(RoundedCornerShape(posterRadius())))
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(12.dp).shimmer())
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(0.5f).height(10.dp).shimmer())
    }
}

/** Loading state of [PersonCircle]. */
@Composable
fun PersonCircleSkeleton(modifier: Modifier = Modifier, diameter: Dp = 92.dp) {
    Column(modifier.width(diameter).semantics { contentDescription = "Loading" }) {
        Box(Modifier.size(diameter).shimmer(CircleShape))
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(12.dp).shimmer())
    }
}

/** Generic text-line placeholder for rows, reviews and settings. */
@Composable
fun SkeletonLines(lines: Int, modifier: Modifier = Modifier) {
    Column(modifier.semantics { contentDescription = "Loading" }) {
        repeat(lines) { index ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(if (index == lines - 1) 0.6f else 1f).height(12.dp).shimmer())
        }
    }
}
