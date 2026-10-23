package com.techgv.cineverse.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.MovieList
import com.techgv.cineverse.data.tmdb.TvList
import com.techgv.cineverse.data.tmdb.toHero
import com.techgv.cineverse.data.tmdb.toPerson
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which catalog Browse shows. Not a route argument: TV maps its route enum to this one. */
enum class BrowseKind { Movies, Tv, People }

/** One Browse tab (prototype `defs`): chip label, page subtitle, and its list. */
class BrowseTab(val label: String, val subtitle: String, val source: (CatalogRepository) -> PageSource)

private fun movies(list: MovieList) = { catalog: CatalogRepository ->
    PageSource({ catalog.movieList(list, it) }) { it.toHero(MediaType.Movie) }
}

private fun tv(list: TvList) = { catalog: CatalogRepository ->
    PageSource({ catalog.tvList(list, it) }) { it.toHero(MediaType.Tv) }
}

/** §7.3 tabs per prototype `defs`. */
fun browseTabs(kind: BrowseKind): List<BrowseTab> = when (kind) {
    BrowseKind.Movies -> listOf(
        BrowseTab("Popular", "What people are watching on TMDB this week", movies(MovieList.Popular)),
        BrowseTab("Now playing", "In theatres near you", movies(MovieList.NowPlaying)),
        BrowseTab("Upcoming", "Coming soon to theatres", movies(MovieList.Upcoming)),
        BrowseTab("Top rated", "Highest rated by the TMDB community", movies(MovieList.TopRated)),
    )
    BrowseKind.Tv -> listOf(
        BrowseTab("Popular", "The most-watched shows on TMDB right now", tv(TvList.Popular)),
        BrowseTab("Airing today", "New episodes out today", tv(TvList.AiringToday)),
        BrowseTab("On TV", "Airing in the next 7 days", tv(TvList.OnTheAir)),
        BrowseTab("Top rated", "Highest rated by the TMDB community", tv(TvList.TopRated)),
    )
    BrowseKind.People -> listOf(
        BrowseTab("Popular people", "Trending actors and filmmakers") { catalog ->
            PageSource({ catalog.popularPeople(it) }) { Hero.OfPerson(it.toPerson()) }
        },
    )
}

fun browseTitle(kind: BrowseKind): String = when (kind) {
    BrowseKind.Movies -> "Movies"
    BrowseKind.Tv -> "TV Shows"
    BrowseKind.People -> "People"
}

data class BrowseUiState(val tab: Int, val grid: SectionState<PagedItems>)

/**
 * §7.3: the selected tab (saved) and its paged grid. A tab change cancels the previous tab's load
 * through the grid section's generation. [scope]: see [HomeStateHolder].
 */
class BrowseStateHolder(
    private val catalog: CatalogRepository,
    appGeneration: GenerationCounter,
    settings: Flow<Settings>,
    private val saver: StateSaver,
    kind: BrowseKind,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    val tabs = browseTabs(kind)
    private val grid = PagedSection(this.scope, appGeneration)
    private val tab = MutableStateFlow((saver.getInt(TAB) ?: 0).coerceIn(0, tabs.lastIndex))

    val uiState: StateFlow<BrowseUiState> = combine(tab, grid.state, ::BrowseUiState)
        .stateIn(this.scope, SharingStarted.Eagerly, BrowseUiState(tab.value, SectionState.Loading))

    init {
        grid.load(tabs[tab.value].source(catalog))
        this.scope.launch {
            settings.map { Triple(it.language, it.region, it.includeAdult) }.distinctUntilChanged().drop(1).collect { grid.reload() }
        }
    }

    /** Selects tab [index] (TV: on chip focus); a no-op for the current or an unknown index. */
    fun selectTab(index: Int) {
        if (index == tab.value || index !in tabs.indices) return
        tab.value = index
        saver.putInt(TAB, index)
        grid.load(tabs[index].source(catalog))
    }

    fun loadMore() = grid.loadMore()

    fun retry() = grid.reload()

    private companion object {
        const val TAB = "browse.tab"
    }
}
