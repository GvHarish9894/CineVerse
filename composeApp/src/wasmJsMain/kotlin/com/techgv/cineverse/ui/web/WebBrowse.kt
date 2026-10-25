package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.di.WebRuntime
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.key
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.displayStyle
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun WebBrowse(entry: WebEntry, catalog: CatalogRepository, generation: GenerationCounter, settings: Flow<Settings>, runtime: WebRuntime, navigate: (WebRoute, Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val holder = remember(entry) { ProductBrowseStateHolder(catalog, generation, settings, entry, entry.route.browseSelection(), scope, runtime::reportStorageFailure) }
    val state by holder.uiState.collectAsState()
    val storage by runtime.storageFailure.collectAsState()
    WebBrowseContent(entry, state, { navigate(it, false) }, holder::loadMore, holder::retry, holder::retryGenres,
        { runtime.retryStorage(); holder.retryStorage() }, storage)
}

@Composable
internal fun WebBrowseContent(entry: WebEntry, state: ProductBrowseUiState, navigate: (WebRoute) -> Unit,
                              loadMore: () -> Unit, retry: () -> Unit, retryGenres: () -> Unit,
                              retryStorage: () -> Unit, storage: BrowserStorageFailure?) {
    val route = entry.route as? WebRoute.Browse
    val content = (state.grid as? SectionState.Content)?.value
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val desktop = maxWidth > 860.dp
        Row(Modifier.widthIn(max = 1320.dp).fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (desktop && route != null) Column(Modifier.width(240.dp).verticalScroll(rememberScrollState())) {
                BrowseFilters(route, state, navigate, retryGenres)
            }
            val targetIndex = remember(entry) { entry.getInt("browse.index") ?: 0 }
            val targetOffset = remember(entry) { entry.getInt("browse.offset") ?: 0 }
            val targetPage = remember(entry) { entry.getInt("browse.page") ?: 1 }
            val scroll = remember(entry) { LazyGridState() }
            var restored by remember(entry) { mutableStateOf(false) }
            LaunchedEffect(content, restored) {
                if (content == null || restored || content.loadingMore || content.moreError != null) return@LaunchedEffect
                // Reload previously viewed pages before restoring their scroll; errors still require explicit retry.
                if (content.page < targetPage && content.hasMore) loadMore()
                else {
                    scroll.scrollToItem(targetIndex.coerceAtMost(content.items.size + 2), targetOffset)
                    restored = true
                }
            }
            LaunchedEffect(scroll, restored) {
                if (restored) snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }
                    .distinctUntilChanged().collect { (index, offset) -> entry.putInt("browse.index", index); entry.putInt("browse.offset", offset) }
            }
            LaunchedEffect(content?.page, restored) { if (restored && content != null) entry.putInt("browse.page", content.page) }
            LazyVerticalGrid(columns = GridCells.Adaptive(if (route == null) 150.dp else 170.dp), state = scroll,
                modifier = Modifier.weight(1f).testTag("web-browse"), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(state.selection.sourceLabel(), style = displayStyle(48.sp), modifier = Modifier.semantics { heading() })
                        Text(if (state.selection is BrowseSelection.Discover) "Generic Discover results · genres match all selected IDs. Not a curated release list."
                            else if (route == null) "Popular people from TMDB · portraits and known-for titles."
                            else "Curated TMDB list. Changing sort or genres opens generic Discover.")
                        if (route != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val lists = if (route.media == "movie") listOf("Popular" to "popular", "Now playing" to "now_playing", "Upcoming" to "upcoming", "Top rated" to "top_rated")
                                else listOf("Popular" to "popular", "Airing today" to "airing_today", "On TV" to "on_the_air", "Top rated" to "top_rated")
                            lists.forEach { (label, list) -> Chip(label, state.selection !is BrowseSelection.Discover && route.list == list, { navigate(WebRoute.Browse(route.media, list)) }) }
                        }
                        if (!desktop && route != null) BrowseFilters(route, state, navigate, retryGenres)
                        if (storage != null) WebStorageNotice(storage, retryStorage, Modifier.testTag("browse-storage-recovery"))
                        if (state.settingsError != null) ErrorState("Saved preferences could not be observed. Last loaded values are retained.", retryStorage, Modifier.testTag("browse-settings-recovery"))
                        Text("${content?.items?.size ?: 0} ${if (route == null) "people" else "titles"} loaded · not a total result count")
                    }
                }
                when (val grid = state.grid) {
                    SectionState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { Text("Loading results…") }
                    SectionState.Empty -> item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("No results", "No results were returned for this source.") { PillButton("Refresh results", retry) } }
                    is SectionState.Error -> item(span = { GridItemSpan(maxLineSpan) }) { BrowseError(grid.kind, retry) }
                    is SectionState.Content -> {
                        items(grid.value.items, key = { it.key }) { hero ->
                            BoxWithConstraints {
                                val palette = ArtPalette(Tokens.Colors.Accent, Tokens.Colors.Secondary)
                                when (hero) {
                                    is Hero.OfPerson -> PersonCard(hero.person.name, hero.person.knownFor.joinToString { it.title }.ifEmpty { hero.person.knownForDepartment.orEmpty() }, palette,
                                        { navigate(hero.route()) }, Modifier.testTag("browse-${hero.key}"), webImage(hero.person.profilePath, "w185"), width = maxWidth)
                                    is Hero.OfTitle -> PosterCard(hero.title.title, hero.title.releaseDate, palette, { navigate(hero.route()) }, Modifier.testTag("browse-${hero.key}"),
                                        imageUrl = webImage(hero.title.posterPath, "w342"), voteAverage = hero.title.voteAverage, voteCount = hero.title.voteCount, width = maxWidth)
                                }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                grid.value.moreError?.let { BrowseError(it, retry, retained = true) }
                                if (grid.value.loadingMore) Text("Loading more results…")
                                else if (grid.value.hasMore && grid.value.moreError == null) PillButton("Load more", loadMore)
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Footer() }
            }
        }
    }
}

