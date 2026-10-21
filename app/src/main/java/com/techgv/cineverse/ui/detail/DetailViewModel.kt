package com.techgv.cineverse.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.data.tmdb.needsEnglishFallback
import com.techgv.cineverse.data.tmdb.orderedYouTubeVideos
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.data.tmdb.withEnglishFallback
import com.techgv.cineverse.domain.Detail
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.ui.components.toSectionErrorKind
import com.techgv.cineverse.ui.nav.Route
import com.techgv.cineverse.util.GenerationCounter
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

const val ENGLISH = "en-US"

/** Catalog only: button states come from `LocalLibraryActions` in the screen, so they never block this (§7.4). */
data class DetailUiState(val detail: SectionState<Detail> = SectionState.Loading)

class DetailViewModel(
    val route: Route.Detail,
    handle: SavedStateHandle,
    private val tmdb: TmdbRepository,
    settings: Flow<Settings>,
    private val appGeneration: GenerationCounter,
) : ViewModel() {
    val focusMemory = FocusMemory(handle)
    val imageBase: String get() = tmdb.imageBaseUrl()

    /** This screen's request generation (§1.1): bumped by every (re)load. */
    private val generation = GenerationCounter()
    private val retries = MutableStateFlow(0)
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Language / region changes reload the visible screen (§3.4); Retry re-runs the same load.
            combine(settings.map { it.language to it.region }.distinctUntilChanged(), retries) { content, _ -> content }
                .collectLatest { (language, region) -> load(language, region) }
        }
    }

    fun retry() = retries.update { it + 1 }

    private suspend fun load(language: String, region: String) {
        val gen = generation.bump()
        val appGen = appGeneration.current
        // Superseded by a newer load: drop. Superseded only by the app generation (auth / settings change
        // without a new settings value here): reload for the current settings, never stay on Loading.
        fun current(): Boolean {
            if (!generation.isCurrent(gen)) return false
            val appCurrent = appGeneration.isCurrent(appGen)
            if (!appCurrent) retry()
            return appCurrent
        }

        if (state.value.detail !is SectionState.Content) _state.update { it.copy(detail = SectionState.Loading) }
        var detail = try {
            when (route.mediaType) {
                MediaType.Movie -> tmdb.movieDetail(route.id, language).toDetail(region)
                MediaType.Tv -> tmdb.tvDetail(route.id, language).toDetail(region)
            }
        } catch (e: TmdbException) {
            if (current()) _state.update { it.copy(detail = SectionState.Error(e.toSectionErrorKind())) }
            return
        }
        if (!current()) return
        _state.update { it.copy(detail = SectionState.Content(detail)) }
        if (language == ENGLISH) return

        // Optional en-US fallbacks after the localized detail is on screen; their failure changes nothing.
        if (detail.needsEnglishFallback) {
            val english = optional {
                when (route.mediaType) {
                    MediaType.Movie -> tmdb.movieDetail(route.id, ENGLISH).let { it.tagline to it.overview }
                    MediaType.Tv -> tmdb.tvDetail(route.id, ENGLISH).let { it.tagline to it.overview }
                }
            }
            if (english != null) detail = detail.withEnglishFallback(english.first, english.second)
        }
        if (detail.videos.isEmpty()) {
            optional { tmdb.videos(route.mediaType, route.id, ENGLISH) }?.let { detail = detail.copy(videos = orderedYouTubeVideos(it.results)) }
        }
        if (current()) _state.update { it.copy(detail = SectionState.Content(detail)) }
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (_: TmdbException) {
        null
    }
}
