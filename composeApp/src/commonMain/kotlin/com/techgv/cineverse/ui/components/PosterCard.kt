package com.techgv.cineverse.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow as BoxShadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.ui.adaptive.LocalShellStyle
import com.techgv.cineverse.ui.adaptive.LocalWindowClass
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.adaptive.WindowClass
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.haptics.HapticKind
import com.techgv.cineverse.ui.theme.LocalReduceMotion
import com.techgv.cineverse.ui.theme.bodyStyle
import com.techgv.cineverse.ui.theme.displayStyle

/** The web card's hover quick action (watchlist + / ✓). [active] = already on the list. */
@Immutable
data class QuickAction(val active: Boolean, val label: String, val enabled: Boolean = true, val onToggle: () -> Unit)

/** Poster width per kit: mobile 118, web 180 (140 under the web kit's ≤ 640 breakpoint). */
@Composable
@ReadOnlyComposable
fun defaultPosterWidth(): Dp = when {
    LocalShellStyle.current == ShellStyle.Mobile -> Tokens.Size.MobilePoster.width
    LocalWindowClass.current == WindowClass.Phone -> 140.dp
    else -> Tokens.Size.WebPoster.width
}

@Composable
@ReadOnlyComposable
internal fun posterRadius(): Dp = if (LocalShellStyle.current == ShellStyle.Mobile) Tokens.Radius.Card else 16.dp

/** Card glow (glow.restingCard → glow.hoveredCard) blended by [t]; the 1px/2px white ring is drawn by the caller. */
internal fun cardGlow(c1: Color, t: Float) = BoxShadow(
    radius = lerp(30.dp, 60.dp, t),
    spread = lerp((-16).dp, (-18).dp, t),
    color = c1,
    offset = DpOffset(0.dp, lerp(14.dp, 26.dp, t)),
)

/**
 * 2:3 poster card: art (image or palette art with the title), optional score ring, title + subtitle.
 * Touch: press-scale. Pointer/keyboard: lift 6dp + scale 1.03 (300 ms), coloured glow + white ring, quick action.
 */
