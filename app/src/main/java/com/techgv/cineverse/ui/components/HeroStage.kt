package com.techgv.cineverse.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Hero artwork at the top right of the stage (prototype `.bd` 980×620 of 1280×720) with left / bottom
 * scrims into the background; cross-fades 400 ms between heroes (0 with Reduce motion, §5).
 */
@Composable
fun BoxScope.HeroBackdrop(imageUrl: String?, seed: String) {
    val reduceMotion = LocalSettings.current.motion
    Crossfade(
        targetState = imageUrl to seed,
        animationSpec = tween(if (reduceMotion) 0 else BACKDROP_FADE_MILLIS),
        modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.766f).fillMaxHeight(0.861f),
        label = "heroBackdrop",
    ) { (url, key) ->
        Box(Modifier.fillMaxSize()) {
            TmdbImage(url, key, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to UiColors.Background,
                        0.27f to UiColors.Background.copy(alpha = 0.94f),
                        0.5f to UiColors.Background.copy(alpha = 0.62f),
                        0.78f to Color.Transparent,
                    ),
                ),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.3f to Color.Transparent, 0.75f to UiColors.Background.copy(alpha = 0.92f), 1f to UiColors.Background),
                ),
            )
        }
    }
}

/**
 * Hero text block (§7.2): eyebrow, title (46 sp if longer than 17 characters, else 60), score ring +
 * meta line, overview (3 lines; 2 when [compact], i.e. focus is in the rows below).
 */
@Composable
fun HeroInfo(
    eyebrow: String,
    title: String,
    meta: String,
    overview: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
    ring: (@Composable () -> Unit)? = null,
) {
    Column(modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(8.dp).background(UiColors.Accent, CircleShape))
            Text(eyebrow.uppercase(), color = UiColors.TextDim, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, maxLines = 1)
        }
        Text(
            title,
            color = Color.White,
            fontSize = if (title.length > 17) 46.sp else 60.sp,
            lineHeight = if (title.length > 17) 48.sp else 62.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ring?.invoke()
            Text(meta, color = Color(0xDBF4F2FF), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (overview.isNotBlank()) {
            Text(
                overview,
                color = Color(0xD6F4F2FF),
                fontSize = 16.sp,
                lineHeight = 24.sp,
                maxLines = if (compact) 2 else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(600.dp),
            )
        }
    }
}
