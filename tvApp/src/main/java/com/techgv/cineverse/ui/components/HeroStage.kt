package com.techgv.cineverse.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * Every part sits in a fixed slot ([HeroSlots]), so a shorter title or overview, a missing ring or a person never
 * moves what is below; only [compact] changes the height. [HeroInfoSkeleton] fills the same slots while loading.
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
    HeroSlots(
        compact = compact,
        modifier = modifier,
        eyebrow = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(8.dp).background(UiColors.Accent, CircleShape))
                Text(
                    eyebrow.uppercase(),
                    color = UiColors.TextDim,
                    fontSize = 13.sp,
                    lineHeight = EYEBROW_LINE_HEIGHT,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.3.sp,
                    maxLines = 1,
                )
            }
        },
        title = {
            Text(
                title,
                color = Color.White,
                fontSize = if (title.length > 17) 46.sp else 60.sp,
                lineHeight = if (title.length > 17) 48.sp else TITLE_LINE_HEIGHT,
                fontWeight = FontWeight.ExtraBold,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        meta = {
            ring?.invoke()
            Text(meta, color = Color(0xDBF4F2FF), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        overview = {
            Text(
                overview,
                color = Color(0xD6F4F2FF),
                fontSize = 16.sp,
                lineHeight = OVERVIEW_LINE_HEIGHT,
                maxLines = if (compact) 2 else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(600.dp),
            )
        },
    )
}

/** [HeroInfo]'s loading state: pulsing blocks in exactly its slots, so the real hero replaces it without a jump. */
@Composable
fun HeroInfoSkeleton(compact: Boolean, modifier: Modifier = Modifier) {
    val alpha = skeletonAlpha()
    fun Modifier.block(shape: Shape = RoundedCornerShape(6.dp)) = alpha(alpha).clip(shape).background(SkeletonColor)
    HeroSlots(
        compact = compact,
        modifier = modifier.semantics { contentDescription = "Loading" },
        eyebrow = { Box(Modifier.size(220.dp, 12.dp).block()) },
        title = { Box(Modifier.size(440.dp, 48.dp).block(RoundedCornerShape(10.dp))) },
        meta = {
            Box(Modifier.size(ScoreRingSize).block(CircleShape))
            Box(Modifier.size(260.dp, 14.dp).block())
        },
        overview = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(if (compact) 2 else 3) { line -> Box(Modifier.size(if (line == 0) 600.dp else 480.dp, 14.dp).block()) }
            }
        },
    )
}

/**
 * The hero's fixed layout, in line heights converted to dp (the TV design canvas scales dp; this follows font
 * scale too): eyebrow 1 line, title 1 tall line (2 unless [compact]), meta row = ring, overview 2 lines (3).
 */
@Composable
private fun HeroSlots(
    compact: Boolean,
    modifier: Modifier,
    eyebrow: @Composable () -> Unit,
    title: @Composable () -> Unit,
    meta: @Composable RowScope.() -> Unit,
    overview: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val eyebrowHeight = with(density) { EYEBROW_LINE_HEIGHT.toDp() }
    val titleHeight = with(density) { (TITLE_LINE_HEIGHT * if (compact) 1 else 2).toDp() }
    val overviewHeight = with(density) { (OVERVIEW_LINE_HEIGHT * if (compact) 2 else 3).toDp() }
    Column(modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.height(eyebrowHeight), contentAlignment = Alignment.CenterStart) { eyebrow() }
        Box(Modifier.height(titleHeight).fillMaxWidth(), contentAlignment = Alignment.TopStart) { title() }
        Row(
            Modifier.height(ScoreRingSize),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            content = meta,
        )
        Box(Modifier.height(overviewHeight), contentAlignment = Alignment.TopStart) { overview() }
    }
}

/** Line heights of the hero text; [TITLE_LINE_HEIGHT] is a short (60 sp) title's, the tallest title line. */
private val EYEBROW_LINE_HEIGHT = 18.sp
private val TITLE_LINE_HEIGHT = 62.sp
private val OVERVIEW_LINE_HEIGHT = 24.sp
