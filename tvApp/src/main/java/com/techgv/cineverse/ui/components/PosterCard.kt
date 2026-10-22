package com.techgv.cineverse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.domain.key
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

val POSTER_WIDTH = 140.dp
val POSTER_HEIGHT = 210.dp

/** Distance between neighbouring poster cards (§6 step 156). */
val POSTER_GAP = 16.dp

private val dateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

/** `yyyy-MM-dd` → date; null for null / blank / malformed. */
fun parseTmdbDate(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

fun formatTmdbDate(date: LocalDate): String = date.format(dateFormat)

/**
 * §6 poster sub-line: "Coming {date}" only for a future release; otherwise "{year} · ★ 7.8", or
 * "{year} · Unrated" when nobody voted. The year is omitted when the date is unknown.
 */
fun posterSubtitle(title: Title, today: LocalDate): String {
    val date = parseTmdbDate(title.releaseDate)
    if (date != null && date.isAfter(today)) return "Coming ${formatTmdbDate(date)}"
    val score = if (title.voteCount == 0) "Unrated" else "★ " + String.format(Locale.ENGLISH, "%.1f", title.voteAverage)
    return listOfNotNull(date?.year?.toString(), score).joinToString(" · ")
}

/** 140×210 poster + title + sub-line (§6); "TV" tag for series, optional [badge] (e.g. "★ 8.0"). */
@Composable
fun PosterCard(
    title: Title,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val url = tmdbImageUrl(title.posterPath, ImageSize.POSTER)
    val seed = title.ref.key
    val glow = if (focused) rememberPaletteColor(url, fallbackColor(seed)) else fallbackColor(seed)
    val subtitle = remember(title) { posterSubtitle(title, LocalDate.now()) }
    val description = listOfNotNull(title.title, if (title.mediaType == MediaType.Tv) "TV series" else null, subtitle, badge)
        .joinToString(", ")
    Column(modifier.width(POSTER_WIDTH)) {
        FocusSurface(
            onClick = onClick,
            shape = CardShape,
            glowColor = glow,
            modifier = Modifier
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = description }
                .size(POSTER_WIDTH, POSTER_HEIGHT),
        ) {
            TmdbImage(url, seed, Modifier.fillMaxSize()) {
                Text(
                    title.title,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 18.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (title.mediaType == MediaType.Tv) Tag("TV", Modifier.align(Alignment.TopStart))
            if (badge != null) Tag(badge, Modifier.align(Alignment.TopEnd), background = UiColors.Badge, color = Color(0xFF1A1205))
        }
        CardLabel(title.title, subtitle, focused)
    }
}

val CardShape = RoundedCornerShape(14.dp)

@Composable
private fun Tag(text: String, modifier: Modifier, background: Color = Color(0xB807060F), color: Color = Color.White) {
    Text(
        text,
        modifier = modifier.padding(8.dp).background(background, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
}

/** Title + sub-line under a card; brighter while the card is focused. */
@Composable
fun CardLabel(title: String, subtitle: String, focused: Boolean, modifier: Modifier = Modifier, centered: Boolean = false) {
    val align = if (centered) TextAlign.Center else null
    val width = if (centered) Modifier.fillMaxWidth() else Modifier
    Column(modifier.padding(top = 12.dp)) {
        Text(
            title,
            color = if (focused) Color.White else UiColors.TextDim,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = align,
            modifier = width,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                color = UiColors.TextFaint,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = align,
                modifier = Modifier.padding(top = 3.dp).then(width),
            )
        }
    }
}
