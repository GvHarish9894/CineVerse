package com.techgv.cineverse.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.techgv.cineverse.ui.haptics.HapticKind
import com.techgv.cineverse.ui.theme.bodyStyle
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
    Column(Modifier.fillMaxSize()) {
        // The field stays outside the lazy results so typing never loses focus to item disposal.
        WebPage {
            WebPageTitle("Search")
            Box(Modifier.padding(top = 22.dp).widthIn(max = 820.dp).fillMaxWidth()) {
                WebSearchField(query, change, submit, Modifier.fillMaxWidth().testTag("search-input").onFocusChanged { focused(it.isFocused) },
                    invalid = invalidUrl || state.inputError != null, large = true)
                if (query.isNotEmpty()) RoundIconButton(CvIcons.Close, "Clear search", { change("") }, Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                    diameter = 32.dp, container = Color.White.copy(alpha = 0.10f))
            }
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (invalidUrl) Text(SEARCH_URL_ERROR, color = MaterialTheme.colorScheme.error)
                else if (state.inputError != null) Text("Search is limited to 200 characters after trimming. Edit or clear your input; nothing was truncated.", color = MaterialTheme.colorScheme.error)
                if (storage != null) WebStorageNotice(storage, retryStorage, Modifier.testTag("search-storage-recovery"))
                if (state.settingsError != null) ErrorState("Saved preferences could not be observed. Last loaded values are retained.", retryStorage, Modifier.testTag("search-settings-recovery"))
                if (state.recentReadError != null) ErrorState("Recent searches could not be read. Saved data has not been reset.", retryStorage, Modifier.testTag("search-recent-recovery"))
                when (state.recentMutation) {
                    RecentMutationStatus.Pending -> Text("Saving recent searches…", style = bodyStyle(14.sp), color = Muted)
                    RecentMutationStatus.Failed -> Text("Recent searches could not be updated. Nothing is retried automatically; choose the search or Clear again.", style = bodyStyle(14.sp))
                    RecentMutationStatus.Succeeded, RecentMutationStatus.Idle -> Unit
                }
            }
        }
        if (!invalidUrl && state.inputError == null) Box(Modifier.weight(1f).padding(top = 12.dp)) {
            SearchResults(entry, state, choose, clearRecent, facet, loadMore, retry, navigate)
        } else Spacer(Modifier.weight(1f))
    }
}

private val Muted = Tokens.Colors.TextPrimary.copy(alpha = 0.65f)

/** The badge for [facet]: loaded results of the active facet ("20", or "20+" while more pages exist); null when unknown. */
private fun ProductSearchUiState.facetCount(facet: SearchFacet): String? = loadedCount(facet)?.let { n ->
    if ((results as? SectionState.Content)?.value?.hasMore == true) "$n+" else "$n"
}

