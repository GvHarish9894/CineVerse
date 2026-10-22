package com.techgv.cineverse.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** §5 cross-fade of the ambient backdrop and the hero art; 0 with Reduce motion. */
const val BACKDROP_FADE_MILLIS = 400

/**
 * §5 ambient light behind a screen: the blurred artwork of the hero / focused item ([imageUrl]) tinted
 * with its palette colour. Cross-fades 400 ms (0 with Reduce motion); hidden when Ambient glow is off.
 */
@Composable
fun AmbientBackdrop(imageUrl: String?, seed: String, modifier: Modifier = Modifier) {
    val settings = LocalSettings.current
    if (!settings.glow) return
    val color = rememberPaletteColor(imageUrl, fallbackColor(seed))
    Crossfade(
        targetState = imageUrl to color,
        animationSpec = tween(if (settings.motion) 0 else BACKDROP_FADE_MILLIS),
        modifier = modifier.fillMaxSize(),
        label = "ambientBackdrop",
    ) { (url, tint) ->
        Box(Modifier.fillMaxSize()) {
            if (url != null) {
                AsyncImage(url, contentDescription = null, modifier = Modifier.fillMaxSize().blur(80.dp).alpha(0.16f))
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(tint.copy(alpha = 0.22f), tint.copy(alpha = 0f)), radius = 1400f),
                ),
            )
        }
    }
}
