package com.techgv.cineverse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.ui.adaptive.LocalShellStyle
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.LocalReduceMotion
import com.techgv.cineverse.ui.theme.bodyStyle
import com.techgv.cineverse.ui.theme.displayStyle
import kotlinx.coroutines.launch

/**
 * Horizontal gutter: mobile 20dp; web 32dp (16 ≤ 640) aligned to the centred 1320dp column, so rows "start
 * aligned with the content column and bleed to the window edge" (`max(32px, (100% - 1320px)/2 + 32px)`).
 */
fun contentGutter(shell: ShellStyle, availableWidth: Dp): Dp = when (shell) {
    ShellStyle.Mobile -> Tokens.Spacing.MobileGutter
    ShellStyle.Web -> {
        val base = if (availableWidth <= 640.dp) Tokens.Spacing.WebGutterPhone else Tokens.Spacing.WebGutter
        max(base, (availableWidth - Tokens.Spacing.WebMaxWidth) / 2 + base)
    }
}

/** Row/section title: mobile `.row-t` 20/700, web `.sec-t` 26/800 display. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val web = LocalShellStyle.current == ShellStyle.Web
    Text(
        text,
        style = if (web) displayStyle(Tokens.Typography.ScaleWeb.SectionTitle) else displayStyle(Tokens.Typography.ScaleMobile.RowTitle, FontWeight.Bold, -0.01f),
        modifier = modifier.semantics { heading() },
    )
}

/**
 * Web section header (`.sec-h`): display title, [trailing] inline right after it (e.g. a [SegmentedControl]), and the
 * ←/→ round buttons at the right edge when [onScrollLeft] and [onScrollRight] are given. It wraps like the kit's
 * `flex-wrap`: on a phone the arrows drop to their own line (still right-aligned) instead of squeezing the title.
 */
@Composable
fun WebSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onScrollLeft: (() -> Unit)? = null,
    onScrollRight: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        SectionTitle(title)
        trailing()
        if (onScrollLeft != null && onScrollRight != null) {
            // 48dp touch targets around 40dp circles: the visible gap is the kit's 8dp.
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(CvIcons.ChevronLeft, "Scroll left", onScrollLeft, container = Color.White.copy(alpha = 0.08f))
                RoundIconButton(CvIcons.ChevronRight, "Scroll right", onScrollRight, container = Color.White.copy(alpha = 0.08f))
            }
        }
    }
}

/**
 * Titled horizontal row (`.row` / `.sec` + `.hs`): snap-to-start, content padded by the gutter, [headerTrailing] for a
 * segmented control or "See all". Web adds ←/→ buttons that scroll ~80% of the visible width (380 ms).
 */
@Composable
fun ScrollRow(
    title: String,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    itemSpacing: Dp = if (LocalShellStyle.current == ShellStyle.Web) 20.dp else Tokens.Spacing.CardGap,
    headerTrailing: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val shell = LocalShellStyle.current
    val web = shell == ShellStyle.Web
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gutter = contentGutter(shell, maxWidth)
        val pagePx = with(LocalDensity.current) { (maxWidth - gutter * 2).toPx() * 0.8f }
        Column {
            if (web) {
                fun scrollBy(px: Float) = scope.launch {
                    if (reduceMotion) state.scrollBy(px) else state.animateScrollBy(px, Tokens.Motion.RowScroll.tween())
                }
                WebSectionHeader(title, Modifier.padding(horizontal = gutter).padding(bottom = 14.dp), { scrollBy(-pagePx) }, { scrollBy(pagePx) }, headerTrailing)
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = gutter).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionTitle(title, Modifier.weight(1f, fill = false))
                    headerTrailing()
                }
            }
            LazyRow(
                state = state,
                contentPadding = PaddingValues(start = gutter, end = gutter, top = if (web) 10.dp else 4.dp, bottom = if (web) 26.dp else 10.dp),
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
                content = content,
            )
        }
    }
}

/** Web `.panel`: white 5% rounded box with an optional uppercase title (filters, settings, person info). */
@Composable
fun Panel(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.clip(shape).background(Color.White.copy(alpha = 0.05f)).border(1.dp, Color.White.copy(alpha = 0.06f), shape).padding(20.dp)) {
        if (title != null) {
            Text(
                title.uppercase(), style = bodyStyle(12.sp, FontWeight.Bold, trackingEm = 0.1f), color = Tokens.Colors.TextPrimary.copy(alpha = 0.62f),
                modifier = Modifier.padding(bottom = 12.dp).semantics { heading() },
            )
        }
        content()
    }
}

