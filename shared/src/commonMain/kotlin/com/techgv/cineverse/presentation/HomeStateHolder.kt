package com.techgv.cineverse.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.MovieList
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.data.tmdb.TvList
import com.techgv.cineverse.data.tmdb.genreNames
import com.techgv.cineverse.data.tmdb.toHero
import com.techgv.cineverse.data.tmdb.toPerson
import com.techgv.cineverse.data.tmdb.youTubeInTrailerOrder
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Home rows (§7.2, prototype order); [key] is the stable row key (§1.3), [card] the prototype's card kind. */
enum class HomeRow(val key: String, val title: String, val card: HomeCard) {
    Trending("trending", "Trending", HomeCard.Poster),
    PopularMovies("popularMovies", "What’s popular · Movies", HomeCard.Poster),
    PopularTv("popularTv", "Popular on TV", HomeCard.Landscape),
    NowPlaying("nowPlaying", "Now playing in theatres", HomeCard.Poster),
    Upcoming("upcoming", "Upcoming", HomeCard.Poster),
    PopularPeople("popularPeople", "Popular people", HomeCard.Person),
    TopRatedMovies("topRatedMovies", "Top rated movies", HomeCard.Poster),
}

enum class HomeCard { Poster, Landscape, Person }

data class HomeUiState(
    val trend: TrendingWindow = TrendingWindow.Week,
    /** The selected item ([HomeStateHolder.selectHero]), else the first trending item. */
    val hero: Hero? = null,
    val rows: Map<HomeRow, SectionState<PagedItems>> = HomeRow.entries.associateWith { SectionState.Loading },
    val genres: Map<Int, String> = emptyMap(),
    /** Title whose Play-trailer videos are loading: its button is disabled. */
    val trailerLoading: MediaRef? = null,
    val settingsError: SectionErrorKind? = null,
    val genresError: SectionErrorKind? = null,
)

/** Outcome of a Play-trailer press (§7.2); each shell maps it to its player route / toast. */
sealed interface TrailerEvent {
    /** Play [videoKey] (YouTube); [title] is the film, [kicker] the video name or "Trailer". */
    data class Play(val videoKey: String, val title: String, val kicker: String) : TrailerEvent
    /** No YouTube video, also not in `en-US`. */
    data class Unavailable(val title: String) : TrailerEvent
    data object Failed : TrailerEvent
}

/** §7.10 player arguments: the title's name, the video name as kicker ("Trailer" when blank). */
fun trailerOf(title: String, videoKey: String, videoName: String) = TrailerEvent.Play(videoKey, title, videoName.ifBlank { "Trailer" })

/**
 * §7.2 Home: rows (each its own [PagedSection] generation), trending window (saved), genre names, the hero and the
 * Play-trailer fetch. A trailer result reaches [events] only while the screen is [setActive] and nothing superseded
 * it (settings reload, deactivation, a newer press); the shell decides what "active" means (TV: resumed and window
 * focused).
 *
 * [scope]: the owner's scope when another ViewModel wraps this holder (TV adapters); default = its own viewModelScope.
 */
