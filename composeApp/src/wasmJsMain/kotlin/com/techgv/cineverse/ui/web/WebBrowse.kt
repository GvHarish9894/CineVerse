package com.techgv.cineverse.ui.web

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.di.WebRuntime
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.key
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.bodyStyle
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
    val people = route == null
    val palette = ArtPalette(Tokens.Colors.Accent, Tokens.Colors.Secondary)
    val filters: (@Composable ColumnScope.() -> Unit)? = if (route == null) null else ({ BrowseFilters(route, state, navigate, retryGenres) })
    WebAsideGrid(
        scroll, GridCells.Adaptive(if (people) 150.dp else 170.dp), Modifier.testTag("web-browse"),
        horizontalSpacing = if (people) 20.dp else 22.dp, verticalSpacing = if (people) 26.dp else 34.dp,
        header = {
            if (route == null) WebPageTitle("Popular people", subtitle = "Popular actors and filmmakers on TMDB")
            else {
                WebPageTitle(if (route.media == "movie") "Movies" else "TV Shows", subtitle = "What people are watching on TMDB this week")
                FlowRow(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val lists = if (route.media == "movie") listOf("Popular" to "popular", "Now playing" to "now_playing", "Upcoming" to "upcoming", "Top rated" to "top_rated")
                        else listOf("Popular" to "popular", "Airing today" to "airing_today", "On TV" to "on_the_air", "Top rated" to "top_rated")
                    // Discover (any sort/genre filter) selects no list chip; a list chip clears the filters.
                    lists.forEach { (label, list) -> Chip(label, state.selection !is BrowseSelection.Discover && route.list == list, { navigate(WebRoute.Browse(route.media, list)) }) }
                }
            }
            if (storage != null) WebStorageNotice(storage, retryStorage, Modifier.padding(top = 16.dp).testTag("browse-storage-recovery"))
            if (state.settingsError != null) ErrorState("Saved preferences could not be observed. Last loaded values are retained.", retryStorage, Modifier.padding(top = 16.dp).testTag("browse-settings-recovery"))
        },
        aside = filters,
    ) {
        if (content != null) item(key = "count", span = { GridItemSpan(maxLineSpan) }) {
            Text(browseCount(content, people, state), style = bodyStyle(14.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.65f), modifier = Modifier.testTag("browse-count"))
        }
        when (val grid = state.grid) {
            SectionState.Loading -> items(8, key = { "skeleton-$it" }) { BoxWithConstraints { PosterCardSkeleton(width = maxWidth) } }
            SectionState.Empty -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                val filtered = (state.selection as? BrowseSelection.Discover)?.genreIds?.isNotEmpty() == true
                if (filtered) EmptyState("No titles match", "Try removing a genre filter.")
                else EmptyState("Nothing to show", "TMDB has no ${if (people) "people" else "titles"} here right now.") { PillButton("Refresh results", retry) }
            }
            is SectionState.Error -> item(key = "error", span = { GridItemSpan(maxLineSpan) }) { BrowseError(grid.kind, retry) }
            is SectionState.Content -> {
                items(grid.value.items, key = { it.key }) { hero ->
                    BoxWithConstraints {
                        when (hero) {
                            is Hero.OfPerson -> PersonCard(hero.person.name, hero.person.knownFor.joinToString { it.title }.ifEmpty { hero.person.knownForDepartment.orEmpty() }, palette,
                                { navigate(hero.route()) }, Modifier.testTag("browse-${hero.key}"), webImage(hero.person.profilePath, "w185"), width = maxWidth)
                            is Hero.OfTitle -> PosterCard(hero.title.title, formatDate(hero.title.releaseDate) ?: yearOf(hero.title.releaseDate), palette, { navigate(hero.route()) },
                                Modifier.testTag("browse-${hero.key}"), imageUrl = webImage(hero.title.posterPath, "w342"),
                                voteAverage = hero.title.voteAverage, voteCount = hero.title.voteCount, width = maxWidth, nrRing = true)
                        }
                    }
                }
                item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        grid.value.moreError?.let { BrowseError(it, retry, retained = true) }
                        if (grid.value.loadingMore) Text("Loading more…", style = bodyStyle(14.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.65f))
                        else if (grid.value.hasMore && grid.value.moreError == null) PillButton("Load more", loadMore, variant = ButtonVariant.Ghost)
                    }
                }
            }
        }
    }
}

/** "20 titles" (all pages read) or "20+ titles" (more pages exist) — loaded items, never a claimed total; plus the genre filter. */
private fun browseCount(content: PagedItems, people: Boolean, state: ProductBrowseUiState): String {
    val n = content.items.size
    val noun = if (people) (if (n == 1 && !content.hasMore) "person" else "people") else (if (n == 1 && !content.hasMore) "title" else "titles")
    val genres = (state.selection as? BrowseSelection.Discover)?.genreIds.orEmpty().map { state.genres[it] ?: "Genre $it" }
    return "$n${if (content.hasMore) "+" else ""} $noun" + if (genres.isEmpty()) "" else " · filtered by ${genres.joinToString()}"
}

