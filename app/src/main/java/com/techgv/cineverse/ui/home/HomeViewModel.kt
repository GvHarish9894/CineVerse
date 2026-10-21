package com.techgv.cineverse.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.MovieList
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.tmdb.TmdbRepository
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
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.PageSource
import com.techgv.cineverse.ui.components.PagedItems
import com.techgv.cineverse.ui.components.PagedSection
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.ui.components.ToastMessage
import com.techgv.cineverse.ui.nav.Route
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Home rows (§7.2) in display order; [key] is the stable focus row key (§1.3). */
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
    /** Focused item (120 ms debounce), else the first trending item. */
    val hero: Hero? = null,
    val rows: Map<HomeRow, SectionState<PagedItems>> = HomeRow.entries.associateWith { SectionState.Loading },
    val genres: Map<Int, String> = emptyMap(),
    /** Title whose Play-trailer videos are loading: its button is disabled. */
    val trailerLoading: MediaRef? = null,
)

sealed interface HomeEvent {
    data class Open(val route: Route) : HomeEvent
    data class Toast(val message: ToastMessage) : HomeEvent
}

class HomeViewModel(
    private val repository: TmdbRepository,
    appGeneration: GenerationCounter,
    private val settings: Flow<Settings>,
    private val handle: SavedStateHandle,
) : ViewModel() {
    val focusMemory = FocusMemory(handle)

    private val sections = HomeRow.entries.associateWith { PagedSection(viewModelScope, appGeneration) }
    private val trend = MutableStateFlow(handle.get<String>(TREND)?.let { TrendingWindow.valueOf(it) } ?: TrendingWindow.Week)
    private val focusedHero = MutableStateFlow<Hero?>(null)
    private val genres = MutableStateFlow<Map<Int, String>>(emptyMap())
    private val trailerLoading = MutableStateFlow<MediaRef?>(null)
    private var heroJob: Job? = null
    private var trailerJob: Job? = null

    private val mutableEvents = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 1)

    /** Not replayed: an event without a (started) collector is dropped, never delivered on return. */
    val events: SharedFlow<HomeEvent> = mutableEvents

    private val rows = combine(sections.map { (row, section) -> section.state.map { row to it } }) { it.toMap() }

    val uiState: StateFlow<HomeUiState> =
        combine(trend, focusedHero, rows, genres, trailerLoading) { trend, focused, rows, genres, trailer ->
            val firstTrending = (rows[HomeRow.Trending] as? SectionState.Content)?.value?.items?.firstOrNull()
            HomeUiState(trend, focused ?: firstTrending, rows, genres, trailer)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState(trend.value))

    init {
        HomeRow.entries.forEach { load(it) }
        loadGenres()
        // §3.4: a language / region / adult change reloads what is visible.
        viewModelScope.launch {
            settings.map { Triple(it.language, it.region, it.includeAdult) }.distinctUntilChanged().drop(1).collect {
                sections.values.forEach { it.reload() }
                loadGenres()
            }
        }
    }

    private fun loadGenres() {
        viewModelScope.launch { genres.value = repository.genreNames() }
    }

    private fun load(row: HomeRow) {
        val source = when (row) {
            HomeRow.Trending -> trend.value.let { window -> PageSource({ repository.trending(window, it) }) { it.toHero() } }
            HomeRow.PopularMovies -> movies(MovieList.Popular)
            HomeRow.PopularTv -> PageSource({ repository.tvList(TvList.Popular, it) }) { it.toHero(MediaType.Tv) }
            HomeRow.NowPlaying -> movies(MovieList.NowPlaying)
            HomeRow.Upcoming -> movies(MovieList.Upcoming)
            HomeRow.PopularPeople -> PageSource({ repository.popularPeople(it) }) { Hero.OfPerson(it.toPerson()) }
            HomeRow.TopRatedMovies -> movies(MovieList.TopRated)
        }
        sections.getValue(row).load(source)
    }

    private fun movies(list: MovieList) = PageSource({ repository.movieList(list, it) }) { it.toHero(MediaType.Movie) }

    fun retry(row: HomeRow) = sections.getValue(row).reload()

    /** Trending chips (OK): switches the window and reloads only the trending row (its own generation). */
    fun selectTrend(window: TrendingWindow) {
        if (window == trend.value) return
        trend.value = window
        handle[TREND] = window.name
        load(HomeRow.Trending)
    }

    /** §7.2: the hero follows focus after 120 ms; focus moving on cancels the pending change. */
    fun onItemFocused(hero: Hero) {
        heroJob?.cancel()
        heroJob = viewModelScope.launch {
            delay(HERO_DEBOUNCE_MILLIS)
            focusedHero.value = hero
        }
    }

    /**
     * Play trailer from Home (§7.2): list items have no video key → fetch `/videos` (cached). Empty in a
     * non-`en-US` language → one `en-US` fetch (§7.4 rule). No YouTube video → "No trailer available".
     */
    fun playTrailer(title: Title) {
        if (trailerJob?.isActive == true) return
        trailerLoading.value = title.ref
        trailerJob = viewModelScope.launch {
            try {
                var videos = repository.videos(title.mediaType, title.ref.id).results.youTubeInTrailerOrder()
                if (videos.isEmpty() && settings.first().language != ENGLISH) {
                    videos = repository.videos(title.mediaType, title.ref.id, ENGLISH).results.youTubeInTrailerOrder()
                }
                val video = videos.firstOrNull()
                mutableEvents.emit(
                    if (video == null) {
                        HomeEvent.Toast(ToastMessage("No trailer available", title.title, icon = "!"))
                    } else {
                        HomeEvent.Open(Route.Player(video.key, video.name.ifBlank { "Trailer" }, "${title.title} · YouTube"))
                    },
                )
            } catch (_: TmdbException) {
                mutableEvents.emit(HomeEvent.Toast(ToastMessage("Couldn’t load the trailer", "Try again", icon = "!")))
            } finally {
                trailerLoading.value = null
            }
        }
    }

    private companion object {
        const val TREND = "home.trend"
        const val HERO_DEBOUNCE_MILLIS = 120L
        const val ENGLISH = "en-US"
    }
}
