package com.techgv.cineverse.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.ui.components.formatRating
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.absoluteValue

/** §6 card line: "year · ★ 7.8", "Coming {date}" for a future release, else "Unrated" without votes. */
internal fun posterSubline(title: Title, today: LocalDate = LocalDate.now()): String {
    val date = title.releaseDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (date != null && date.isAfter(today)) return "Coming ${date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))}"
    if (title.voteCount == 0) return "Unrated"
    return listOfNotNull(date?.year?.toString(), "★ ${formatRating(title.voteAverage)}").joinToString(" · ")
}

/**
 * Lane-local PosterCard (§6: 140×210, poster `w342`, title, sub-line, TV tag, badge); the shared
 * ui/components PosterCard replaces it at merge.
 */
@Composable
fun LibraryPosterCard(title: Title, badge: String?, imageBaseUrl: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(Modifier.width(140.dp)) {
        Card(
            onClick = onClick,
            modifier = modifier.size(140.dp, 210.dp),
            scale = CardDefaults.scale(focusedScale = 1.08f),
        ) {
            Box(Modifier.fillMaxSize()) {
                val hue = (title.title.hashCode().absoluteValue % 360).toFloat()
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(Color.hsv(hue, 0.55f, 0.45f), Color.hsv((hue + 50) % 360, 0.6f, 0.2f))),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(title.title, style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.padding(8.dp), maxLines = 4)
                }
                title.posterPath?.let {
                    AsyncImage(model = "${imageBaseUrl}w342$it", contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (title.mediaType == MediaType.Tv) Tag("TV", Modifier.align(Alignment.TopStart))
                badge?.let { Tag(it, Modifier.align(Alignment.TopEnd)) }
            }
        }
        Text(
            title.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(posterSubline(title), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun Tag(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier.padding(6.dp).background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
