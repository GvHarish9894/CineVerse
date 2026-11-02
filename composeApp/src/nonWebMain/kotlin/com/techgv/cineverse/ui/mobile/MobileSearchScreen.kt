package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.tmdb.SearchFacet
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.bodyStyle

@Composable
internal fun MobileSearchScreen(
    graph: MobileApplication,
    model: MobileSearchViewModel,
    scrollToTop: Int,
    navigator: MobileNavigator,
    onInvalidBack: () -> Unit,
) {
    val search = model.search
    if (search == null) {
        MobilePlaceholder("Search unavailable", "Invalid Search state. Go back to leave it unchanged.", onInvalidBack)
        return
    }
    val state by search.uiState.collectAsState()
    val settingsFailed by graph.settingsFailed.collectAsState()
    val scroll = rememberLazyListState()
    var handledScroll by rememberSaveable { mutableIntStateOf(0) }
    var lastQuery by rememberSaveable { mutableStateOf(state.normalizedQuery) }
    var lastFacet by rememberSaveable { mutableStateOf(state.facet.name) }
    LaunchedEffect(scrollToTop, state.normalizedQuery, state.facet) {
        if (handledScroll != scrollToTop || lastQuery != state.normalizedQuery || lastFacet != state.facet.name) scroll.scrollToItem(0)
        handledScroll = scrollToTop
        lastQuery = state.normalizedQuery
        lastFacet = state.facet.name
    }
    MobileSearchContent(state, graph.catalog.imageBaseUrl(), navigator, search::setQuery, search::selectFacet,
        model::submit, model::chooseQuery, search::clearRecentQueries, search::retryRecentRead,
        model::retrySettings, model::retry, search::loadMore,
        settingsFailed, scroll)
}

