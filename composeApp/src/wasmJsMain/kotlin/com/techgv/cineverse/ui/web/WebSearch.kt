package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.search.RecentQueryStore
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.displayStyle
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** One logical history entry, including in-place query replacements; never cached across entries. */
internal class WebSearchOwner(
    val entry: WebEntry, catalog: CatalogRepository, generation: GenerationCounter,
    settings: Flow<Settings>, recent: RecentQueryStore, scope: CoroutineScope,
    private val navigate: (WebRoute, Boolean) -> Unit, onStorageFailure: (StorageException) -> Unit,
) {
    val holder = ProductSearchStateHolder(catalog, generation, settings, recent, entry, scope, onStorageFailure)
    private var invalidDraft: String? by mutableStateOf(null)
    private var disposed = false
    val query: String get() = invalidDraft ?: (entry.route as WebRoute.Search).query
    val invalidUrl: Boolean get() = invalidDraft != null

    fun change(query: String) {
        if (disposed) return
        val next = (entry.route as WebRoute.Search).copy(query = query)
        if (!next.representable()) { invalidDraft = query; return }
        navigate(next, true)
        invalidDraft = null
        holder.setInput(next.query, next.searchFacet())
    }
    fun facet(facet: SearchFacet) {
        if (disposed || invalidUrl) return
        val next = (entry.route as WebRoute.Search).copy(facet = facet.webValue())
        navigate(next, true)
        holder.setInput(next.query, facet)
    }
    fun submit() { if (!disposed && !invalidUrl) holder.commitQuery() }
    fun choose(query: String) { if (!disposed && !invalidUrl) { change(query); submit() } }
    fun dispose() { disposed = true; holder.dispose() }
}

@Composable
internal fun WebSearchEditor(query: String, change: (String) -> Unit, submit: () -> Unit, modifier: Modifier = Modifier, invalid: Boolean = false, enabled: Boolean = true) {
    var editor by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    // External URL/other-editor updates replace text, not same-text selection or IME composition.
    LaunchedEffect(query) { if (editor.text != query) editor = TextFieldValue(query, TextRange(query.length)) }
    OutlinedTextField(editor, { if (enabled) { editor = it; change(it.text) } }, modifier, enabled = enabled, singleLine = true,
        label = { Text("Search movies, TV and people") }, isError = invalid,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (enabled) submit() }))
}

@Composable
internal fun WebSearch(owner: WebSearchOwner, storage: BrowserStorageFailure?, retryStorage: () -> Unit,
                       navigate: (WebRoute, Boolean) -> Unit, focused: (Boolean) -> Unit) {
    val state by owner.holder.uiState.collectAsState()
    WebSearchContent(owner.entry, state, owner.query, owner.invalidUrl, owner::change, owner::submit, owner::choose, owner::facet,
        owner.holder::clearRecentQueries, owner.holder::loadMore, owner.holder::retry,
        retryStorage, storage, { navigate(it, false) }, focused)
}

@Composable
internal fun WebSearchContent(entry: WebEntry, state: ProductSearchUiState, query: String, invalidUrl: Boolean,
                              change: (String) -> Unit, submit: () -> Unit, choose: (String) -> Unit, facet: (SearchFacet) -> Unit,
                              clearRecent: () -> Unit, loadMore: () -> Unit, retry: () -> Unit, retryStorage: () -> Unit,
                              storage: BrowserStorageFailure?, navigate: (WebRoute) -> Unit, focused: (Boolean) -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 1200.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Search", style = displayStyle(48.sp), modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WebSearchEditor(query, change, submit, Modifier.weight(1f).heightIn(min = 60.dp).testTag("search-input").onFocusChanged { focused(it.isFocused) }, invalidUrl || state.inputError != null)
                PillButton("Clear", { change("") }, variant = ButtonVariant.Ghost)
                PillButton("Search", submit, enabled = !invalidUrl && state.inputError == null && query.isNotBlank())
            }
            if (invalidUrl) Text(SEARCH_URL_ERROR, color = MaterialTheme.colorScheme.error)
            else if (state.inputError != null) Text("Search is limited to 200 characters after trimming. Edit or clear your input; nothing was truncated.", color = MaterialTheme.colorScheme.error)
            if (storage != null) WebStorageNotice(storage, retryStorage, Modifier.testTag("search-storage-recovery"))
            if (state.settingsError != null) ErrorState("Saved preferences could not be observed. Last loaded values are retained.", retryStorage, Modifier.testTag("search-settings-recovery"))
            if (state.recentReadError != null) ErrorState("Recent searches could not be read. Saved data has not been reset.", retryStorage, Modifier.testTag("search-recent-recovery"))
            when (state.recentMutation) {
                RecentMutationStatus.Pending -> Text("Saving recent-search action…")
                RecentMutationStatus.Failed -> Text("Recent-search action failed. It will not be replayed; retry reads or explicitly choose the action again.")
                RecentMutationStatus.Succeeded -> Text("Recent-search action saved.")
                RecentMutationStatus.Idle -> Unit
            }
        }
        if (!invalidUrl && state.inputError == null) BoxWithConstraints(Modifier.widthIn(max = 1200.dp).fillMaxWidth().weight(1f)) {
            val wide = maxWidth > 860.dp
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (wide) Column(Modifier.width(200.dp)) { SearchFacets(state, facet) }
                Column(Modifier.weight(1f)) {
                    if (!wide) SearchFacets(state, facet)
                    if (state.showingSuggestions) {
                        Text("Recent searches", style = MaterialTheme.typography.titleMedium)
                        val recentQueries = state.recentQueries
                        when {
                            recentQueries == null -> Text("Recent searches are unknown until saved data is read.")
                            recentQueries.isEmpty() -> Text("No saved recent searches.")
                            else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                recentQueries.forEach { Chip(it, false, { choose(it) }) }
                            }
                        }
                        PillButton("Clear recent searches", clearRecent, small = true, variant = ButtonVariant.Ghost)
                        Text("Trending titles this week", style = MaterialTheme.typography.titleMedium)
                        Text("Title suggestions from TMDB, not trending search queries.")
                    }
                    SearchResults(entry, state, loadMore, retry, navigate)
                }
            }
        } else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SearchFacets(state: ProductSearchUiState, select: (SearchFacet) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchFacet.entries.forEach { facet ->
            val label = when (facet) { SearchFacet.Multi -> "All"; SearchFacet.Movie -> "Movies"; SearchFacet.Tv -> "TV shows"; SearchFacet.Person -> "People" }
            val count = state.loadedCount(facet)?.let { "$it loaded" } ?: "unknown"
            Chip("$label · $count", facet == state.facet, { select(facet) }, Modifier.testTag("search-facet-${facet.webValue()}"))
        }
    }
}

