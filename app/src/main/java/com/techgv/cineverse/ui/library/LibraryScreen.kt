package com.techgv.cineverse.ui.library

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.techgv.cineverse.AppContainer
import com.techgv.cineverse.data.library.LibraryEntry
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.ui.components.Chip
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.FocusRestorer
import com.techgv.cineverse.ui.components.FocusRow
import com.techgv.cineverse.ui.components.PosterCard
import com.techgv.cineverse.ui.components.SectionErrorKind
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.ui.components.formatRating
import com.techgv.cineverse.ui.components.rememberSavedLazyGridState
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(container: AppContainer, onOpenTitle: (MediaRef) -> Unit, onExplore: () -> Unit, onSignIn: () -> Unit) {
    val vm: LibraryViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                LibraryViewModel(createSavedStateHandle(), container.libraryRepository)
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.revalidate()
        onPauseOrDispose { }
    }
    LibraryContent(state, vm.memory, vm::selectTab, vm::loadMore, vm::retry, onOpenTitle, onExplore, onSignIn)
}

/**
 * §7.8 layout: tabs (switch on focus, OK enters the grid or CTA), then one 7-column grid with a "Movies" and
 * a "TV Shows" group; the screen-level empty state + CTAs only when both groups are empty.
 */
@Composable
fun LibraryContent(
    state: LibraryUiState,
    memory: FocusMemory,
    onTab: (LibraryTab) -> Unit,
    onLoadMore: (MediaType) -> Unit,
    onRetry: (MediaType) -> Unit,
    onOpenTitle: (MediaRef) -> Unit,
    onExplore: () -> Unit,
    onSignIn: () -> Unit,
) {
    val restorer = remember(memory) { FocusRestorer(memory) }
    val scope = rememberCoroutineScope()
    val gridState = rememberSavedLazyGridState(memory, "grid:${state.tab.name}")
    val tabFocus = remember { LibraryTab.entries.associateWith { FocusRequester() } }
    val groups = listOf(MediaType.Movie to state.movies, MediaType.Tv to state.tv)
    val gridItems = libraryGridItems(state)
    // Restore scrolls by lazy-item index: headers and status rows sit between the cards.
    val cards = gridItems.withIndex().filter { it.value is LibraryGridItem.Card }
    val gridRow = FocusRow(GRID, state.tab.name, cards.map { it.value.key }, gridState = gridState, lazyIndices = cards.map { it.index })
    val tabsRow = FocusRow(TABS, TABS, LibraryTab.entries.map { it.name })
    val ready = groups.none { it.second.section == SectionState.Loading }
    val empty = ready && groups.all { it.second.section == SectionState.Empty }
    // CTAs and "Try again" buttons are remembered too: focus on them must survive re-entry, not fall back to a tab.
    val bodyRow = FocusRow(BODY, BODY, bodyKeys(state, empty))
    val rememberBody: (String) -> Modifier = { key -> with(restorer) { Modifier.rememberFocus(BODY, BODY, key, bodyRow.itemKeys.indexOf(key)) } }
    // UP from the body's top row goes to the selected tab. Geometric search would pick the rail when its items sit
    // just above-left (1080p at 320 dpi) and the tabs are far above, or a non-selected tab (which would switch tabs).
    val upToTab = Modifier.focusProperties { up = tabFocus.getValue(state.tab) }

    /** OK on a tab: the first card, else the first CTA / "Try again". */
    fun focusBody() {
        scope.launch { restorer.restore(listOf(gridRow, bodyRow)) }
    }

    /** Until the first restore finished, focus landing on a tab must not switch tabs. */
    var restoring by remember { mutableStateOf(true) }
    LaunchedEffect(ready) {
        if (!ready || !restoring) return@LaunchedEffect
        val saved = memory.saved
        restorer.restore(listOf(gridRow, bodyRow, tabsRow))
        // AppNav's content focusRestorer may redirect this first entry to the child it remembered: re-apply once.
        if (saved != null && memory.saved != saved) {
            memory.save(saved)
            restorer.restore(listOf(gridRow, bodyRow, tabsRow))
        }
        restoring = false
    }

    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 27.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("My Library", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(
            if (state.signedIn) "Signed in as ${state.accountName} · synced with TMDB" else "Guest · saved on this TV only",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Entering the tabs (UP from the grid) lands on the selected tab: focusing another one would switch tabs.
        Row(
            Modifier.focusProperties { onEnter = { tabFocus.getValue(state.tab).requestFocus() } }.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LibraryTab.entries.forEachIndexed { index, tab ->
                Chip(
                    label = tab.label,
                    selected = tab == state.tab,
                    onClick = ::focusBody,
                    modifier = with(restorer) { Modifier.rememberFocus(TABS, TABS, tab.name, index) }
                        .focusRequester(tabFocus.getValue(tab))
                        .testTag("tab:${tab.name}")
                        .onFocusChanged { if (it.isFocused && !restoring) onTab(tab) },
                )
            }
        }
        if (empty) {
            EmptyLibrary(state, { key -> rememberBody(key).then(upToTab) }, onExplore, onSignIn)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(COLUMNS),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
                modifier = with(restorer) { Modifier.restoreRowFocus(gridRow, scope) }.testTag(GRID),
            ) {
                items(gridItems, key = { it.key }, span = { if (it is LibraryGridItem.Card) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) { item ->
                    val topUp = if (item.topRow) upToTab else Modifier
                    when (item) {
                        is LibraryGridItem.Header -> Text(
                            if (item.media == MediaType.Movie) "Movies" else "TV Shows",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        is LibraryGridItem.Loading -> Message("Loading…")
                        is LibraryGridItem.Error ->
                            ErrorBlock(item.kind.message, item.kind.retryable, { onRetry(item.media) }, rememberBody("retry:${item.media}").then(topUp))
                        is LibraryGridItem.RefreshError -> ErrorBlock(
                            "Couldn't refresh — ${item.kind.message}", item.kind.retryable, { onRetry(item.media) },
                            rememberBody("refresh:${item.media}").then(topUp),
                        )
                        is LibraryGridItem.Card -> {
                            if (item.loadsMore) LaunchedEffect(item.focusIndex) { onLoadMore(item.media) }
                            val entry = item.entry
                            PosterCard(
                                title = entry.title,
                                onClick = { onOpenTitle(entry.title.ref) },
                                modifier = with(restorer) { Modifier.rememberFocus(GRID, state.tab.name, item.key, item.focusIndex) }
                                    .then(topUp)
                                    .testTag(item.key),
                                // Only Rated cards carry the badge; v4 watchlist / favourite items may also have account_rating.
                                badge = entry.rating?.takeIf { state.tab == LibraryTab.Rated }?.let { "★ ${formatRating(it)}" },
                            )
                        }
                        is LibraryGridItem.LoadingMore -> Message("Loading more…")
                        is LibraryGridItem.MoreError ->
                            ErrorBlock(item.kind.message, item.kind.retryable, { onRetry(item.media) }, rememberBody("more:${item.media}"))
                    }
                }
            }
        }
    }
}

/** One lazy grid item in display order; [key] is its lazy key, [topRow]: UP goes to the selected tab. */
internal sealed interface LibraryGridItem {
    val key: String
    val topRow: Boolean get() = false

    data class Header(val media: MediaType) : LibraryGridItem {
        override val key get() = "header:$media"
    }

    data class Loading(val media: MediaType) : LibraryGridItem {
        override val key get() = "loading:$media"
    }

    data class Error(val media: MediaType, val kind: SectionErrorKind, override val topRow: Boolean) : LibraryGridItem {
        override val key get() = "error:$media"
    }

    /** Above the (possibly stale) cards of a group whose revalidation failed. */
    data class RefreshError(val media: MediaType, val kind: SectionErrorKind, override val topRow: Boolean) : LibraryGridItem {
        override val key get() = "refreshError:$media"
    }

    /** [focusIndex]: position among all cards (focus memory); [loadsMore]: last loaded card of a group with more pages. */
    data class Card(val media: MediaType, val entry: LibraryEntry, val focusIndex: Int, override val topRow: Boolean, val loadsMore: Boolean) :
        LibraryGridItem {
        override val key get() = entry.title.ref.key
    }

    data class LoadingMore(val media: MediaType) : LibraryGridItem {
        override val key get() = "more:$media"
    }

    data class MoreError(val media: MediaType, val kind: SectionErrorKind) : LibraryGridItem {
        override val key get() = "moreError:$media"
    }
}

/** The grid's items: per non-empty group a header, then its loading / error row or its cards and footer rows. */
internal fun libraryGridItems(state: LibraryUiState): List<LibraryGridItem> = buildList {
    var cards = 0
    var firstGroup = true
    for (media in MediaType.entries) {
        val group = state.group(media)
        if (group.section == SectionState.Empty) continue
        val top = firstGroup
        firstGroup = false
        add(LibraryGridItem.Header(media))
        when (val section = group.section) {
            SectionState.Loading, SectionState.Empty -> add(LibraryGridItem.Loading(media))
            is SectionState.Error -> add(LibraryGridItem.Error(media, section.kind, top))
            is SectionState.Content -> {
                group.refreshError?.let { add(LibraryGridItem.RefreshError(media, it, top)) }
                val cardsTop = top && group.refreshError == null
                section.value.forEachIndexed { i, entry ->
                    add(LibraryGridItem.Card(media, entry, cards++, cardsTop && i < COLUMNS, i == section.value.lastIndex && group.hasMore))
                }
                if (group.loadingMore) add(LibraryGridItem.LoadingMore(media))
                group.moreError?.let { add(LibraryGridItem.MoreError(media, it)) }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(state: LibraryUiState, focusKey: (String) -> Modifier, onExplore: () -> Unit, onSignIn: () -> Unit) {
    val title = when (state.tab) {
        LibraryTab.Watchlist -> "Your watchlist is empty"
        LibraryTab.Favourites -> "No favourites yet"
        LibraryTab.Rated -> "Nothing rated yet"
    }
    val text = "Press OK on Watchlist, Favourite or Rate on any title and it will show up here." +
        if (state.signedIn) "" else " Sign in to keep it in sync with your TMDB account."
    Column(Modifier.widthIn(max = 620.dp).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onExplore, modifier = focusKey(EXPLORE).testTag(EXPLORE_TAG)) { Text("Explore popular movies") }
            if (!state.signedIn) OutlinedButton(onClick = onSignIn, modifier = focusKey(SIGN_IN).testTag(SIGN_IN_TAG)) { Text("Sign in with TMDB") }
        }
    }
}

@Composable
private fun ErrorBlock(message: String, retryable: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Message(message)
        if (retryable) Button(onClick = onRetry, modifier = modifier) { Text("Try again") }
    }
}

/** Focusable controls outside the cards, in display order: empty-state CTAs or the groups' "Try again" buttons. */
private fun bodyKeys(state: LibraryUiState, empty: Boolean): List<String> = buildList {
    if (empty) {
        add(EXPLORE)
        if (!state.signedIn) add(SIGN_IN)
        return@buildList
    }
    for (media in MediaType.entries) {
        val group = state.group(media)
        if ((group.section as? SectionState.Error)?.kind?.retryable == true) add("retry:$media")
        if (group.section is SectionState.Content && group.refreshError?.retryable == true) add("refresh:$media")
        if (group.moreError?.retryable == true) add("more:$media")
    }
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal const val EXPLORE_TAG = "library:explore"
internal const val SIGN_IN_TAG = "library:signIn"
private const val GRID = "grid"
private const val TABS = "tabs"
private const val BODY = "body"
private const val EXPLORE = "explore"
private const val SIGN_IN = "signIn"
private const val COLUMNS = 7
