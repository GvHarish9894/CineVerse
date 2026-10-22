package com.techgv.cineverse.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

val LANDSCAPE_WIDTH = 248.dp
val LANDSCAPE_HEIGHT = 140.dp

/**
 * 248×140 backdrop + title + [subtitle] (§6). Callers build the sub-line from what they know (network
 * name only when known; Home's Popular TV: first genre). [seed] keys the fallback colour. [imageUrl] overrides
 * the TMDB backdrop for non-TMDB art (Detail's video row: YouTube thumbnails).
 */
@Composable
fun LandscapeCard(
    title: String,
    subtitle: String,
    imagePath: String?,
    seed: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = tmdbImageUrl(imagePath, ImageSize.BACKDROP),
) {
    var focused by remember { mutableStateOf(false) }
    val glow = if (focused) rememberPaletteColor(imageUrl, fallbackColor(seed)) else fallbackColor(seed)
    Column(modifier.width(LANDSCAPE_WIDTH)) {
        FocusSurface(
            onClick = onClick,
            shape = CardShape,
            glowColor = glow,
            modifier = Modifier
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = listOf(title, subtitle).filter { it.isNotEmpty() }.joinToString(", ") }
                .size(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT),
        ) {
            TmdbImage(imageUrl, seed, Modifier.fillMaxSize()) {
                Text(
                    title,
                    modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        CardLabel(title, subtitle, focused)
    }
}
