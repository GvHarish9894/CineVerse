package com.techgv.cineverse.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.techgv.cineverse.data.tmdb.yearOf
import com.techgv.cineverse.domain.SeasonSummary

/** §6 SeasonCard 158×240 (step 174): poster, "Season n" / "Specials", "{episode_count} episodes · {year}". [modifier] goes on the focusable. */
@Composable
fun SeasonCard(season: SeasonSummary, posterUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(Modifier.width(158.dp)) {
        DetailCardSurface(onClick = onClick, modifier = modifier.size(158.dp, 240.dp)) {
            DetailCardArt(posterUrl, season.label, Modifier.fillMaxSize())
        }
        Text(
            season.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull("${season.episodeCount} episodes", yearOf(season.airDate)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
