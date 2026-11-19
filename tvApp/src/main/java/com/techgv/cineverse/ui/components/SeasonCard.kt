package com.techgv.cineverse.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.data.tmdb.yearOf
import com.techgv.cineverse.domain.SeasonSummary

/**
 * §6 SeasonCard 158×240 (step 174): poster, "Season n" / "Specials", "{episode_count} episodes · {year}". [modifier] goes on the focusable.
 * The labels are a [CardLabel] like every other card: its top gap keeps them clear of the focused card's scale and border.
 */
@Composable
fun SeasonCard(season: SeasonSummary, posterUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val subtitle = listOfNotNull("${season.episodeCount} episodes", yearOf(season.airDate)).joinToString(" · ")
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(SEASON_CARD_WIDTH)) {
        // The labels sit below the focusable card, so the card carries them for TalkBack.
        DetailCardSurface(
            onClick = onClick,
            modifier = modifier
                .onFocusChanged { focused = it.isFocused }
                .size(SEASON_CARD_WIDTH, SEASON_CARD_HEIGHT)
                .semantics { contentDescription = "${season.label}, $subtitle" },
        ) {
            DetailCardArt(posterUrl, season.label, Modifier.fillMaxSize())
        }
        CardLabel(season.label, subtitle, focused)
    }
}

val SEASON_CARD_WIDTH = 158.dp
val SEASON_CARD_HEIGHT = 240.dp
