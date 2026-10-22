package com.techgv.cineverse.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.tmdb.needsEnglishFallback
import com.techgv.cineverse.data.tmdb.orderedYouTubeVideos
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.data.tmdb.withEnglishFallback
import com.techgv.cineverse.domain.Detail
import com.techgv.cineverse.domain.LibraryActions
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Catalog only: button states are [DetailStateHolder.accountState], so they never block the title (§7.4). */
data class DetailUiState(val detail: SectionState<Detail> = SectionState.Loading)

/**
 * §7.4 / §7.2 library button rule: enabled only with a library ([hasLibrary]), a known state (never "off" while
 * `account_states` is unknown) and no write of [kind] in flight.
 */
fun LibraryState.canToggle(kind: LibraryKind, hasLibrary: Boolean = true): Boolean = hasLibrary && known && kind !in pending

/** §7.4 Play trailer: the first YouTube video in trailer order; null hides the button. */
fun Detail.trailer(): TrailerEvent.Play? = videos.firstOrNull()?.let { trailerOf(title, it.key, it.name) }

/** §7.4 Episodes: the first listed season (Specials last); null (no seasons) hides the button. */
fun Detail.episodesSeason(): Int? = seasons.firstOrNull()?.seasonNumber

/**
 * §7.4 Detail of [ref]: one catalog request in the settings language; when that is not `en-US`, blank tagline /
 * overview are filled from one cached `en-US` detail (D7, labelled by the mapper) and empty videos are refetched in
 * `en-US`, after the localized detail is already shown (their failure changes nothing). Language / region changes
 * and [retry] reload; a result that only missed a newer app generation reloads rather than staying on Loading.
 * [library] supplies the separate account state (null: [accountState] stays Unknown). [scope]: see [HomeStateHolder].
 */
class DetailStateHolder(
    val ref: MediaRef,
    private val catalog: CatalogRepository,
    settings: Flow<Settings>,
    private val appGeneration: GenerationCounter,
    library: LibraryActions? = null,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    val imageBase: String get() = catalog.imageBaseUrl()

    /** This screen's request generation (§1.1): bumped by every (re)load. */
    private val generation = GenerationCounter()
    private val retries = MutableStateFlow(0)
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /** Watchlist / favourite / rating of [ref] for the current identity, read separately from the catalog. */
    val accountState: StateFlow<LibraryState> = (library?.state(ref) ?: flowOf(LibraryState.Unknown))
        .stateIn(this.scope, SharingStarted.WhileSubscribed(5_000), LibraryState.Unknown)

    init {
        this.scope.launch {
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
            when (ref.mediaType) {
                MediaType.Movie -> catalog.movieDetail(ref.id, language).toDetail(region)
                MediaType.Tv -> catalog.tvDetail(ref.id, language).toDetail(region)
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
                when (ref.mediaType) {
                    MediaType.Movie -> catalog.movieDetail(ref.id, ENGLISH).let { it.tagline to it.overview }
                    MediaType.Tv -> catalog.tvDetail(ref.id, ENGLISH).let { it.tagline to it.overview }
                }
            }
            if (english != null) detail = detail.withEnglishFallback(english.first, english.second)
        }
        if (detail.videos.isEmpty()) {
            optional { catalog.videos(ref.mediaType, ref.id, ENGLISH) }?.let { detail = detail.copy(videos = orderedYouTubeVideos(it.results)) }
        }
        if (current()) _state.update { it.copy(detail = SectionState.Content(detail)) }
    }
}

/** Runs an optional fallback fetch: a TMDB failure yields null. */
internal suspend fun <T> optional(block: suspend () -> T): T? = try {
    block()
} catch (_: TmdbException) {
    null
}