@Composable
internal fun MobileSearchContent(
    state: ProductSearchUiState,
    baseUrl: String,
    navigator: MobileNavigator,
    onQuery: (String) -> Unit,
    onFacet: (SearchFacet) -> Unit,
    onSubmit: () -> Unit,
    onChooseQuery: (String) -> Unit,
    onClearRecent: () -> Unit,
    onRecentRetry: () -> Unit,
    onSettingsRetry: () -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    settingsFailed: Boolean = false,
    scroll: LazyListState = rememberLazyListState(),
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun dismissKeyboard() { keyboard?.hide(); focus.clearFocus() }
    var editor by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(state.query)) }
    LaunchedEffect(state.query) { if (editor.text != state.query) editor = TextFieldValue(state.query) }
    val content = (state.results as? SectionState.Content)?.value
    LaunchedEffect(scroll, content) {
        // A failed page is retained; viewport changes must never turn into an automatic retry loop.
        if (content?.hasMore == true && !content.loadingMore && content.moreError == null) {
            snapshotFlow {
                val layout = scroll.layoutInfo
                layout.visibleItemsInfo.lastOrNull()?.index?.let { it >= layout.totalItemsCount - 3 } == true
            }.collect { nearEnd -> if (nearEnd) onMore() }
        }
    }
    // safeDrawing already includes the keyboard; the 100dp clears the floating tab bar, which the keyboard covers.
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val markOrigin = rememberMobileSharedOpen()
    if (confirmClear) ClearRecentSheet(onConfirm = { confirmClear = false; onClearRecent() }, onDismiss = { confirmClear = false })
    Box(Modifier.fillMaxSize()) {
        AmbientGlow(mobilePalette("search"))
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = if (keyboardOpen) 0.dp else 100.dp)) {
            SectionTitle("Search")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(editor, { editor = it; onQuery(it.text) }, Modifier.fillMaxWidth().testTag("search-input"),
                label = { Text("Search movies, TV and people") }, singleLine = true,
                isError = state.inputError != null,
                trailingIcon = { if (state.query.isNotEmpty()) RoundIconButton(CvIcons.Close, "Clear search", { onQuery("") }) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit(); dismissKeyboard() }))
            if (state.inputError != null) Text("Use 200 characters or fewer (excluding surrounding spaces).", Modifier.testTag("search-input-error"))
            if (!state.showingSuggestions) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // No per-page counts: a label that changes on every page load reads as noise.
                SearchFacet.entries.forEach { facet -> Chip(facet.label(), state.facet == facet, { onFacet(facet) }) }
            }
            LazyColumn(Modifier.fillMaxSize().testTag("mobile-search"), scroll,
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 20.dp)) {
                val settingsBroken = settingsFailed || state.settingsError != null
                if (settingsBroken) item("settings") {
                    ErrorState(
                        if (state.settingsKnown) "Saved preferences unavailable; keeping the last loaded values." else "Saved preferences unavailable; content preferences are unknown.",
                        onSettingsRetry, Modifier.testTag("search-settings-recovery"))
                }
                if (state.recentReadError != null) item("recent-error") {
                    ErrorState("Couldn't read recent searches. Previously loaded entries may be out of date.", onRecentRetry,
                        Modifier.testTag("search-recent-recovery"))
                }
                // Pending/succeeded writes are silent; only a failure needs the user's attention.
                if (state.recentMutation == RecentMutationStatus.Failed) item("mutation") {
                    Text("Couldn't update recent searches.", style = bodyStyle(13.sp), color = MaterialTheme.colorScheme.error)
                }
                if (state.showingSuggestions) {
                    item("recent") {
                        RecentSearches(state.recentQueries, state.recentReadError == null,
                            onChoose = { onChooseQuery(it); dismissKeyboard() }, onClear = { confirmClear = true })
                    }
                    item("suggestions") { SectionTitle("Trending titles · This week") }
                }
                when (val results = state.results) {
                    // Invalid input and unknown settings are not empty-success states; first settings read still shimmers.
                    null -> if (!state.settingsKnown && !settingsBroken && state.inputError == null) searchSkeleton()
                    SectionState.Loading -> searchSkeleton()
                    SectionState.Empty -> item("empty") { Text(if (state.showingSuggestions) "No Trending titles available." else "No results. Try another title or name.") }
                    is SectionState.Error -> item("error") { SectionFailure(results.kind, onRetry) }
                    is SectionState.Content -> {
                        items(results.value.items, key = { it.key }) { hero ->
                            MobileSearchResultRow(hero, baseUrl) {
                                dismissKeyboard()
                                (hero as? Hero.OfTitle)?.let { markOrigin("search", it.title.ref) }
                                navigator.open(hero.searchDestination())
                            }
                        }
                        if (results.value.loadingMore && results.value.moreError == null) items(2, key = { "paging-skeleton-$it" }) {
                            SearchRowSkeleton()
                        }
                        if (results.value.hasMore) item("paging") {
                            val error = results.value.moreError
                            if (error != null) SectionFailure(error, onRetry)
                            else if (!results.value.loadingMore) PillButton("Load more", onMore)
                        }
                    }
                }
            }
        }
    }
}

