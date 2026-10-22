package com.techgv.cineverse.ui.season

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.domain.Detail
import com.techgv.cineverse.domain.Season
import com.techgv.cineverse.presentation.SavedStateHandleStateSaver
import com.techgv.cineverse.presentation.SeasonNotice
import com.techgv.cineverse.presentation.SeasonScreenState
import com.techgv.cineverse.presentation.SeasonStateHolder
import com.techgv.cineverse.presentation.SectionState
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.nav.Route
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

const val SEASON_NOT_AVAILABLE = "Season not available"

data class SeasonUiState(
    /** The TV detail this screen loads itself (cache hit from Detail, network after process death). */
    val show: SectionState<Detail> = SectionState.Loading,
    /** Selected season number; null while the show loads or when it has no seasons. */
    val selected: Int? = null,
    /** Episodes of [selected]. */
    val episodes: SectionState<Season> = SectionState.Loading,
    /** Episode number whose inline panel is open. */
    val openEpisode: Int? = null,
    /** One-shot toast text (§7.12), cleared by [SeasonViewModel.noticeShown]. */
    val notice: String? = null,
)

private fun SeasonScreenState.toUiState() = SeasonUiState(
    show, selected, episodes, openEpisode,
    notice = when (notice) {
        SeasonNotice.SeasonNotAvailable -> SEASON_NOT_AVAILABLE
        null -> null
    },
)

/** TV adapter over [SeasonStateHolder] (§7.6): focus memory and the notice copy. */
class SeasonViewModel(
    val route: Route.Season,
    handle: SavedStateHandle,
    tmdb: TmdbRepository,
    settings: Flow<Settings>,
    appGeneration: GenerationCounter,
) : ViewModel() {
    val focusMemory = FocusMemory(handle)
    private val holder = SeasonStateHolder(
        route.seriesId, route.seasonNumber, tmdb.catalog, settings, appGeneration, SavedStateHandleStateSaver(handle), viewModelScope,
    )
    val imageBase: String get() = holder.imageBase
    val state: StateFlow<SeasonUiState> =
        holder.state.map { it.toUiState() }.stateIn(viewModelScope, SharingStarted.Eagerly, holder.state.value.toUiState())

    fun retry() = holder.retry()

    /** Focus on a season selects it (§7.6); a different season replaces (cancels) the episode load. */
    fun selectSeason(seasonNumber: Int) = holder.selectSeason(seasonNumber)

    fun retryEpisodes() = holder.retryEpisodes()

    fun openEpisode(episodeNumber: Int) = holder.openEpisode(episodeNumber)

    fun closeEpisode() = holder.closeEpisode()

    fun noticeShown() = holder.noticeShown()
}
