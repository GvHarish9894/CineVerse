package com.techgv.cineverse.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

private val Secondary62 = Tokens.Colors.TextPrimary.copy(alpha = 0.62f)

@Composable
private fun CardTexts(title: String, subtitle: String?, center: Boolean = false, web: Boolean = LocalShellStyle.current == ShellStyle.Web) {
    val align = if (center) TextAlign.Center else TextAlign.Start
    Text(
        title,
        style = if (web) bodyStyle(15.sp, FontWeight.Bold) else bodyStyle(13.sp, FontWeight.SemiBold),
        textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
    )
    if (subtitle != null) {
        Text(
            subtitle, style = bodyStyle(if (web) 13.sp else 11.5.sp), color = Secondary62,
            textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

/** Initials for person art without a photo (`initials()` in the prototypes). */
fun initialsOf(name: String): String = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

@Composable
private fun BoxScope.Initials(name: String, size: androidx.compose.ui.unit.TextUnit) {
    Text(
        initialsOf(name),
        style = displayStyle(size).merge(TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.4f), blurRadius = 14f))),
        color = Color.White,
        modifier = Modifier.align(Alignment.Center),
    )
}

@Composable
private fun PlayDisc(diameter: Dp, iconSize: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(diameter).clip(CircleShape).background(Tokens.Colors.Background.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Icon(CvIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

/** Mobile `.lc`: 250×141 landscape art (title on the art) + title/subtitle; web picks reuse it at their width. */
@Composable
fun LandscapeCard(
    title: String,
    subtitle: String?,
    palette: ArtPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    width: Dp = Tokens.Size.MobileLandscape.width,
) {
    val shape = RoundedCornerShape(Tokens.Radius.Card)
    Column(modifier.width(width).cvClickable(onClick, onClickLabel = title, focusShape = shape)) {
        FilledArt(
            imageUrl, palette, ArtStyle.Backdrop, shape,
            Modifier.fillMaxWidth().aspectRatio(250f / 141f).dropShadow(shape, cardGlow(palette.c1, 0f)),
        ) {
            Text(
                title, style = displayStyle(17.sp, trackingEm = 0f), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        CardTexts(title, subtitle, web = false)
    }
}

/** Mobile `.pp`: round portrait (92dp rows, 96dp grids) with initials fallback, name + role. */
@Composable
fun PersonCircle(
    name: String,
    subtitle: String?,
    palette: ArtPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    diameter: Dp = Tokens.Size.MobilePerson,
) {
    Column(modifier.width(diameter).cvClickable(onClick, onClickLabel = name, focusShape = CircleShape), horizontalAlignment = Alignment.CenterHorizontally) {
        PaletteArt(imageUrl, palette, ArtStyle.Person, Modifier.size(diameter).dropShadow(CircleShape, cardGlow(palette.c1, 0f)).clip(CircleShape)) {
            Initials(name, 30.sp)
        }
        Spacer(Modifier.height(8.dp))
        CardTexts(name, subtitle, center = true, web = false)
    }
}

/** Web `.pcard`: 4:5 portrait card (156 wide), lift 5dp + scale 1.03 + coloured glow on hover/focus. */
@Composable
fun PersonCard(
    name: String,
    subtitle: String?,
    palette: ArtPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    width: Dp = Tokens.Size.WebPersonCard.width,
) {
    val shape = RoundedCornerShape(16.dp)
    val source = remember { MutableInteractionSource() }
    val active by source.collectIsActiveAsState()
    val t by animateFloatAsState(if (active) 1f else 0f, tween(300, easing = EmphasizedEasing), label = "personLift")
    val lift = if (LocalReduceMotion.current) 0f else t
    Column(modifier.width(width).cvClickable(onClick, onClickLabel = name, focusShape = shape, interactionSource = source)) {
        PaletteArt(
            imageUrl, palette, ArtStyle.Person,
            Modifier.fillMaxWidth().aspectRatio(4f / 5f)
                .graphicsLayer { translationY = -5.dp.toPx() * lift; scaleX = 1f + 0.03f * lift; scaleY = 1f + 0.03f * lift }
                .dropShadow(shape, cardGlow(palette.c1, t))
                .clip(shape),
        ) { Initials(name, 44.sp) }
        Spacer(Modifier.height(12.dp))
        CardTexts(name, subtitle, web = true)
    }
}

/** Mobile `.top10`: a 120sp outlined rank overlapped by a 112×168 poster. */
@Composable
fun Top10Card(rank: Int, title: String, palette: ArtPalette, onClick: () -> Unit, modifier: Modifier = Modifier, imageUrl: String? = null) {
    val shape = RoundedCornerShape(Tokens.Radius.Card)
    Row(
        modifier.width(170.dp).height(186.dp)
            .cvClickable(onClick, onClickLabel = "Number $rank: $title", focusShape = shape)
            .semantics(mergeDescendants = true) { contentDescription = "Number $rank: $title" },
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            "$rank",
            style = displayStyle(120.sp, trackingEm = -0.06f).merge(TextStyle(drawStyle = Stroke(width = 4f), lineHeight = 94.sp)),
            color = Tokens.Colors.TextPrimary.copy(alpha = 0.55f),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.clearAndSetSemantics { },
        )
        PaletteArt(
            imageUrl, palette, ArtStyle.Poster,
            Modifier.offset(x = (-26).dp).size(112.dp, 168.dp).dropShadow(shape, cardGlow(palette.c1, 0f)).clip(shape),
            bottomScrim = true,
        ) { PosterTitleOverlay(title, 15.sp, 10.dp, 10.dp) }
    }
}

/** Mobile `.vcard` (350 wide, radius 18, 46dp play disc) / web `.vc` (340, radius 16, 58dp disc, hover scale 1.03). */
@Composable
fun VideoCard(
    title: String,
    subtitle: String?,
    palette: ArtPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    width: Dp = if (LocalShellStyle.current == ShellStyle.Web) Tokens.Size.WebVideo.width else 350.dp,
) {
    val web = LocalShellStyle.current == ShellStyle.Web
    val shape = RoundedCornerShape(if (web) 16.dp else 18.dp)
    val source = remember { MutableInteractionSource() }
    val active by source.collectIsActiveAsState()
    val t by animateFloatAsState(if (active && !LocalReduceMotion.current) 1f else 0f, tween(300, easing = EmphasizedEasing), label = "videoHover")
    Column(modifier.width(width).cvClickable(onClick, haptic = HapticKind.Select, onClickLabel = "Play $title", focusShape = shape, interactionSource = source)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).graphicsLayer { scaleX = 1f + 0.03f * t; scaleY = 1f + 0.03f * t }) {
            FilledArt(imageUrl, palette, ArtStyle.Backdrop, shape)
            PlayDisc(
                if (web) 58.dp else 46.dp, if (web) 28.dp else 22.dp,
                Modifier.align(Alignment.Center).graphicsLayer { scaleX = 1f + 0.12f * t; scaleY = 1f + 0.12f * t },
            )
        }
        Spacer(Modifier.height(if (web) 12.dp else 10.dp))
        CardTexts(title, subtitle, center = web, web = web)
    }
}

/**
 * Episode row: still with play glyph, "S1 · E3", name, "date · runtime · ★", 2-line overview (mobile `.ep` / web `.ep`).
 * Web wraps like the kit's `.ep` flex-wrap: the 240dp still sits above the body when the 300dp body basis no longer
 * fits beside it, and spans the full width under the ≤ 640 breakpoint (`.ep-art{width:100%}`).
 */
@Composable
fun EpisodeCard(
    code: String,
    name: String,
    meta: String,
    overview: String?,
    palette: ArtPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
) {
    val web = LocalShellStyle.current == ShellStyle.Web
    val shape = RoundedCornerShape(if (web) 20.dp else 18.dp)
    val phone = LocalWindowClass.current == WindowClass.Phone
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = web && (phone || maxWidth < 16.dp * 2 + 240.dp + 22.dp + 300.dp)
        val container = Modifier.fillMaxWidth()
            .cvClickable(onClick, haptic = HapticKind.Select, onClickLabel = "Play $name", focusShape = shape)
            .clip(shape).background(Color.White.copy(alpha = 0.05f))
            .then(if (web) Modifier.border(1.dp, Color.White.copy(alpha = 0.06f), shape) else Modifier)
            .padding(if (web) 16.dp else 10.dp)
        val art = @Composable { artModifier: Modifier ->
            Box(artModifier.aspectRatio(if (web) 16f / 9f else 132f / 74f)) {
                FilledArt(imageUrl, palette, ArtStyle.Backdrop, RoundedCornerShape(if (web) 14.dp else 12.dp))
                PlayDisc(34.dp, 16.dp, Modifier.align(Alignment.Center))
            }
        }
        val body = @Composable { bodyModifier: Modifier ->
            Column(bodyModifier) {
                Text(code, style = if (web) displayStyle(22.sp) else bodyStyle(11.sp, FontWeight.Bold, trackingEm = 0.08f), color = Tokens.Colors.TextPrimary.copy(alpha = if (web) 0.5f else 0.64f))
                Text(name, style = bodyStyle(if (web) 18.sp else 14.5.sp, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                Text(meta, style = bodyStyle(if (web) 13.5.sp else 12.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.66f), modifier = Modifier.padding(top = 3.dp))
                if (overview != null) {
                    Text(
                        overview, style = bodyStyle(if (web) 15.sp else 12.5.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.78f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (stacked) {
            Column(container, verticalArrangement = Arrangement.spacedBy(22.dp)) {
                art(if (phone) Modifier.fillMaxWidth() else Modifier.width(240.dp))
                body(Modifier.fillMaxWidth())
            }
        } else {
            Row(container, horizontalArrangement = Arrangement.spacedBy(if (web) 22.dp else 14.dp)) {
                art(Modifier.width(if (web) 240.dp else 132.dp))
                body(Modifier.weight(1f))
            }
        }
    }
}

/** Review: avatar initials, author, date, gold score, text (mobile `.rev` / web `.rev`). */
@Composable
fun ReviewCard(author: String, date: String, rating: Double?, text: String, modifier: Modifier = Modifier, maxLines: Int = 6) {
    val web = LocalShellStyle.current == ShellStyle.Web
    val shape = RoundedCornerShape(if (web) 20.dp else 18.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = if (web) 0.05f else 0.06f))
            .then(if (web) Modifier.border(1.dp, Color.White.copy(alpha = 0.06f), shape) else Modifier)
            .padding(if (web) 22.dp else 16.dp)
            .semantics(mergeDescendants = true) { },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (web) 12.dp else 10.dp)) {
            Box(Modifier.size(if (web) 44.dp else 34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Text(initialsOf(author), style = bodyStyle(12.sp, FontWeight.Bold))
            }
            Column(Modifier.weight(1f)) {
                Text(author, style = bodyStyle(14.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(date, style = bodyStyle(12.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.64f))
            }
            if (rating != null) GoldBadge("★ ${oneDecimal(rating)}")
        }
        Text(
            text, style = bodyStyle(if (web) 15.5.sp else 14.sp).copy(lineHeight = if (web) 24.8.sp else 21.sp),
            color = Tokens.Colors.TextPrimary.copy(alpha = 0.86f), maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (web) 12.dp else 10.dp),
        )
    }
}

/** `.rev-score` / rated badge: gold pill with dark text. */
@Composable
fun GoldBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = bodyStyle(12.sp, FontWeight.Bold), color = Tokens.Colors.OnGold,
        modifier = modifier.background(Tokens.Colors.Gold, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** One decimal, locale-free (8.0, 7.5): the kits' rating format. */
fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toInt()
    return "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
}

/** Mobile `.fact` tile (uppercase label + value) / web aside fact (label + value, no tile). */
@Composable
fun FactTile(label: String, value: String, modifier: Modifier = Modifier) {
    val web = LocalShellStyle.current == ShellStyle.Web
    Column(
        modifier
            .then(if (web) Modifier else Modifier.clip(RoundedCornerShape(Tokens.Radius.Tile)).background(Tokens.Colors.Tile).padding(horizontal = 14.dp, vertical = 12.dp))
            .semantics(mergeDescendants = true) { },
    ) {
        Text(
            if (web) label else label.uppercase(),
            style = if (web) bodyStyle(13.sp, FontWeight.Bold) else bodyStyle(10.5.sp, FontWeight.Bold, trackingEm = 0.08f),
            color = Tokens.Colors.TextPrimary.copy(alpha = if (web) 0.62f else 0.6f),
        )
        Text(value, style = if (web) bodyStyle(15.5.sp) else bodyStyle(14.sp, FontWeight.SemiBold), modifier = Modifier.padding(top = if (web) 3.dp else 4.dp))
    }
}
