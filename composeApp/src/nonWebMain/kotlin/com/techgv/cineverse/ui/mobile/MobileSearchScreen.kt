package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.data.tmdb.SearchFacet
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*

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
                SearchFacet.entries.forEach { facet ->
                    val count = state.loadedCount(facet)
                    Chip(facet.label() + (count?.let { " · $it loaded" } ?: ""), state.facet == facet, { onFacet(facet) })
                }
            }
            LazyColumn(Modifier.fillMaxSize().testTag("mobile-search"), scroll,
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 20.dp)) {
                if (!state.settingsKnown || settingsFailed || state.settingsError != null) item("settings") {
                    if (settingsFailed || state.settingsError != null) ErrorState(
                        if (state.settingsKnown) "Saved preferences unavailable; keeping the last loaded values." else "Saved preferences unavailable; content preferences are unknown.",
                        onSettingsRetry, Modifier.testTag("search-settings-recovery"))
                    else Text("Loading saved preferences…")
                }
                if (state.recentReadError != null) item("recent-error") {
                    ErrorState("Couldn't read recent searches. Previously loaded entries may be out of date.", onRecentRetry,
                        Modifier.testTag("search-recent-recovery"))
                }
                when (state.recentMutation) {
                    RecentMutationStatus.Pending -> item("mutation") { Text("Updating recent searches…") }
                    RecentMutationStatus.Failed -> item("mutation") { Text("Couldn't update recent searches. Not retried.") }
                    RecentMutationStatus.Succeeded -> item("mutation") { Text("Recent-search write completed.") }
                    RecentMutationStatus.Idle -> Unit
                }
                if (state.showingSuggestions) {
                    item("recent") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionTitle("Recent searches")
                            when (val recent = state.recentQueries) {
                                null -> if (state.recentReadError == null) Text("Loading recent searches…")
                                else -> if (recent.isEmpty()) Text("No recent searches.") else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    recent.forEach { query -> Chip(query, false, { onChooseQuery(query); dismissKeyboard() }) }
                                }
                            }
                            PillButton("Clear recent searches", onClearRecent, variant = ButtonVariant.Ghost)
                        }
                    }
                    item("suggestions") { SectionTitle("Trending titles · This week") }
                }
                when (val results = state.results) {
                    null -> Unit // Invalid input and unknown settings are not empty-success states.
                    SectionState.Loading -> items(3) { SkeletonLines(3, Modifier.fillMaxWidth()) }
                    SectionState.Empty -> item("empty") { Text(if (state.showingSuggestions) "No Trending titles available." else "No results. Try another title or name.") }
                    is SectionState.Error -> item("error") { SectionFailure(results.kind, onRetry) }
                    is SectionState.Content -> {
                        items(results.value.items, key = { it.key }) { hero ->
                            MobileSearchResultRow(hero, baseUrl) { dismissKeyboard(); navigator.open(hero.searchDestination()) }
                        }
                        if (results.value.loadingMore && results.value.moreError == null) items(2, key = { "paging-skeleton-$it" }) {
                            SkeletonLines(3, Modifier.fillMaxWidth())
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
        val path = when (hero) { is Hero.OfTitle -> hero.title.posterPath; is Hero.OfPerson -> hero.person.profilePath }
        PaletteArt(imageUrl(baseUrl, "w185", path), mobilePalette(hero.key), if (person) ArtStyle.Person else ArtStyle.Poster,
            Modifier.size(58.dp, if (person) 58.dp else 87.dp).clip(if (person) CircleShape else RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f)) { Text(title); Text(subtitle) }
    }
}

private fun SearchFacet.label(): String = when (this) {
    SearchFacet.Multi -> "All"
    SearchFacet.Movie -> "Movies"
    SearchFacet.Tv -> "TV"
    SearchFacet.Person -> "People"
}
