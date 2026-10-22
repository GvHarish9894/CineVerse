package com.techgv.cineverse.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.techgv.cineverse.ui.feedback.LocalFeedback
import kotlin.math.abs

// Interim card focus style and artwork for the Detail-lane cards (ReviewCard, FactCard, CreditCard,
// SeasonCard and the Detail screens' private cards); the browse lane's shared card styling replaces it.

/**
 * §6 focus frame: scale [focusedScale] over 150 ms (snaps with Reduce motion), a glow when Ambient glow is on,
 * a plain white border always (focus is always visible). A disabled surface stays focusable (focus must not
 * jump away from a pending button) but ignores OK. The §9 OK confirm comes from the root key hook, not here.
 */
@Composable
fun DetailCardSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    enabled: Boolean = true,
    focusedScale: Float = 1.08f,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable BoxScope.() -> Unit,
) {
    val feedback = LocalFeedback.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = if (feedback.reduceMotion) snap() else tween(150),
        label = "detailFocusScale",
    )
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focused && feedback.ambientGlow) Modifier.shadow(16.dp, shape, ambientColor = glow, spotColor = glow) else Modifier)
            .clip(shape)
            .background(containerColor)
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .semantics { if (!enabled) disabled() }
            .clickable(interactionSource = null, indication = null, onClick = { if (enabled) onClick() }),
        content = content,
    )
}

/** TMDB artwork, or (null path / load failure) a gradient from a hash of [fallbackText] with that text (§1.5). */
@Composable
fun DetailCardArt(url: String?, fallbackText: String, modifier: Modifier = Modifier, initials: Boolean = false) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.background(fallbackBrush(fallbackText)), contentAlignment = Alignment.Center) {
        if (url == null || failed) {
            Text(
                if (initials) initialsOf(fallbackText) else fallbackText,
                style = if (initials) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleSmall,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(!LocalFeedback.current.reduceMotion).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

fun initialsOf(name: String): String =
    name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

private fun fallbackBrush(seed: String): Brush {
    val hue = abs(seed.hashCode() % 360).toFloat()
    return Brush.linearGradient(listOf(Color.hsv(hue, 0.55f, 0.45f), Color.hsv((hue + 60f) % 360f, 0.6f, 0.2f)))
}