@Composable
private fun SearchFacets(state: ProductSearchUiState, wide: Boolean, select: (SearchFacet) -> Unit) {
    val facets = SearchFacet.entries.map { facet ->
        facet to when (facet) { SearchFacet.Multi -> "All"; SearchFacet.Movie -> "Movies"; SearchFacet.Tv -> "TV shows"; SearchFacet.Person -> "People" }
    }
    if (!wide) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        facets.forEach { (facet, label) ->
            Chip(state.facetCount(facet)?.let { "$label · $it" } ?: label, facet == state.facet, { select(facet) }, Modifier.testTag("search-facet-${facet.webValue()}"))
        }
    } else Panel(Modifier.fillMaxWidth(), title = "Search results") {
        facets.forEach { (facet, label) ->
            val selected = facet == state.facet
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().testTag("search-facet-${facet.webValue()}")
                    .cvClickable({ select(facet) }, role = Role.Tab, haptic = HapticKind.Select, focusShape = shape, pressedScale = 1f)
                    .semantics { this.selected = selected }
                    .clip(shape).background(if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                    .heightIn(min = 44.dp).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = bodyStyle(15.sp, FontWeight.SemiBold))
                state.facetCount(facet)?.let {
                    Text(it, style = bodyStyle(12.5.sp, FontWeight.Bold), modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun SearchResults(entry: WebEntry, state: ProductSearchUiState, choose: (String) -> Unit, clearRecent: () -> Unit, facet: (SearchFacet) -> Unit,
                          loadMore: () -> Unit, retry: () -> Unit, navigate: (WebRoute) -> Unit) {
    val source = state.normalizedQuery to state.facet
    val targetPage = remember(entry, source) { entry.getInt("search.page") ?: 1 }
    val targetIndex = remember(entry, source) { entry.getInt("search.index") ?: 0 }
    val targetOffset = remember(entry, source) { entry.getInt("search.offset") ?: 0 }
    val scroll = remember(entry, source) { LazyGridState() }
    var restored by remember(entry, source) { mutableStateOf(false) }
    val content = (state.results as? SectionState.Content)?.value
    LaunchedEffect(content, restored) {
        if (restored || content == null || content.loadingMore || content.moreError != null) return@LaunchedEffect
        if (content.page < targetPage && content.hasMore) loadMore()
        else { scroll.scrollToItem(targetIndex.coerceAtMost(content.items.size + 2), targetOffset); restored = true }
    }
    LaunchedEffect(scroll, restored) {
        if (restored) snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { (index, offset) ->
            entry.putInt("search.index", index.coerceAtMost(100000)); entry.putInt("search.offset", offset.coerceAtMost(100000))
        }
    }
    LaunchedEffect(content?.page, restored) { if (restored && content != null) entry.putInt("search.page", content.page) }
    val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
    WebAsideGrid(scroll, GridCells.Fixed(1), Modifier.testTag("search-results"), horizontalSpacing = 0.dp, verticalSpacing = 14.dp,
        aside = { wide -> SearchFacets(state, wide, facet) }) {
        if (state.showingSuggestions) item(key = "recent", span = full) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Recent searches", style = bodyStyle(16.sp, FontWeight.SemiBold))
                val recentQueries = state.recentQueries
                // Null is not yet read (or failed, shown above): never presented as an empty history.
                if (recentQueries?.isEmpty() == true) Text("No saved recent searches.", style = bodyStyle(14.sp), color = Muted)
                else if (recentQueries != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recentQueries.forEach { Chip(it, false, { choose(it) }) }
                }
                if (recentQueries?.isEmpty() != true) PillButton("Clear recent searches", clearRecent, small = true, variant = ButtonVariant.Ghost)
            }
        }
        val count = when {
            state.showingSuggestions -> "Trending this week"
            content != null -> "${content.items.size}${if (content.hasMore) "+" else ""} ${if (content.items.size == 1 && !content.hasMore) "result" else "results"} for “${state.normalizedQuery}”"
            else -> null
        }
        if (count != null) item(key = "count", span = full) {
            Text(count, style = bodyStyle(14.sp), color = Muted, modifier = Modifier.padding(top = if (state.showingSuggestions) 12.dp else 0.dp).testTag("search-count"))
        }
        when (val result = state.results) {
            null, SectionState.Loading -> items(3, key = { "skeleton-$it" }) { SearchRowSkeleton() }
            SectionState.Empty -> item(key = "empty", span = full) {
                EmptyState("No matches", "There are no movies, shows or people that match your search. Try a shorter word.") { PillButton("Refresh results", retry, variant = ButtonVariant.Ghost) }
            }
            is SectionState.Error -> item(key = "error", span = full) { SearchError(result.kind, retry) }
            is SectionState.Content -> {
                items(result.value.items, key = { it.key }) { hero -> SearchResultRow(hero) { navigate(hero.route()) } }
                item(key = "more", span = full) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        result.value.moreError?.let { SearchError(it, retry, true) }
                        if (result.value.loadingMore) Text("Loading more…", style = bodyStyle(14.sp), color = Muted)
                        else if (result.value.hasMore && result.value.moreError == null) PillButton("Load more", loadMore, variant = ButtonVariant.Ghost)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRowSkeleton() {
    Row(Modifier.fillMaxWidth().testTag("search-loading").padding(12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.width(86.dp).aspectRatio(2f / 3f).shimmer(RoundedCornerShape(12.dp)))
        SkeletonLines(3, Modifier.weight(1f).padding(top = 4.dp))
    }
}

/** `.sres`: 86dp poster (or round portrait with initials), title, "Movie · Mar 1, 2022 · ★ 7.7", two-line overview. */
@Composable
private fun SearchResultRow(hero: Hero, open: () -> Unit) {
    val person = (hero as? Hero.OfPerson)?.person
    val title = (hero as? Hero.OfTitle)?.title
    val name = person?.name ?: title!!.title
    val meta = if (person != null) listOfNotNull("Person", person.knownForDepartment?.takeIf { it.isNotBlank() })
        else listOfNotNull(if (title!!.mediaType == MediaType.Tv) "TV series" else "Movie", formatDate(title.releaseDate) ?: yearOf(title.releaseDate),
            if (title.voteCount > 0) "★ ${oneDecimal(title.voteAverage)}" else "NR")
    val overview = if (person != null) person.knownFor.take(3).joinToString { it.title }.takeIf { it.isNotEmpty() }?.let { "Known for $it" } else title!!.overview.takeIf { it.isNotBlank() }
    val shape = RoundedCornerShape(18.dp)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().testTag("search-${hero.key}").cvClickable(open, onClickLabel = name, focusShape = shape, pressedScale = 1f, interactionSource = source)
            .clip(shape).background(Color.White.copy(alpha = if (hovered) 0.08f else 0.04f)).border(1.dp, Color.White.copy(alpha = 0.06f), shape).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val palette = ArtPalette(Tokens.Colors.Accent, Tokens.Colors.Secondary)
        if (person != null) PaletteArt(webImage(person.profilePath, "w185"), palette, ArtStyle.Person, Modifier.size(86.dp).clip(CircleShape)) {
            Text(initialsOf(person.name), Modifier.align(Alignment.Center), style = displayStyle(26.sp), color = Color.White)
        } else PaletteArt(webImage(title!!.posterPath, "w185"), palette, ArtStyle.Poster, Modifier.width(86.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(name, style = bodyStyle(18.sp, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(meta.joinToString(" · "), Modifier.padding(top = 2.dp), style = bodyStyle(13.5.sp), color = Muted)
            if (overview != null) Text(overview, Modifier.padding(top = 8.dp), style = bodyStyle(14.5.sp).copy(lineHeight = 22.sp),
                color = Tokens.Colors.TextPrimary.copy(alpha = 0.82f), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
