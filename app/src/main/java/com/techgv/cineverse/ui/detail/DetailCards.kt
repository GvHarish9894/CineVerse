package com.techgv.cineverse.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.techgv.cineverse.data.tmdb.formatDate
import com.techgv.cineverse.data.tmdb.parseDate
import com.techgv.cineverse.data.tmdb.yearOf
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.ui.components.DetailCardArt
import com.techgv.cineverse.ui.components.DetailCardSurface
import com.techgv.cineverse.ui.components.FocusRestorer
import com.techgv.cineverse.ui.components.FocusRow
import com.techgv.cineverse.ui.components.PinToStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

// Interim private versions of the shared §6 cards / controls (PosterCard, PersonCard, LandscapeCard,
// ScoreRing, Chip, ActionButton) used by Detail, Person and Season until the browse lane's land.

internal val SCREEN_INSET = 48.dp

/** Where a focused row's cards land below the viewport top: leaves its title visible. */
internal val ROW_TOP_INSET = 80.dp

/** Title + subtitle lines under a poster / person / season card. */
private val CARD_LABELS_HEIGHT = 56.dp

/** §6 PosterCard 140×210 (step 156). [modifier] goes on the focusable. */
@Composable
internal fun Lane2PosterCard(title: Title, posterUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(Modifier.width(140.dp)) {
        DetailCardSurface(onClick = onClick, modifier = modifier.size(140.dp, 210.dp).describedAs(title.title)) {
            DetailCardArt(posterUrl, title.title, Modifier.fillMaxSize())
            if (title.mediaType == MediaType.Tv) {
                Text(
                    "TV",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        CardLabel(title.title, posterSubtitle(title, LocalDate.now()))
    }
}

/** §6: "year · ★ 7.8"; unrated → "Coming {date}" when the release is in the future, else "Unrated". */
internal fun posterSubtitle(title: Title, today: LocalDate): String {
    if (title.voteCount > 0) return listOfNotNull(yearOf(title.releaseDate), "★ " + oneDecimal(title.voteAverage)).joinToString(" · ")
    val release = parseDate(title.releaseDate)
    return if (release != null && release.isAfter(today)) "Coming ${formatDate(release)}" else "Unrated"
}

/** §6 PersonCard: 132 circle (step 148), name, role. [modifier] goes on the focusable. */
@Composable
internal fun Lane2PersonCard(name: String, role: String, profileUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(Modifier.width(132.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        DetailCardSurface(onClick = onClick, shape = CircleShape, modifier = modifier.size(132.dp).describedAs(name, role)) {
            DetailCardArt(profileUrl, name, Modifier.fillMaxSize(), initials = true)
        }
        CardLabel(name, role, center = true)
    }
}

/** §6 LandscapeCard 248×140 (step 264). [modifier] goes on the focusable. */
@Composable
internal fun Lane2LandscapeCard(title: String, subtitle: String, imageUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(Modifier.width(248.dp)) {
        DetailCardSurface(onClick = onClick, modifier = modifier.size(248.dp, 140.dp).describedAs(title, subtitle)) {
            DetailCardArt(imageUrl, title, Modifier.fillMaxSize())
        }
        CardLabel(title, subtitle)
    }
}

/** The label sits below the focusable card, so the card itself carries it for TalkBack (and tests). */
internal fun Modifier.describedAs(vararg lines: String): Modifier =
    semantics { contentDescription = lines.filter { it.isNotBlank() }.joinToString(", ") }

@Composable
private fun CardLabel(title: String, subtitle: String, center: Boolean = false) {
    val align = if (center) TextAlign.Center else TextAlign.Start
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    if (subtitle.isNotEmpty()) {
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** §6 ScoreRing 48: `vote_average×10`, green ≥70, yellow ≥40, else red; caller hides it when `vote_count == 0`. */
@Composable
internal fun DetailScoreRing(voteAverage: Double, modifier: Modifier = Modifier) {
    val score = (voteAverage * 10).roundToInt().coerceIn(0, 100)
    val color = when {
        score >= 70 -> Color(0xFF22C55E)
        score >= 40 -> Color(0xFFFACC15)
        else -> Color(0xFFEF4444)
    }
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.2f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * score / 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        }
        Text("$score", style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

/** §6 Chip (h 44), non-interactive: hero metadata. */
@Composable
internal fun DetailChip(label: String) {
    Box(
        Modifier.height(44.dp).background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp)).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/**
 * §6 ActionButton (h 56): glyph + label; [primary] solid, [on] filled. Disabled (e.g. a pending library write)
 * it stays focusable, ignores OK and is dimmed, glyph included. [modifier] goes on the focusable.
 */
@Composable
internal fun DetailActionButton(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    on: Boolean = false,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val (background, content) = when {
        focused -> Color.White to Color.Black
        primary -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        on -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) to Color.White
        else -> Color.White.copy(alpha = 0.12f) to Color.White
    }
    DetailCardSurface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(28.dp),
        enabled = enabled,
        containerColor = background,
    ) {
        Row(
            Modifier.height(56.dp).padding(horizontal = 22.dp).alpha(if (enabled) 1f else 0.5f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(glyph, style = MaterialTheme.typography.titleMedium, color = content)
            Text(label, style = MaterialTheme.typography.titleSmall, color = content, maxLines = 1)
        }
    }
}

/**
 * A titled horizontal row (§1.4): LazyRow pinned to the start inset, with per-row focus memory
 * (entering from another row lands on the remembered item, else `min(savedIndex, lastIndex)`).
 */
@Composable
internal fun DetailRow(
    title: String,
    restorer: FocusRestorer,
    focusRow: FocusRow,
    state: LazyListState,
    spacing: Int = 16,
    content: LazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = SCREEN_INSET, bottom = 4.dp),
        )
        PinToStart(SCREEN_INSET) {
            LazyRow(
                state = state,
                modifier = with(restorer) { Modifier.restoreRowFocus(focusRow, scope) },
                contentPadding = PaddingValues(horizontal = SCREEN_INSET, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing.dp),
                content = content,
            )
        }
    }
}

/**
 * Wrap the outer vertical list: it scrolls only when the focused child is not fully visible, then puts its
 * top [topInset] below the viewport top (room for the row title). The TV default pivot would scroll even
 * the hero's buttons to 30 % and cut the title off. Rows inside keep their own [PinToStart].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RevealVertically(topInset: Dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val insetPx = with(density) { topInset.toPx() }
    val labelsPx = with(density) { CARD_LABELS_HEIGHT.toPx() }
    val spec = remember(insetPx, labelsPx) {
        object : BringIntoViewSpec {
            // The focused card's labels sit below it: they must fit too.
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) =
                if (offset >= 0f && offset + size + labelsPx <= containerSize) 0f else offset - insetPx
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** Focus entering the hero (outer item 0) shows all of it; the TV pivot alone would cut the title off. */
internal fun Modifier.revealHeroOnFocus(outer: LazyListState, scope: CoroutineScope, reduceMotion: Boolean): Modifier =
    onFocusChanged { if (it.hasFocus) scope.launch { if (reduceMotion) outer.scrollToItem(0) else outer.animateScrollToItem(0) } }

internal fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
