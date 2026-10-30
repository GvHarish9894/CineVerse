package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*
import kotlinx.coroutines.flow.distinctUntilChanged

/** Parent owns entry visibility/resume via model.setActive; callbacks must be real root actions. */
@Composable
internal fun MobileLibraryScreen(
    graph: MobileApplication,
    model: MobileLibraryViewModel,
    scrollToTop: Int,
    navigator: MobileNavigator,
    onSignIn: () -> Unit,
    onExplore: () -> Unit,
) {
    val snapshot by model.state.collectAsState()
    // Recheck authoritative values in the render pass as well as in every dispatched callback.
    val identity by graph.identity.collectAsState()
    val settings by graph.settings.collectAsState()
    val identityFailed by graph.identityFailed.collectAsState()
    val settingsFailed by graph.settingsFailed.collectAsState()
    val storageFailed by graph.storageFailed.collectAsState()
    val state = remember(snapshot, identity, settings, identityFailed, settingsFailed, storageFailed, graph.generation.current) { model.visibleState(snapshot) }
    key(state.context) {
        val scroll = rememberLazyGridState()
        var applied by remember { mutableStateOf(false) }
        var handledTop by remember { mutableIntStateOf(scrollToTop) }
        LaunchedEffect(state.scrollReady, scrollToTop) {
            if (state.scrollReady) {
                val top = handledTop != scrollToTop
                handledTop = scrollToTop
                scroll.scrollToItem(if (top) 0 else state.position.index, if (top) 0 else state.position.offset)
                applied = true
            } else applied = false
        }
        LaunchedEffect(applied) {
            if (applied) snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }
                .distinctUntilChanged().collect { (index, offset) -> model.saveScroll(state.context, index, offset) }
        }
        MobileLibraryContent(state, graph.catalog.imageBaseUrl(),
            { model.selectTab(state.context, it) }, { model.loadMore(state.context, it) },
            { model.retry(state.context, it) }, { model.retryStorage(state.context) },
            { model.open(state.context, it, navigator) }, { model.signIn(state.context, onSignIn) },
            { model.explore(state.context, onExplore) }, { model.refresh(state.context) }, scroll)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileLibraryContent(
    state: MobileLibraryState,
    baseUrl: String,
    onTab: (LibraryTab) -> Unit,
    onMore: (MediaType) -> Unit,
    onRetry: (MediaType) -> Unit,
    onStorageRetry: () -> Unit,
    onOpen: (MediaRef) -> Unit,
    onSignIn: () -> Unit,
    onExplore: () -> Unit,
    onRefresh: () -> Unit,
    scroll: LazyGridState = rememberLazyGridState(),
) {
    val library = state.library.takeUnless { state.invalid || state.failed }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        AmbientGlow(mobilePalette("library"))
        PullToRefreshBox(library != null && (library.movies.section == SectionState.Loading || library.tv.section == SectionState.Loading),
            onRefresh, Modifier.fillMaxSize()) {
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().testTag("mobile-library"), scroll,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item("header", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle("Library")
                        if (library != null) {
                            Text(if (library.signedIn) "TMDB account · ${library.accountName.orEmpty()}" else "Saved on this device · Guest")
                            SegmentedControl(LibraryTab.entries.map { it.label }, LibraryTab.entries.indexOf(library.tab), { onTab(LibraryTab.entries[it]) })
                            val movies = library.movies.loadedCount()
                            val tv = library.tv.loadedCount()
                            if (movies != null && tv != null) Text("${library.tab.label} · ${movies + tv} loaded")
                            if (!library.signedIn) {
                                Text("Sign in to sync your Library with TMDB.")
                                PillButton("Sign in with TMDB", onSignIn)
                            }
                        }
                    }
                }
                when {
                    state.invalid -> item("invalid", span = { GridItemSpan(maxLineSpan) }) { Text("Invalid saved Library state. Leave it unchanged.") }
                    state.failed -> item("storage", span = { GridItemSpan(maxLineSpan) }) {
                        ErrorState("Library unavailable. Saved preferences or account data could not be read.",
                            onStorageRetry, Modifier.testTag("library-storage-retry"))
                    }
                    library == null -> item("unknown", span = { GridItemSpan(maxLineSpan) }) { Text("Loading Library…") }
                    else -> {
                        MediaType.entries.forEach { media ->
                            val group = library.group(media)
                            val label = if (media == MediaType.Movie) "Movies" else "TV shows"
                            item("${media.name}-header", span = { GridItemSpan(maxLineSpan) }) {
                                SectionTitle(group.loadedCount()?.let { "$label · $it loaded" } ?: label)
                            }
                            when (val section = group.section) {
                                SectionState.Loading -> items(3, key = { "${media.name}-loading-$it" }) { PosterCardSkeleton(Modifier.fillMaxWidth()) }
                                SectionState.Empty -> item("${media.name}-empty", span = { GridItemSpan(maxLineSpan) }) { Text("No ${label.lowercase()} in ${library.tab.label.lowercase()}.") }
                                is SectionState.Error -> item("${media.name}-error", span = { GridItemSpan(maxLineSpan) }) {
                                    LibraryGroupRetry(media, "Couldn't load ${label.lowercase()}.", onRetry)
                                }
                                is SectionState.Content -> items(section.value, key = { "${media.name}-${it.title.ref.id}" }) { entry ->
                                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                                        PosterCard(entry.title.title, titleMeta(entry.title), mobilePalette("${media.name}-${entry.title.ref.id}"),
                                            { onOpen(entry.title.ref) }, width = maxWidth,
                                            imageUrl = imageUrl(baseUrl, "w342", entry.title.posterPath),
                                            voteAverage = entry.title.voteAverage, voteCount = entry.title.voteCount,
                                            badge = if (library.tab == LibraryTab.Rated) entry.rating?.let { "★ $it" } else null)
                                    }
                                }
                            }
                            if (group.moreError != null || group.refreshError != null) item("${media.name}-retry", span = { GridItemSpan(maxLineSpan) }) {
                                LibraryGroupRetry(media, if (group.refreshError != null) "Couldn't refresh ${label.lowercase()}; loaded items may be stale."
                                    else "Couldn't load more ${label.lowercase()}.", onRetry)
                            }
                            else if (group.loadingMore) item("${media.name}-more-loading", span = { GridItemSpan(maxLineSpan) }) { Text("Loading more ${label.lowercase()}…") }
                            else if (group.hasMore) item("${media.name}-more", span = { GridItemSpan(maxLineSpan) }) {
                                PillButton("Load more $label", { onMore(media) })
                            }
                        }
                        if (library.movies.section == SectionState.Empty && library.tv.section == SectionState.Empty) item("browse", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState("Your ${library.tab.label.lowercase()} is empty", "Find your next favourite.") {
                                PillButton("Explore movies", onExplore)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryGroupRetry(media: MediaType, message: String, onRetry: (MediaType) -> Unit) {
    ErrorState(message, { onRetry(media) }, Modifier.testTag("library-${media.name}-retry"))
}

private fun LibraryGroup.loadedCount(): Int? = when (val value = section) {
    is SectionState.Content -> value.value.size
    SectionState.Empty -> 0
    else -> null
}
