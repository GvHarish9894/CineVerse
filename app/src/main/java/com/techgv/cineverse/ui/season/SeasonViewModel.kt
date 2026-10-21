package com.techgv.cineverse.ui.season

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.data.tmdb.toSeason
import com.techgv.cineverse.domain.Detail
import com.techgv.cineverse.domain.Season
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.ui.components.toSectionErrorKind
import com.techgv.cineverse.ui.nav.Route
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

class SeasonViewModel(
    val route: Route.Season,
    private val handle: SavedStateHandle,
    private val tmdb: TmdbRepository,
    settings: Flow<Settings>,
    private val appGeneration: GenerationCounter,
) : ViewModel() {
    val focusMemory = FocusMemory(handle)
    val imageBase: String get() = tmdb.imageBaseUrl()

    /** Scopes (§1.1): the show load and the episodes load have independent generations. */
    private val showGeneration = GenerationCounter()
    private val episodesGeneration = GenerationCounter()
    private var episodesJob: Job? = null
    private val retries = MutableStateFlow(0)
    private val _state = MutableStateFlow(SeasonUiState(openEpisode = handle[OPEN_EPISODE]))
    val state: StateFlow<SeasonUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(settings.map { it.language to it.region }.distinctUntilChanged(), retries) { content, _ -> content }
                .collectLatest { (_, region) -> loadShow(region) }
        }
    }

    fun retry() = retries.update { it + 1 }

    /** Focus on a season selects it (§7.6); a different season replaces (cancels) the episode load. */
    fun selectSeason(seasonNumber: Int) {
        if (seasonNumber == state.value.selected) return
        handle[SELECTED] = seasonNumber
        _state.update { it.copy(selected = seasonNumber) }
        loadEpisodes(seasonNumber)
    }

    fun retryEpisodes() {
        state.value.selected?.let(::loadEpisodes)
    }

    fun openEpisode(episodeNumber: Int) = setOpenEpisode(episodeNumber)

    fun closeEpisode() = setOpenEpisode(null)

    fun noticeShown() = _state.update { it.copy(notice = null) }

    private fun setOpenEpisode(episodeNumber: Int?) {
        handle[OPEN_EPISODE] = episodeNumber
        _state.update { it.copy(openEpisode = episodeNumber) }
    }

    private suspend fun loadShow(region: String) {
        val gen = showGeneration.bump()
        val appGen = appGeneration.current
        fun current() = showGeneration.isCurrent(gen) && appGeneration.isCurrent(appGen)

        if (state.value.show !is SectionState.Content) _state.update { it.copy(show = SectionState.Loading) }
        val show = try {
            tmdb.tvDetail(route.seriesId).toDetail(region)
        } catch (e: TmdbException) {
            if (current()) _state.update { it.copy(show = SectionState.Error(e.toSectionErrorKind())) }
            return
        }
        if (!current()) return
        val wanted = handle[SELECTED] ?: route.seasonNumber
        val selected = show.seasons.firstOrNull { it.seasonNumber == wanted }?.seasonNumber ?: show.seasons.firstOrNull()?.seasonNumber
        val missing = show.seasons.isNotEmpty() && selected != wanted
        handle[SELECTED] = selected
        _state.update {
            it.copy(
                show = SectionState.Content(show),
                selected = selected,
                notice = if (missing) SEASON_NOT_AVAILABLE else it.notice,
                episodes = if (selected == null) SectionState.Empty else it.episodes,
            )
        }
        if (selected != null) loadEpisodes(selected)
    }

    private fun loadEpisodes(seasonNumber: Int) {
        episodesJob?.cancel()
        val gen = episodesGeneration.bump()
        val appGen = appGeneration.current
        fun current() = episodesGeneration.isCurrent(gen) && appGeneration.isCurrent(appGen)
        _state.update { it.copy(episodes = SectionState.Loading) }
        episodesJob = viewModelScope.launch {
            val result = try {
                SectionState.Content(tmdb.season(route.seriesId, seasonNumber).toSeason(route.seriesId))
            } catch (e: TmdbException) {
                SectionState.Error(e.toSectionErrorKind())
            }
            if (current()) _state.update { it.copy(episodes = result) }
        }
    }

    private companion object {
        const val SELECTED = "selectedSeason"
        const val OPEN_EPISODE = "openEpisode"
    }
}