@Composable
private fun BrowseFilters(route: WebRoute.Browse, state: ProductBrowseUiState, navigate: (WebRoute) -> Unit, retryGenres: () -> Unit) {
    val discover = state.selection as? BrowseSelection.Discover
    fun filter(sort: DiscoverSort = discover?.sort ?: DiscoverSort.Popularity, genres: List<Int> = discover?.genreIds.orEmpty()) {
        navigate(WebRoute.Browse(route.media, "discover", sort.webValue(route.media), genres))
    }
    Panel(Modifier.fillMaxWidth(), title = "Sort") {
        var expanded by remember { mutableStateOf(false) }
        val current = discover?.sort ?: DiscoverSort.Popularity
        Box {
            PillButton(current.label(), { expanded = true }, Modifier.fillMaxWidth().testTag("browse-sort"), variant = ButtonVariant.Ghost, small = true, trailingIcon = CvIcons.ChevronDown)
            DropdownMenu(expanded, { expanded = false }, Modifier.widthIn(min = 230.dp), offset = androidx.compose.ui.unit.DpOffset(0.dp, 8.dp),
                shape = RoundedCornerShape(16.dp), containerColor = MenuFill, border = BorderStroke(1.dp, Tokens.Colors.Stroke)) {
                DiscoverSort.entries.forEach { sort ->
                    val source = remember { MutableInteractionSource() }
                    // Same kit `.menu` item and keyboard focus ring as the header menus.
                    DropdownMenuItem(text = { Text(sort.label(), style = bodyStyle(14.5.sp, FontWeight.Medium)) }, onClick = { expanded = false; filter(sort = sort) },
                        trailingIcon = { if (sort == current) Icon(CvIcons.Check, contentDescription = "Selected", Modifier.size(16.dp)) },
                        interactionSource = source, modifier = Modifier.focusRing(source, RoundedCornerShape(10.dp)))
                }
            }
        }
    }
    Panel(Modifier.fillMaxWidth(), title = "Genres") {
        if (state.genresLoading) Text("Loading genres…", style = bodyStyle(14.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.65f))
        state.genresError?.let { Box(Modifier.testTag("browse-genres-recovery")) { BrowseError(it, retryGenres) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val selected = discover?.genreIds.orEmpty()
            (state.genres.keys + selected).sorted().forEach { id ->
                val active = id in selected
                Chip(state.genres[id] ?: "Genre $id", active, {
                    if (active || selected.size < 20) filter(genres = if (active) selected - id else selected + id)
                }, Modifier.semantics { if (!active && selected.size >= 20) disabled() }, icon = if (active) CvIcons.Check else null)
            }
        }
        if (discover?.genreIds?.isNotEmpty() == true) PillButton("Clear genres", { filter(genres = emptyList()) }, Modifier.padding(top = 10.dp), variant = ButtonVariant.Ghost, small = true)
        if (discover?.genreIds?.size == 20) Text("Maximum 20 genres selected. Remove one before adding another.", Modifier.padding(top = 8.dp), style = bodyStyle(13.sp))
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

/** Kit `.menu` fill (as the header menus). */
private val MenuFill = Color(22, 19, 40).copy(alpha = 0.98f)

private val AsideWidth = 300.dp
private val AsideGap = 36.dp

/**
 * The kit's `.split` page around one lazy grid (the page's only scroll): the [header] item and the footer span the
 * full width; wide screens (> 860dp) show [aside] (filters, facets) in a 300dp column left of the grid, held below the
 * header while the grid scrolls and pushed up by the footer; phones stack it under the header.
 * Item keys "header" and "footer" are reserved.
 */
@Composable
internal fun WebAsideGrid(
    state: LazyGridState,
    columns: GridCells,
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp,
    verticalSpacing: Dp,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    aside: (@Composable ColumnScope.() -> Unit)? = null,
    content: LazyGridScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = aside != null && maxWidth > 860.dp
        val gutter = contentGutter(ShellStyle.Web, maxWidth)
        val start = if (wide) gutter + AsideWidth + AsideGap else gutter
        val bleed = Modifier.bleed(start, gutter)
        val spacing = with(LocalDensity.current) { verticalSpacing.roundToPx() }
        if (wide) {
            var height by remember { mutableIntStateOf(0) }
            // First in composition (keyboard order before the grid), drawn and hit-tested above it.
            Column(
                Modifier.zIndex(1f).padding(start = gutter).width(AsideWidth).heightIn(max = maxHeight)
                    .offset { IntOffset(0, asideTop(state.layoutInfo, height, spacing)) }
                    .onSizeChanged { height = it.height }
                    // Wheel/drag over the aside scrolls the page (the grid) once the aside itself cannot scroll.
                    .scrollable(state, Orientation.Vertical, reverseDirection = true)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) { if (aside != null) aside() }
        }
        LazyVerticalGrid(columns, modifier.fillMaxSize(), state, PaddingValues(start = start, end = gutter),
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing), verticalArrangement = Arrangement.spacedBy(verticalSpacing)) {
            if (header != null || (aside != null && !wide)) item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                WebPage(bleed) {
                    if (header != null) header()
                    if (aside != null && !wide) Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { aside() }
                }
            }
            content()
            item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { Footer(bleed.padding(top = 40.dp)) }
        }
    }
}

/** Below the header item (or the top once it has scrolled away), and never over the footer. */
private fun asideTop(info: LazyGridLayoutInfo, height: Int, spacing: Int): Int {
    val items = info.visibleItemsInfo
    val top = items.firstOrNull { it.key == "header" }?.let { it.offset.y + it.size.height + spacing } ?: 0
    val footer = items.firstOrNull { it.key == "footer" }?.offset?.y ?: return top
    return minOf(top, footer - height - spacing)
}

/** Lays a full-span lazy item across the grid's content padding (header, footer). */
private fun Modifier.bleed(start: Dp, end: Dp): Modifier = layout { measurable, constraints ->
    val s = start.roundToPx()
    val e = end.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + s + e, maxWidth = constraints.maxWidth + s + e))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-s, 0) }
}
