package com.techgv.cineverse.ui.detail

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
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
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

// Interim private versions of the shared §6 cards / controls (PosterCard, PersonCard, LandscapeCard,
// ScoreRing, Chip, ActionButton) used by Detail, Person and Season until the browse lane's land.

internal val SCREEN_INSET = 48.dp

/** §6 PosterCard 140×210 (step 156). [modifier] goes on the focusable. */
@Composable
internal fun Lane2PosterCard(title: Title, posterUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(Modifier.width(140.dp)) {
        DetailCardSurface(onClick = onClick, modifier = modifier.size(140.dp, 210.dp)) {
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
        DetailCardSurface(onClick = onClick, shape = CircleShape, modifier = modifier.size(132.dp)) {
            DetailCardArt(profileUrl, name, Modifier.fillMaxSize(), initials = true)
        }
        CardLabel(name, role, center = true)
    }
}

/** §6 LandscapeCard 248×140 (step 264). [modifier] goes on the focusable. */
@Composable
internal fun Lane2LandscapeCard(title: String, subtitle: String, imageUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(Modifier.width(248.dp)) {
        DetailCardSurface(onClick = onClick, modifier = modifier.size(248.dp, 140.dp)) {
            DetailCardArt(imageUrl, title, Modifier.fillMaxSize())
        }
        CardLabel(title, subtitle)
    }
}

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

/** §6 ActionButton (h 56): glyph + label; [primary] solid, [on] filled. [modifier] goes on the focusable. */
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
    val content: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
    }
    val padding = PaddingValues(horizontal = 22.dp)
    if (primary || on) {
        Button(onClick = onClick, enabled = enabled, contentPadding = padding, modifier = modifier.height(56.dp), scale = ButtonDefaults.scale(focusedScale = 1.08f)) { content() }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = padding, modifier = modifier.height(56.dp)) { content() }
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

internal fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
