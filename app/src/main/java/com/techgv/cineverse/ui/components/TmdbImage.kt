package com.techgv.cineverse.ui.components

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.techgv.cineverse.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

/** §1.5 TMDB image sizes. */
object ImageSize {
    const val POSTER = "w342"
    const val POSTER_HERO = "w780"
    const val BACKDROP = "w1280"
    const val PROFILE = "w185"
    const val PROFILE_HERO = "h632"
    const val STILL = "w300"
}

fun tmdbImageUrl(baseUrl: String, size: String, path: String?): String? =
    path?.let { baseUrl.trimEnd('/') + "/" + size + "/" + it.trimStart('/') }

/** Full image URL for a nullable `*_path`, base URL from `/3/configuration` when loaded (§1.5). */
@Composable
fun tmdbImageUrl(path: String?, size: String): String? {
    if (path == null) return null
    val repository = LocalContext.current.appContainer.tmdbRepository
    return tmdbImageUrl(repository.imageBaseUrl(), size, path)
}

/** Deterministic colour for artwork-less items and glow until the palette is known (§1.5 fallback). */
fun fallbackColor(seed: String): Color {
    val hue = (seed.hashCode().absoluteValue % 360).toFloat()
    return Color.hsl(hue, 0.55f, 0.42f)
}

/**
 * Artwork of a card / hero: [url] when present; otherwise (and while loading) a gradient from [seed]
 * with [fallback] text (title or initials) on top. Coil cross-fade is off with Reduce motion (§6).
 */
@Composable
fun TmdbImage(
    url: String?,
    seed: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable BoxScope.() -> Unit = {},
) {
    val base = fallbackColor(seed)
    Box(modifier.background(Brush.linearGradient(listOf(base, base.copy(alpha = 0.55f).compositeOverDark())))) {
        if (url == null) {
            fallback()
        } else {
            val reduceMotion = LocalSettings.current.motion
            val context = LocalContext.current
            val request = remember(url, reduceMotion) {
                ImageRequest.Builder(context).data(url).crossfade(!reduceMotion).build()
            }
            AsyncImage(model = request, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun Color.compositeOverDark(): Color = Color(
    red = red * alpha + 0.03f * (1 - alpha),
    green = green * alpha + 0.02f * (1 - alpha),
    blue = blue * alpha + 0.06f * (1 - alpha),
)

/** Palette colours by image URL, shared by focus glow and the ambient backdrop. */
private val paletteCache = LruCache<String, Int>(256)

/**
 * The artwork's palette colour (vibrant, else dominant) for glow / ambient light (§5, §6), [fallback]
 * until it is known or when [url] is null. Decodes a small software bitmap of the image once per URL.
 */
@Composable
fun rememberPaletteColor(url: String?, fallback: Color): Color {
    var color by remember(url) { mutableStateOf(url?.let { paletteCache.get(it) }?.let(::Color)) }
    val context = LocalContext.current
    LaunchedEffect(url) {
        if (url == null || color != null) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(url).size(PALETTE_SAMPLE_PX).allowHardware(false).build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        val argb = withContext(Dispatchers.Default) { paletteArgb(bitmap) } ?: return@LaunchedEffect
        paletteCache.put(url, argb)
        color = Color(argb)
    }
    return color ?: fallback
}

private fun paletteArgb(bitmap: Bitmap): Int? {
    val palette = Palette.from(bitmap).maximumColorCount(12).generate()
    val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch ?: return null
    return Color(swatch.rgb).toArgb()
}

private const val PALETTE_SAMPLE_PX = 64
