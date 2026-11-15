package com.techgv.cineverse.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.techgv.cineverse.data.tmdb.yearOf
import com.techgv.cineverse.domain.SeasonSummary

/** §6 SeasonCard 158×240 (step 174): poster, "Season n" / "Specials", "{episode_count} episodes · {year}". [modifier] goes on the focusable. */
@Composable
fun SeasonCard(season: SeasonSummary, posterUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val subtitle = listOfNotNull("${season.episodeCount} episodes", yearOf(season.airDate)).joinToString(" · ")
    Column(Modifier.width(SEASON_CARD_WIDTH)) {
        // The labels sit below the focusable card, so the card carries them for TalkBack.
        DetailCardSurface(onClick = onClick, modifier = modifier.size(SEASON_CARD_WIDTH, SEASON_CARD_HEIGHT).semantics { contentDescription = "${season.label}, $subtitle" }) {
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
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

val SEASON_CARD_WIDTH = 158.dp
val SEASON_CARD_HEIGHT = 240.dp

/** Height of the two label lines under a [SeasonCard]. */
@Composable
fun seasonLabelHeight(): Dp = with(LocalDensity.current) {
    (MaterialTheme.typography.titleSmall.lineHeight.toDp() + MaterialTheme.typography.bodySmall.lineHeight.toDp())
}
