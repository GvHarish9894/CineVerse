package com.techgv.cineverse.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.techgv.cineverse.ui.feedback.LocalFeedback
import kotlin.math.abs

// Interim card focus style and artwork for the Detail-lane cards (ReviewCard, FactCard, CreditCard,
// SeasonCard and the Detail screens' private cards); the browse lane's shared card styling replaces it.

/** §6 focus: scale 1.08, glow when Ambient glow is on, a plain border always (focus is always visible). */
@Composable
fun DetailCardSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val feedback = LocalFeedback.current
    Surface(
        onClick = {
            feedback.confirm()
            onClick()
        },
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape)),
        glow = if (feedback.ambientGlow) {
            ClickableSurfaceDefaults.glow(focusedGlow = Glow(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), 16.dp))
        } else {
            ClickableSurfaceDefaults.glow()
        },
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

/** `{base}{size}{path}`, or null for a missing path (§1.5 sizes: poster w342 / w780, backdrop w1280, profile w185 / h632, still w300). */
fun tmdbImageUrl(base: String, size: String, path: String?): String? = path?.takeIf { it.isNotBlank() }?.let { base + size + it }

fun initialsOf(name: String): String =
    name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

private fun fallbackBrush(seed: String): Brush {
    val hue = abs(seed.hashCode() % 360).toFloat()
    return Brush.linearGradient(listOf(Color.hsv(hue, 0.55f, 0.45f), Color.hsv((hue + 60f) % 360f, 0.6f, 0.2f)))
}