@Composable
private fun BrowseFilters(route: WebRoute.Browse, state: ProductBrowseUiState, navigate: (WebRoute) -> Unit, retryGenres: () -> Unit) {
    val discover = state.selection as? BrowseSelection.Discover
    fun filter(sort: DiscoverSort = discover?.sort ?: DiscoverSort.Popularity, genres: List<Int> = discover?.genreIds.orEmpty()) {
        navigate(WebRoute.Browse(route.media, "discover", sort.webValue(route.media), genres))
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Discover filters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        var expanded by remember { mutableStateOf(false) }
        Box {
            PillButton("Sort: ${(discover?.sort ?: DiscoverSort.Popularity).label()} ▾", { expanded = true }, variant = ButtonVariant.Ghost)
            DropdownMenu(expanded, { expanded = false }) {
                DiscoverSort.entries.forEach { sort ->
                    // House buttons retain the same visible keyboard focus ring as the other controls.
                    PillButton(sort.label(), { expanded = false; filter(sort = sort) }, variant = ButtonVariant.Ghost)
                }
            }
        }
        Text("Genres · match all selected", style = MaterialTheme.typography.labelLarge)
        if (state.genresLoading) Text("Loading genres…")
        state.genresError?.let { Box(Modifier.testTag("browse-genres-recovery")) { BrowseError(it, retryGenres) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val selected = discover?.genreIds.orEmpty()
            (state.genres.keys + selected).sorted().forEach { id ->
                val active = id in selected
                Chip(state.genres[id] ?: "Genre $id", active, {
                    if (active || selected.size < 20) filter(genres = if (active) selected - id else selected + id)
                }, Modifier.semantics { if (!active && selected.size >= 20) disabled() })
            }
        }
        if (discover?.genreIds?.isNotEmpty() == true) PillButton("Clear genres", { filter(genres = emptyList()) }, variant = ButtonVariant.Ghost)
        if (discover?.genreIds?.size == 20) Text("Maximum 20 genres selected. Remove one before adding another.")
    }
}

@Composable
private fun BrowseError(kind: SectionErrorKind, retry: () -> Unit, retained: Boolean = false) {
    val message = when (kind) {
        SectionErrorKind.Offline -> "Offline. Check your connection."
        SectionErrorKind.NotAvailable -> "These results are not available."
        SectionErrorKind.AppCredential -> "Catalog service configuration is unavailable."
        SectionErrorKind.SessionExpired -> "Session expired. Verification is required."
        SectionErrorKind.Generic -> "Could not load results."
    } + if (retained) " Loaded results are retained." else ""
    if (kind.retryable) ErrorState(message, retry) else Text(message)
}

private fun DiscoverSort.label(): String = if (this == DiscoverSort.ReleaseDate) "Release date" else name