/** Header with a quiet "Clear all" text action, then one tappable history row per query. [recent] null is unknown, not empty. */
@Composable
private fun RecentSearches(recent: List<String>?, readable: Boolean, onChoose: (String) -> Unit, onClear: () -> Unit) {
    val muted = Tokens.Colors.TextPrimary.copy(alpha = 0.6f)
    Column(Modifier.testTag("search-recent")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Recent searches", Modifier.weight(1f))
            if (!recent.isNullOrEmpty()) Text("Clear all", style = bodyStyle(14.sp, FontWeight.SemiBold), color = Tokens.Colors.Accent,
                modifier = Modifier.minimumInteractiveComponentSize()
                    .cvClickable(onClear, focusShape = RoundedCornerShape(8.dp)).padding(horizontal = 4.dp))
        }
        when {
            recent == null -> if (readable) SkeletonLines(2, Modifier.fillMaxWidth(0.7f).padding(vertical = 8.dp))
            recent.isEmpty() -> Text("No recent searches.", style = bodyStyle(14.sp), color = muted, modifier = Modifier.padding(vertical = 4.dp))
            else -> recent.forEach { query ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).cvClickable({ onChoose(query) }, onClickLabel = "Search $query",
                    focusShape = RoundedCornerShape(8.dp), pressedScale = 1f),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(CvIcons.History, null, tint = muted, modifier = Modifier.size(20.dp))
                    Text(query, Modifier.weight(1f), style = bodyStyle(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(CvIcons.ChevronRight, null, tint = muted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Bottom-sheet confirmation: icon, short title and body, then full-width stacked actions (destructive first). */
@Composable
private fun ClearRecentSheet(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    CvBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().testTag("search-clear-confirm"), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Tokens.Colors.Danger.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(CvIcons.History, null, tint = Tokens.Colors.Danger, modifier = Modifier.size(26.dp))
            }
            Text("Clear recent searches?", Modifier.padding(top = 16.dp).semantics { heading() },
                style = bodyStyle(19.sp, FontWeight.Bold), textAlign = TextAlign.Center)
            Text("Your search history on this device will be removed. This can't be undone.", Modifier.padding(top = 8.dp),
                style = bodyStyle(14.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.65f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            PillButton("Clear", onConfirm, Modifier.fillMaxWidth(), variant = ButtonVariant.Destructive)
            Spacer(Modifier.height(8.dp))
            PillButton("Cancel", onDismiss, Modifier.fillMaxWidth(), variant = ButtonVariant.Ghost)
        }
    }
}

/** Enough rows to fill a phone viewport, so loading reads as the whole page arriving, not three stray bars. */
private fun LazyListScope.searchSkeleton() = items(8, key = { "skeleton-$it" }) { SearchRowSkeleton() }

/** Same footprint as [MobileSearchResultRow]: 58×87 poster, title and meta lines. */
@Composable
private fun SearchRowSkeleton() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clearAndSetSemantics { contentDescription = "Loading" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(58.dp, 87.dp).shimmer(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.75f).height(14.dp).shimmer())
            Box(Modifier.fillMaxWidth(0.45f).height(11.dp).shimmer())
        }
    }
}

@Composable
private fun MobileSearchResultRow(hero: Hero, baseUrl: String, onClick: () -> Unit) {
    val title = when (hero) { is Hero.OfTitle -> hero.title.title; is Hero.OfPerson -> hero.person.name }
    val subtitle = when (hero) {
        is Hero.OfTitle -> titleMeta(hero.title)
        is Hero.OfPerson -> listOfNotNull("Person", hero.person.knownForDepartment).joinToString(" · ")
    }
    Row(Modifier.fillMaxWidth().cvClickable(onClick, onClickLabel = title).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val person = hero is Hero.OfPerson
        val ref = (hero as? Hero.OfTitle)?.title?.ref
        val path = when (hero) { is Hero.OfTitle -> hero.title.posterPath; is Hero.OfPerson -> hero.person.profilePath }
        // Titles load the Detail header's w342 so the shared poster lands on a cached image, not a blank swap.
        PaletteArt(imageUrl(baseUrl, if (person) "w185" else "w342", path), mobilePalette(hero.key), if (person) ArtStyle.Person else ArtStyle.Poster,
            (if (ref != null) mobileSharedPoster(ref, "search") else Modifier)
                .size(58.dp, if (person) 58.dp else 87.dp).clip(if (person) CircleShape else RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f)) { Text(title, if (ref != null) mobileSharedTitle(ref, "search") else Modifier); Text(subtitle) }
    }
}

private fun SearchFacet.label(): String = when (this) {
    SearchFacet.Multi -> "All"
    SearchFacet.Movie -> "Movies"
    SearchFacet.Tv -> "TV"
    SearchFacet.Person -> "People"
}