@Composable
fun PosterCard(
    title: String,
    subtitle: String?,
    palette: ArtPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    voteAverage: Double = 0.0,
    voteCount: Int = 0,
    width: Dp = defaultPosterWidth(),
    showScoreRing: Boolean = LocalShellStyle.current == ShellStyle.Web,
    badge: String? = null,
    tag: String? = null,
    quickAction: QuickAction? = null,
) {
    val web = LocalShellStyle.current == ShellStyle.Web
    val radius = posterRadius()
    val shape = RoundedCornerShape(radius)
    val source = remember { MutableInteractionSource() }
    val active by source.collectIsActiveAsState()
    val reduceMotion = LocalReduceMotion.current
    val t by animateFloatAsState(if (active) 1f else 0f, tween(300, easing = EmphasizedEasing), label = "posterLift")
    val lift = if (reduceMotion) 0f else t
    val ring = showScoreRing && voteCount > 0

    Box(modifier.width(width)) {
        Column(Modifier.fillMaxWidth().cvClickable(onClick, onClickLabel = title, focusShape = shape, interactionSource = source)) {
            Box(
                Modifier
                    .graphicsLayer {
                        translationY = -6.dp.toPx() * lift
                        scaleX = 1f + 0.03f * lift
                        scaleY = 1f + 0.03f * lift
                    },
            ) {
                PaletteArt(
                    imageUrl = imageUrl,
                    palette = palette,
                    style = ArtStyle.Poster,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .dropShadow(shape, cardGlow(palette.c1, t))
                        .border(if (t > 0f) 2.dp else 1.dp, Color.White.copy(alpha = if (t > 0f) 0.85f * t else 0.06f), shape)
                        .clip(shape),
                    bottomScrim = true,
                ) { PosterTitleOverlay(title, if (web) 21.sp else 15.sp, if (web) 14.dp else 10.dp, if (web && ring) 24.dp else 10.dp) }
                if (tag != null || badge != null) PosterLabel(tag, badge)
                if (ring) ScoreRing(voteAverage, voteCount, Modifier.align(Alignment.BottomStart).offset(10.dp, 18.dp), ScoreRingSize.Mini)
            }
            Spacer(Modifier.height(if (ring) 28.dp else 8.dp))
            Text(
                title,
                style = if (web) bodyStyle(15.sp, FontWeight.Bold) else bodyStyle(13.sp, FontWeight.SemiBold),
                maxLines = if (web) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = bodyStyle(if (web) 13.sp else 11.5.sp),
                    color = Tokens.Colors.TextPrimary.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (quickAction != null) QuickActionButton(quickAction, cardActive = active, lift = lift, reduceMotion = reduceMotion)
    }
}

/** 38dp disc inside a 48dp target (5dp + 5dp = the kit's 10dp inset); focus ring and click live on the target. */
@Composable
private fun BoxScope.QuickActionButton(action: QuickAction, cardActive: Boolean, lift: Float, reduceMotion: Boolean) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    AnimatedVisibility(
        visible = cardActive || focused || hovered || action.active,
        enter = if (reduceMotion) fadeIn(tween(200)) else fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.8f),
        exit = if (reduceMotion) fadeOut(tween(200)) else fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.8f),
        modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).graphicsLayer { translationY = -6.dp.toPx() * lift },
    ) {
        Box(
            Modifier
                .sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
                .cvClickable(action.onToggle, haptic = HapticKind.Select, onClickLabel = action.label, focusShape = CircleShape, enabled = action.enabled, interactionSource = source)
                .semantics { contentDescription = action.label },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(38.dp).disabledAlpha(action.enabled).clip(CircleShape)
                    .background(if (action.active) Tokens.Colors.Accent else Tokens.Colors.Background.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (action.active) CvIcons.Check else CvIcons.Plus,
                    contentDescription = null,
                    tint = if (action.active) Tokens.Colors.OnAccent else Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Fallback-art title (`.pt`): display 800, bottom-left, with a soft shadow. */
@Composable
internal fun BoxScope.PosterTitleOverlay(title: String, size: androidx.compose.ui.unit.TextUnit, side: Dp, bottom: Dp) {
    Text(
        title,
        style = displayStyle(size, trackingEm = -0.01f).merge(TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.55f), blurRadius = 12f), lineHeight = size * 1.02f)),
        color = Color.White,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.align(Alignment.BottomStart).padding(start = side, end = side, bottom = bottom),
    )
}

/** `.ptag` (TV, dark, top-left) and `.pbadge` (gold date/rating, top-right on mobile, top-left on web). */
@Composable
private fun BoxScope.PosterLabel(tag: String?, badge: String?) {
    val web = LocalShellStyle.current == ShellStyle.Web
    if (tag != null) {
        Text(
            tag,
            style = bodyStyle(if (web) 10.5.sp else 9.5.sp, FontWeight.Bold, trackingEm = 0.08f),
            color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(if (web) 10.dp else 7.dp)
                .background(Tokens.Colors.Background.copy(alpha = 0.72f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
    if (badge != null) {
        Text(
            badge,
            style = bodyStyle(if (web) 11.5.sp else 10.5.sp, FontWeight.Bold),
            color = Tokens.Colors.OnGold,
            modifier = Modifier.align(if (web) Alignment.TopStart else Alignment.TopEnd).padding(if (web) 10.dp else 7.dp)
                .background(Tokens.Colors.Gold, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/** Fills the parent with a gradient scrim + art: used by landscape/video/episode cards. */
@Composable
internal fun FilledArt(imageUrl: String?, palette: ArtPalette, style: ArtStyle, shape: RoundedCornerShape, modifier: Modifier = Modifier, fallback: @Composable BoxScope.() -> Unit = {}) {
    PaletteArt(imageUrl, palette, style, modifier.clip(shape).fillMaxSize(), bottomScrim = true, fallback = fallback)
}