/** Logo mark: accent → violet gradient tile with the play glyph (`.logo-mark`). */
@Composable
fun LogoMark(modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 11 / 36))
            .background(Brush.linearGradient(listOf(Tokens.Colors.Accent, Tokens.Colors.Secondary))),
        contentAlignment = Alignment.Center,
    ) { Icon(CvIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 16 / 36)) }
}

const val TMDB_ATTRIBUTION = "This product uses the TMDB API but is not endorsed or certified by TMDB."

@Immutable
data class FooterLink(val label: String, val onClick: () -> Unit)

/** The web footer's Browse / You columns, provided once by the app root so every page's [Footer] shows them. */
@Immutable
data class FooterLinks(val browse: List<FooterLink> = emptyList(), val you: List<FooterLink> = emptyList())

val LocalFooterLinks = staticCompositionLocalOf { FooterLinks() }

/**
 * Footer with the required TMDB attribution. Web: top hairline, logo + one-liner, Browse / You link columns (default:
 * [LocalFooterLinks]), attribution note.
 * Mobile: the centred attribution line only (`.attrib`).
 */
@Composable
fun Footer(modifier: Modifier = Modifier, browse: List<FooterLink> = LocalFooterLinks.current.browse, you: List<FooterLink> = LocalFooterLinks.current.you) {
    if (LocalShellStyle.current == ShellStyle.Mobile) {
        Text(
            "Data and images from TMDB · $TMDB_ATTRIBUTION",
            style = bodyStyle(12.sp).copy(lineHeight = 18.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.55f), textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        )
        return
    }
    BoxWithConstraints(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.02f), Color.White.copy(alpha = 0.04f))))
            .drawBehind { drawLine(Color.White.copy(alpha = 0.06f), Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(top = 48.dp, bottom = 60.dp),
    ) {
        val gutter = contentGutter(ShellStyle.Web, maxWidth)
        Column(Modifier.padding(horizontal = gutter)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(40.dp)) {
                Column(Modifier.widthIn(max = 360.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LogoMark()
                        Text("CineVerse", style = displayStyle(22.sp))
                    }
                    FooterNote("Movies, TV and the people who make them — on your TV, phone and the web.", Modifier.padding(top = 14.dp))
                }
                FooterColumn("Browse", browse)
                FooterColumn("You", you)
            }
            FooterNote(TMDB_ATTRIBUTION, Modifier.padding(top = 28.dp))
        }
    }
}

@Composable
private fun FooterColumn(title: String, links: List<FooterLink>) {
    if (links.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title.uppercase(), style = bodyStyle(13.sp, FontWeight.Bold, trackingEm = 0.1f), color = Color.White, modifier = Modifier.semantics { heading() })
        links.forEach { link ->
            Text(
                link.label, style = bodyStyle(14.5.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.78f),
                modifier = Modifier.cvClickable(link.onClick, focusShape = RoundedCornerShape(6.dp), pressedScale = 1f),
            )
        }
    }
}

@Composable
private fun FooterNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = bodyStyle(13.sp).copy(lineHeight = 21.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.55f), modifier = modifier.widthIn(max = 720.dp))
}

/** Empty state (`.empty`): concentric rings illustration, title, message, optional action. */
@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val web = LocalShellStyle.current == ShellStyle.Web
    Column(
        modifier.fillMaxWidth()
            .then(if (web) Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.04f)).padding(vertical = 50.dp, horizontal = 20.dp) else Modifier.padding(top = 40.dp, start = 20.dp, end = 20.dp))
            .semantics(mergeDescendants = true) { },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val art = if (web) 140.dp else 150.dp
        Box(Modifier.size(art), contentAlignment = Alignment.Center) {
            listOf(1f, 0.68f, 0.36f).forEachIndexed { i, f ->
                Box(Modifier.size(art * f).offset(x = (i * 6).dp, y = (-i * 4).dp).border(2.dp, Color.White.copy(alpha = 0.18f), CircleShape))
            }
        }
        Text(title, style = displayStyle(if (web) 26.sp else 22.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
        Text(
            message, style = bodyStyle(if (web) 15.5.sp else 14.5.sp).copy(lineHeight = if (web) 23.sp else 22.sp),
            color = Tokens.Colors.TextPrimary.copy(alpha = 0.72f), textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 440.dp),
        )
        if (action != null) Box(Modifier.padding(top = 18.dp)) { action() }
    }
}

/** Inline error + retry for a section (both kits' "States to add"). */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(Tokens.Colors.Danger.copy(alpha = 0.10f)).border(1.dp, Tokens.Colors.Danger.copy(alpha = 0.35f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = bodyStyle(14.sp), modifier = Modifier.weight(1f))
        PillButton("Retry", onRetry, variant = ButtonVariant.Ghost, small = true)
    }
}
