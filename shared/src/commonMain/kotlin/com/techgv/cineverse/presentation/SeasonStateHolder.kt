package com.techgv.cineverse.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.data.tmdb.toSeason
import com.techgv.cineverse.domain.Detail
import com.techgv.cineverse.domain.Season
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
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

/** One-shot notices of the Season screen (§7.6); the shell shows its copy, then calls [SeasonStateHolder.noticeShown]. */
enum class SeasonNotice { SeasonNotAvailable }

data class SeasonScreenState(
    /** The TV detail this screen loads itself (cache hit from Detail, network after process death). */
    val show: SectionState<Detail> = SectionState.Loading,
    /** Selected season number; null while the show loads or when it has no seasons. */
    val selected: Int? = null,
    /** Episodes of [selected]. */
    val episodes: SectionState<Season> = SectionState.Loading,
    /** Episode number whose inline panel is open. */
    val openEpisode: Int? = null,
    val notice: SeasonNotice? = null,
)

/**
 * §7.6 Season [seasonNumber] of [seriesId]: loads the TV detail itself (never Detail's memory), keeps the selected
 * season (saved; a season that no longer exists selects the first and raises [SeasonNotice.SeasonNotAvailable]) and
 * loads its episodes, a newer selection cancelling the previous load. Show and episodes are independent generation
 * scopes. [scope]: see [HomeStateHolder].
 */
class SeasonStateHolder(
    val seriesId: Int,
    val seasonNumber: Int,
    private val catalog: CatalogRepository,
    settings: Flow<Settings>,
    private val appGeneration: GenerationCounter,
    private val saver: StateSaver,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    val imageBase: String get() = catalog.imageBaseUrl()

    private val showGeneration = GenerationCounter()
    private val episodesGeneration = GenerationCounter()
    private var episodesJob: Job? = null

    /** Language of the current show load; episode loads use the same one. */
    private var language: String? = null
    private val retries = MutableStateFlow(0)
    private val _state = MutableStateFlow(SeasonScreenState(openEpisode = saver.getInt(OPEN_EPISODE)))
    val state: StateFlow<SeasonScreenState> = _state.asStateFlow()

    init {
        this.scope.launch {
            combine(settings.map { it.language to it.region }.distinctUntilChanged(), retries) { content, _ -> content }
                .collectLatest { (language, region) -> loadShow(language, region) }
        }
    }

    fun retry() = retries.update { it + 1 }

    /** Selects a season (TV: on focus); a different season replaces (cancels) the episode load. */
    fun selectSeason(seasonNumber: Int) {
        if (seasonNumber == state.value.selected) return
        saver.putInt(SELECTED, seasonNumber)
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
        saver.putInt(OPEN_EPISODE, episodeNumber)
        _state.update { it.copy(openEpisode = episodeNumber) }
    }

    private suspend fun loadShow(language: String, region: String) {
        this.language = language
        val gen = showGeneration.bump()
        val appGen = appGeneration.current
        // As DetailStateHolder: an app-generation-only supersession reloads for the current settings.
        fun current(): Boolean {
            if (!showGeneration.isCurrent(gen)) return false
            val appCurrent = appGeneration.isCurrent(appGen)
            if (!appCurrent) retry()
            return appCurrent
        }

        if (state.value.show !is SectionState.Content) _state.update { it.copy(show = SectionState.Loading) }
        val show = try {
            catalog.tvDetail(seriesId, language).toDetail(region)
        } catch (e: TmdbException) {
            if (current()) _state.update { it.copy(show = SectionState.Error(e.toSectionErrorKind())) }
            return
        }
        if (!current()) return
        val wanted = saver.getInt(SELECTED) ?: seasonNumber
        val selected = show.seasons.firstOrNull { it.seasonNumber == wanted }?.seasonNumber ?: show.seasons.firstOrNull()?.seasonNumber
        val missing = show.seasons.isNotEmpty() && selected != wanted
        saver.putInt(SELECTED, selected)
        _state.update {
            it.copy(
                show = SectionState.Content(show),
                selected = selected,
                notice = if (missing) SeasonNotice.SeasonNotAvailable else it.notice,
                episodes = if (selected == null) SectionState.Empty else it.episodes,
            )
        }
        if (selected != null) loadEpisodes(selected)
    }

    private fun loadEpisodes(seasonNumber: Int) {
        episodesJob?.cancel()
        val gen = episodesGeneration.bump()
        val appGen = appGeneration.current
        val lang = language
        _state.update { it.copy(episodes = SectionState.Loading) }
        episodesJob = scope.launch {
            val result = try {
                SectionState.Content(catalog.season(seriesId, seasonNumber, lang).toSeason(seriesId))
            } catch (e: TmdbException) {
                SectionState.Error(e.toSectionErrorKind())
            }
            if (!episodesGeneration.isCurrent(gen)) return@launch
            // Missed only a newer app generation: load this season again rather than stay on Loading.
            if (!appGeneration.isCurrent(appGen)) return@launch loadEpisodes(seasonNumber)
            _state.update { it.copy(episodes = result) }
        }
    }

    private companion object {
        const val SELECTED = "selectedSeason"
        const val OPEN_EPISODE = "openEpisode"
    }
}
