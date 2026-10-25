package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.data.tmdb.MovieList
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.data.tmdb.TvList
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.key
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*

@Composable
internal fun MobileExploreScreen(
    graph: MobileApplication,
    model: MobileExploreViewModel,
    scrollToTop: Int,
    navigator: MobileNavigator,
    onBack: (() -> Unit)? = null,
) {
    val page by model.currentPage.collectAsState()
    val state by page.browse.uiState.collectAsState()
    val storageFailed by page.storageFailed.collectAsState()
    val settingsFailed by graph.settingsFailed.collectAsState()
    val scrollStates = rememberSaveableStateHolder()
    scrollStates.SaveableStateProvider(state.selection.type().name) {
        val scroll = rememberLazyGridState()
        var handledScroll by rememberSaveable { mutableIntStateOf(0) }
        var lastSelection by rememberSaveable { mutableStateOf(state.selection.toString()) }
        LaunchedEffect(scrollToTop, state.selection) {
            if (handledScroll != scrollToTop || lastSelection != state.selection.toString()) scroll.scrollToItem(0)
            handledScroll = scrollToTop
            lastSelection = state.selection.toString()
        }
        MobileExploreContent(state, graph.catalog.imageBaseUrl(), navigator, model::selectType, model::select,
            model::retry, model::retryStorage, model::retryGenres, page.browse::loadMore,
            settingsFailed, storageFailed, scroll, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileExploreContent(
    state: ProductBrowseUiState,
    baseUrl: String,
    navigator: MobileNavigator,
    onType: (MobileExploreType) -> Unit,
    onSelection: (BrowseSelection) -> Unit,
    onRetry: () -> Unit,
    onStorageRetry: () -> Unit,
    onGenresRetry: () -> Unit,
    onMore: () -> Unit,
    settingsFailed: Boolean = false,
    storageFailed: Boolean = false,
    scroll: LazyGridState = rememberLazyGridState(),
    onBack: (() -> Unit)? = null,
) {
    val content = (state.grid as? SectionState.Content)?.value
    // Only a successful, idle page can trigger viewport paging. Displayed errors require an explicit press.
    LaunchedEffect(scroll, content) {
        if (content?.hasMore == true && !content.loadingMore && content.moreError == null) {
            snapshotFlow {
                val layout = scroll.layoutInfo
                layout.visibleItemsInfo.lastOrNull()?.index?.let { it >= layout.totalItemsCount - 3 } == true
            }.collect { nearEnd -> if (nearEnd) onMore() }
        }
    }
    Box(Modifier.fillMaxSize()) {
        AmbientGlow(mobilePalette("explore"))
        PullToRefreshBox(state.grid == SectionState.Loading, onRetry, Modifier.fillMaxSize()) {
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().testTag("mobile-explore"), scroll,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item("header", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (onBack != null) PillButton("Back", onBack, variant = ButtonVariant.Ghost)
                        SectionTitle("Explore")
                        Text(when (val selection = state.selection) {
                            is BrowseSelection.Movies -> "Movies · ${selection.list.label()}"
                            is BrowseSelection.Tv -> "TV shows · ${selection.list.label()}"
                            BrowseSelection.People -> "Popular people"
                            is BrowseSelection.Trending -> "Trending · ${if (selection.window == TrendingWindow.Day) "Today" else "This week"} · Movies and TV"
                            is BrowseSelection.Discover -> "Discover"
                        }, Modifier.testTag("explore-source"))
                        val types = listOf(MobileExploreType.Movies, MobileExploreType.Tv, MobileExploreType.People)
                        val trending = state.selection as? BrowseSelection.Trending
                        if (trending != null) {
                            // Mixed Trending has no selected media type; don't mislabel it as Movies or TV.
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                types.forEachIndexed { index, type -> Chip(listOf("Movies", "TV shows", "People")[index], false, { onType(type) }) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TrendingWindow.entries.forEach { window ->
                                    Chip(if (window == TrendingWindow.Day) "Today" else "This week", trending.window == window,
                                        { onSelection(BrowseSelection.Trending(window)) })
                                }
                            }
                        } else SegmentedControl(listOf("Movies", "TV shows", "People"), types.indexOf(state.selection.type()), { onType(types[it]) })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            when (val selection = state.selection) {
                                is BrowseSelection.Movies -> MovieList.entries.forEach { list ->
                                    Chip(list.label(), selection.list == list, { onSelection(BrowseSelection.Movies(list)) })
                                }
                                is BrowseSelection.Tv -> TvList.entries.forEach { list ->
                                    Chip(list.label(), selection.list == list, { onSelection(BrowseSelection.Tv(list)) })
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                if (!state.settingsKnown || settingsFailed || state.settingsError != null) item("settings", span = { GridItemSpan(maxLineSpan) }) {
                    if (settingsFailed || state.settingsError != null) ErrorState(
                        if (state.settingsKnown) "Saved preferences unavailable; keeping the last loaded values." else "Saved preferences unavailable; content preferences are unknown.",
                        onStorageRetry, Modifier.testTag("explore-settings-recovery"))
                    else Text("Loading saved preferences…")
                }
                if (state.genresError != null) item("genres", span = { GridItemSpan(maxLineSpan) }) {
                    ErrorState("Genre names unavailable; keeping loaded content.", onGenresRetry, Modifier.testTag("explore-genres-recovery"))
                }
                if (storageFailed && !settingsFailed && state.settingsError == null && state.genresError == null) item("storage", span = { GridItemSpan(maxLineSpan) }) {
                    ErrorState("A Browse storage read failed. Retry reads.", onStorageRetry, Modifier.testTag("explore-storage-recovery"))
                }
                when (val grid = state.grid) {
                    SectionState.Loading -> items(9) {
                        if (state.selection == BrowseSelection.People) PersonCircleSkeleton(Modifier.fillMaxWidth(), diameter = 96.dp)
                        else PosterCardSkeleton(Modifier.fillMaxWidth())
                    }
                    SectionState.Empty -> item("empty", span = { GridItemSpan(maxLineSpan) }) { Text("Nothing here yet.") }
                    is SectionState.Error -> item("error", span = { GridItemSpan(maxLineSpan) }) { SectionFailure(grid.kind, onRetry) }
                    is SectionState.Content -> {
                        items(grid.value.items, key = { it.key }) { hero ->
                            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                                when (hero) {
                                    is Hero.OfPerson -> PersonCircle(hero.person.name, hero.person.knownForDepartment, mobilePalette(hero.key),
                                        { navigator.open(MobileDestination.Person(hero.person.id)) },
                                        imageUrl = imageUrl(baseUrl, "w185", hero.person.profilePath), diameter = minOf(96.dp, maxWidth))
                                    is Hero.OfTitle -> PosterCard(hero.title.title, titleMeta(hero.title), mobilePalette(hero.key),
                                        { navigator.open(MobileDestination.Detail(hero.title.ref)) }, width = maxWidth,
                                        imageUrl = imageUrl(baseUrl, "w342", hero.title.posterPath),
                                        voteAverage = hero.title.voteAverage, voteCount = hero.title.voteCount,
                                        tag = if (hero.title.mediaType == MediaType.Tv) "TV" else null)
                                }
                            }
                        }
                        if (grid.value.hasMore) item("paging", span = { GridItemSpan(maxLineSpan) }) {
                            val error = grid.value.moreError
                            if (error != null) SectionFailure(error, onRetry)
                            else if (grid.value.loadingMore) Text("Loading more…")
                            else PillButton("Load more", onMore)
                        }
                    }
                }
                item("attribution", span = { GridItemSpan(maxLineSpan) }) { Footer() }
            }
        }
    }
}

private fun MovieList.label(): String = when (this) {
    MovieList.Popular -> "Popular"
    MovieList.NowPlaying -> "Now playing"
    MovieList.Upcoming -> "Upcoming"
    MovieList.TopRated -> "Top rated"
}

private fun TvList.label(): String = when (this) {
    TvList.Popular -> "Popular"
    TvList.AiringToday -> "Airing today"
    TvList.OnTheAir -> "On TV"
    TvList.TopRated -> "Top rated"
}
