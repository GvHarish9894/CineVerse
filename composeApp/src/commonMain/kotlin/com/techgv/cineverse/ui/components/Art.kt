package com.techgv.cineverse.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.techgv.cineverse.ui.design.Scrims.posterBottomScrim
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A title's two palette colours (kits: extracted from the poster in the real app; hand-picked in samples). */
@Immutable
data class ArtPalette(val c1: Color, val c2: Color)

/** Which prototype fallback art to draw while there is no image (or it failed). */
enum class ArtStyle { Poster, Backdrop, Person }

/**
 * Image with the prototypes' gradient art behind it. [fallback] (e.g. the title in display type) shows until an
 * image has loaded, so missing/broken images still read as a designed card. [bottomScrim] darkens the bottom (titles).
 * [placeholderUrl]: an already-loaded smaller rendition (e.g. the tapped card's) shown from memory while [imageUrl] loads.
 */
@Composable
fun PaletteArt(
    imageUrl: String?,
    palette: ArtPalette,
    style: ArtStyle,
    modifier: Modifier = Modifier,
    bottomScrim: Boolean = false,
    placeholderUrl: String? = null,
    fallback: @Composable BoxScope.() -> Unit = {},
) {
    var loaded by remember(imageUrl) { mutableStateOf(false) }
    val context = LocalPlatformContext.current
    Box(modifier.paletteArt(palette, style)) {
        if (imageUrl != null) {
            AsyncImage(
                // No transformations, so Coil's memory key is the bare URL.
                model = if (placeholderUrl == null) imageUrl
                    else remember(imageUrl, placeholderUrl) { ImageRequest.Builder(context).data(imageUrl).placeholderMemoryCacheKey(placeholderUrl).build() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { loaded = true },
            )
        }
        if (bottomScrim) Box(Modifier.matchParentSize().posterBottomScrim())
        if (!loaded) fallback()
    }
}

private val ArtBase = Color(0xFF0A0913)

/** posterArt / backdropArt / personArt from the prototypes, layer for layer (bottom first). */
fun Modifier.paletteArt(palette: ArtPalette, style: ArtStyle): Modifier = drawBehind {
    val (c1, c2) = palette
    when (style) {
        ArtStyle.Poster -> {
            cssLinear(165f, c2.copy(alpha = 0x77 / 255f), ArtBase)
            cssRadial(0.85f, 0.82f, c2, 0.60f)
            cssRadial(0.25f, 0.18f, c1, 0.55f)
        }
        ArtStyle.Backdrop -> {
            cssLinear(135f, ArtBase, c2.copy(alpha = 0x55 / 255f))
            cssRadial(0.34f, 0.96f, c2.copy(alpha = 0x66 / 255f), 0.70f, 0.42f, 0.52f)
            cssRadial(0.96f, 0.88f, c2.copy(alpha = 0xDD / 255f), 0.66f, 0.50f, 0.70f)
            cssRadial(0.62f, 0.34f, c1.copy(alpha = 0xDD / 255f), 0.62f, 0.60f, 0.80f)
        }
        ArtStyle.Person -> {
            cssLinear(160f, c2.copy(alpha = 0x88 / 255f), Color(0xFF0B0A16))
            cssRadial(0.80f, 0.85f, c2, 0.66f)
            cssRadial(0.30f, 0.25f, c1, 0.62f)
        }
    }
}

/** CSS `linear-gradient(<angle>deg, from, to)` over the whole draw area. */
internal fun DrawScope.cssLinear(angleDeg: Float, from: Color, to: Color) {
    val rad = angleDeg * PI.toFloat() / 180f
    val direction = Offset(sin(rad), -cos(rad))
    val half = (abs(size.width * direction.x) + abs(size.height * direction.y)) / 2f
    drawRect(Brush.linearGradient(listOf(from, to), start = center - direction * half, end = center + direction * half))
}

/**
 * CSS `radial-gradient(<shape> at cx cy, color 0%, transparent stop)`: a farthest-corner circle when [rx]/[ry] are
 * null, else an ellipse with radii as fractions of width/height.
 */
internal fun DrawScope.cssRadial(cx: Float, cy: Float, color: Color, stop: Float, rx: Float? = null, ry: Float? = null) {
    val c = Offset(size.width * cx, size.height * cy)
    if (rx == null || ry == null) {
        val corners = listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
        val radius = corners.maxOf { (it - c).getDistance() }
        radialEllipse(c, radius, radius, color, stop)
    } else {
        radialEllipse(c, size.width * rx, size.height * ry, color, stop)
    }
}

/** An elliptical radial gradient (centre colour → transparent at [stop] of the radii), filling the draw area. */
internal fun DrawScope.radialEllipse(center: Offset, radiusX: Float, radiusY: Float, color: Color, stop: Float) {
    val squash = radiusY / radiusX
    scale(scaleX = 1f, scaleY = squash, pivot = center) {
        // Cover the whole (unscaled) area in the squashed coordinate space.
        val top = center.y - center.y / squash
        drawRect(
            Brush.radialGradient(0f to color, stop to color.copy(alpha = 0f), center = center, radius = radiusX),
            topLeft = Offset(0f, top),
            size = Size(size.width, size.height / squash),
        )
    }
}