@Composable
private fun SearchResults(entry: WebEntry, state: ProductSearchUiState, loadMore: () -> Unit, retry: () -> Unit, navigate: (WebRoute) -> Unit) {
    val source = state.normalizedQuery to state.facet
    val targetPage = remember(entry, source) { entry.getInt("search.page") ?: 1 }
    val targetIndex = remember(entry, source) { entry.getInt("search.index") ?: 0 }
    val targetOffset = remember(entry, source) { entry.getInt("search.offset") ?: 0 }
    val scroll = remember(entry, source) { LazyListState() }
    var restored by remember(entry, source) { mutableStateOf(false) }
    val content = (state.results as? SectionState.Content)?.value
    LaunchedEffect(content, restored) {
        if (restored || content == null || content.loadingMore || content.moreError != null) return@LaunchedEffect
        if (content.page < targetPage && content.hasMore) loadMore()
        else { scroll.scrollToItem(targetIndex.coerceAtMost(content.items.size), targetOffset); restored = true }
    }
    LaunchedEffect(scroll, restored) {
        if (restored) snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { (index, offset) ->
            entry.putInt("search.index", index.coerceAtMost(100000)); entry.putInt("search.offset", offset.coerceAtMost(100000))
        }
    }
    LaunchedEffect(content?.page, restored) { if (restored && content != null) entry.putInt("search.page", content.page) }
    LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("search-results"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val result = state.results) {
            null -> item { Text("Waiting for saved preferences…") }
            SectionState.Loading -> item { Text("Loading results…") }
            SectionState.Empty -> item { EmptyState("No results", "No matches were returned for this source.") { PillButton("Refresh results", retry) } }
            is SectionState.Error -> item { SearchError(result.kind, retry) }
            is SectionState.Content -> {
                items(result.value.items, key = { it.key }) { hero -> SearchResultRow(hero) { navigate(hero.route()) } }
                item {
                    Column {
                        result.value.moreError?.let { SearchError(it, retry, true) }
                        if (result.value.loadingMore) Text("Loading more results…")
                        else if (result.value.hasMore && result.value.moreError == null) PillButton("Load more", loadMore)
                    }
                }
            }
        }
        item { Footer() }
    }
}

@Composable
private fun SearchResultRow(hero: Hero, open: () -> Unit) {
    val person = (hero as? Hero.OfPerson)?.person
    val title = (hero as? Hero.OfTitle)?.title
    Row(Modifier.fillMaxWidth().testTag("search-${hero.key}").cvClickable(open, focusShape = RoundedCornerShape(12.dp)).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        FilledArt(webImage(person?.profilePath ?: title?.posterPath, "w185"), ArtPalette(Tokens.Colors.Accent, Tokens.Colors.Secondary), (if (person != null) ArtStyle.Person else ArtStyle.Poster),
            RoundedCornerShape(if (person != null) 50.dp else 8.dp), Modifier.width(86.dp).height(112.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(person?.name ?: title!!.title, style = MaterialTheme.typography.titleLarge)
            Text(if (person != null) "Person · ${person.knownForDepartment.orEmpty()}" else "${title!!.mediaType} · ${title.releaseDate.orEmpty()} · ${if (title.voteCount > 0) "★ ${oneDecimal(title.voteAverage)}" else "NR"}")
            Text(person?.knownFor?.joinToString { it.title } ?: title!!.overview, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SearchError(kind: SectionErrorKind, retry: () -> Unit, retained: Boolean = false) {
    val message = when (kind) {
        SectionErrorKind.Offline -> "Offline. Check your connection."
        SectionErrorKind.NotAvailable -> "These results are not available."
        SectionErrorKind.AppCredential -> "Catalog service configuration is unavailable."
        SectionErrorKind.SessionExpired -> "Session expired. Verification is required."
        SectionErrorKind.Generic -> "Could not load results."
    } + if (retained) " Loaded results are retained." else ""
    if (kind.retryable) ErrorState(message, retry) else Text(message)
}