class HomeStateHolder(
    private val catalog: CatalogRepository,
    private val appGeneration: GenerationCounter,
    private val settings: Flow<Settings>,
    private val saver: StateSaver,
    scope: CoroutineScope? = null,
    private val onStorageFailure: (StorageException) -> Unit = {},
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val sections = HomeRow.entries.associateWith { PagedSection(this.scope, appGeneration, onStorageFailure) }
    private val trend = MutableStateFlow(saver.getString(TREND)?.let { TrendingWindow.valueOf(it) } ?: TrendingWindow.Week)
    private val selectedHero = MutableStateFlow<Hero?>(null)
    private val genres = MutableStateFlow<Map<Int, String>>(emptyMap())
    private val trailerLoading = MutableStateFlow<MediaRef?>(null)
    private val settingsError = MutableStateFlow<SectionErrorKind?>(null)
    private val genresError = MutableStateFlow<SectionErrorKind?>(null)
    private val settingsReads = MutableStateFlow(0L)
    private var contentSettings: Triple<String, String, Boolean>? = null
    private var settingsApp = appGeneration.current
    private var genresApp = appGeneration.current
    private var heroJob: Job? = null
    private var trailerJob: Job? = null
    private var genresJob: Job? = null
    private val genresGeneration = GenerationCounter()

    /** Bumped when a pending trailer fetch must not open anything any more (settings reload, deactivation). */
    private val trailerGeneration = GenerationCounter()
    private var active = false

    private val mutableEvents = MutableSharedFlow<TrailerEvent>(extraBufferCapacity = 1)

    /** Not replayed: an event without a (started) collector is dropped, never delivered on return. */
    val events: SharedFlow<TrailerEvent> = mutableEvents

    private val rows = combine(sections.map { (row, section) -> section.state.map { row to it } }) { it.toMap() }

    val uiState: StateFlow<HomeUiState> =
        combine(trend, selectedHero, rows, genres, trailerLoading) { trend, selected, rows, genres, trailer ->
            val firstTrending = (rows[HomeRow.Trending] as? SectionState.Content)?.value?.items?.firstOrNull()
            HomeUiState(trend, selected ?: firstTrending, rows, genres, trailer)
        }.combine(settingsError) { state, error -> state.copy(settingsError = error) }
            .combine(genresError) { state, error -> state.copy(genresError = error) }
            .stateIn(this.scope, SharingStarted.Eagerly, HomeUiState(trend.value))

    init {
        HomeRow.entries.forEach { load(it) }
        loadGenres()
        // Keep the last real tuple across subscriptions: retry must detect changes missed while storage was down.
        this.scope.launch {
            settingsReads.collectLatest { read ->
                var first = true
                var app = appGeneration.current
                try {
                    settings.collect { value ->
                        currentCoroutineContext().ensureActive()
                        val next = Triple(value.language, value.region, value.includeAdult)
                        val changed = (contentSettings != null && contentSettings != next) || settingsApp != appGeneration.current
                        contentSettings = next
                        settingsApp = appGeneration.current
                        app = settingsApp
                        settingsError.value = null
                        if (changed) {
                            resetContent()
                        } else if (first && read > 0) {
                            sections.values.forEach { it.retryStorage() }
                            if (genresError.value != null) loadGenres()
                        }
                        first = false
                    }
                } catch (failure: StorageException) {
                    currentCoroutineContext().ensureActive()
                    if (app == appGeneration.current) {
                        settingsError.value = SectionErrorKind.Generic
                        onStorageFailure(failure)
                    }
                }
            }
        }
    }

    /** Re-subscribe settings, then recover failed reads; never replay a previous trailer press. */
    fun retryStorage() { settingsReads.update { it + 1 } }

    private fun resetContent() {
        HomeRow.entries.forEach { load(it) }
        genres.value = emptyMap()
        loadGenres()
        heroJob?.cancel()
        selectedHero.value = null
        cancelTrailer()
    }

    /** Genre names of the current settings; a newer load (or a newer app generation) supersedes this one. */
    private fun loadGenres() {
        genresJob?.cancel()
        val gen = genresGeneration.bump()
        val app = appGeneration.current
        if (genresApp != app) genres.value = emptyMap()
        genresJob = scope.launch {
            val result = try {
                Result.success(catalog.genreNames())
            } catch (failure: StorageException) {
                Result.failure(failure)
            }
            currentCoroutineContext().ensureActive()
            if (gen != genresGeneration.current) return@launch
            if (app != appGeneration.current) return@launch loadGenres()
            result.fold(
                onSuccess = {
                    genres.value = it
                    genresApp = app
                    genresError.value = null
                },
                onFailure = {
                    genresError.value = SectionErrorKind.Generic
                    onStorageFailure(it as StorageException)
                },
            )
        }
    }

    /** The screen can open what a trailer fetch finds; false cancels a pending fetch (it never opens later). */
    fun setActive(active: Boolean) {
        this.active = active
        if (!active) cancelTrailer()
    }

    private fun cancelTrailer() {
        trailerGeneration.bump()
        trailerJob?.cancel()
        trailerLoading.value = null
    }

    private fun load(row: HomeRow) {
        val source = when (row) {
            HomeRow.Trending -> trend.value.let { window -> PageSource({ catalog.trending(window, it) }) { it.toHero() } }
            HomeRow.PopularMovies -> movies(MovieList.Popular)
            HomeRow.PopularTv -> PageSource({ catalog.tvList(TvList.Popular, it) }) { it.toHero(MediaType.Tv) }
            HomeRow.NowPlaying -> movies(MovieList.NowPlaying)
            HomeRow.Upcoming -> movies(MovieList.Upcoming)
            HomeRow.PopularPeople -> PageSource({ catalog.popularPeople(it) }) { Hero.OfPerson(it.toPerson()) }
            HomeRow.TopRatedMovies -> movies(MovieList.TopRated)
        }
        sections.getValue(row).load(source)
    }

    private fun movies(list: MovieList) = PageSource({ catalog.movieList(list, it) }) { it.toHero(MediaType.Movie) }

    fun retry(row: HomeRow) {
        if (settingsError.value != null) retryStorage()
        else sections.getValue(row).run {
            if (!retryStorage()) reload(retainContent = true)
        }
    }

    fun loadMore(row: HomeRow) = sections.getValue(row).loadMore()

    /** Switches the trending window and reloads only the trending row (its own generation). */
    fun selectTrend(window: TrendingWindow) {
        if (window == trend.value) return
        trend.value = window
        saver.putString(TREND, window.name)
        heroJob?.cancel()
        selectedHero.value = null
        cancelTrailer()
        load(HomeRow.Trending)
    }

    /** The hero shows [hero] after [delayMillis] (TV: focus debounce, mobile: pager page); a newer call cancels it. */
    fun selectHero(hero: Hero, delayMillis: Long = 0) {
        heroJob?.cancel()
        val app = appGeneration.current
        heroJob = scope.launch {
            delay(delayMillis)
            currentCoroutineContext().ensureActive()
            if (app == appGeneration.current) selectedHero.value = hero
        }
    }

    /**
     * Play trailer from Home (§7.2): list items have no video key → fetch `/videos` (cached). Empty in a
     * non-`en-US` language → one `en-US` fetch (§7.4 rule). One fetch at a time; ignored while inactive.
     */
    fun playTrailer(title: Title) {
        if (!active || trailerJob?.isActive == true) return
        val gen = trailerGeneration.bump()
        val app = appGeneration.current
        trailerLoading.value = title.ref
        trailerJob = scope.launch {
            var storageFailure: StorageException? = null
            val event = try {
                var videos = catalog.videos(title.mediaType, title.ref.id).results.youTubeInTrailerOrder()
                if (videos.isEmpty() && settings.first().language != ENGLISH) {
                    videos = catalog.videos(title.mediaType, title.ref.id, ENGLISH).results.youTubeInTrailerOrder()
                }
                videos.firstOrNull()?.let { trailerOf(title.title, it.key, it.name) } ?: TrailerEvent.Unavailable(title.title)
            } catch (_: TmdbException) {
                TrailerEvent.Failed
            } catch (failure: StorageException) {
                storageFailure = failure
                TrailerEvent.Failed
            }
            currentCoroutineContext().ensureActive()
            // Superseded: cancelTrailer() already cleared the loading flag.
            if (gen != trailerGeneration.current || !active) return@launch
            trailerLoading.value = null
            if (app == appGeneration.current) {
                storageFailure?.let(onStorageFailure)
                mutableEvents.emit(event)
            }
        }
    }

    private companion object {
        const val TREND = "home.trend"
    }
}

/** D7 fallback language and the default TMDB content language. */
const val ENGLISH = "en-US"
