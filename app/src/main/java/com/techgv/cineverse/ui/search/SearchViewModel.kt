package com.techgv.cineverse.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.data.tmdb.toHero
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.KeyboardKey
import com.techgv.cineverse.ui.components.PageSource
import com.techgv.cineverse.ui.components.PagedItems
import com.techgv.cineverse.ui.components.PagedSection
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.util.GenerationCounter
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

const val SEARCH_MAX_CHARS = 28
const val SEARCH_DEBOUNCE_MILLIS = 300L

/** §7.7 query editing: keyboard / hardware characters append up to 28 characters; Delete; Clear. */
fun editQuery(query: String, key: KeyboardKey): String = when (key) {
    is KeyboardKey.Char -> typeIntoQuery(query, key.char)
    KeyboardKey.Space -> typeIntoQuery(query, ' ')
    KeyboardKey.Delete -> query.dropLast(1)
    KeyboardKey.Clear -> ""
}

fun typeIntoQuery(query: String, char: Char): String = if (query.length < SEARCH_MAX_CHARS) query + char else query

data class SearchUiState(val query: String, val results: SectionState<PagedItems>) {
    /** ≥ 1 non-blank character → search results; otherwise "Trending now" suggestions. */
    val searching: Boolean get() = query.isNotBlank()
}

/**
 * §7.7: an empty query shows trending (week) suggestions; otherwise `search/multi` after a 300 ms
 * debounce. Every query change is a new generation of the results section, so stale responses drop.
 */
class SearchViewModel(
    private val repository: TmdbRepository,
    appGeneration: GenerationCounter,
    settings: Flow<Settings>,
    private val handle: SavedStateHandle,
) : ViewModel() {
    val focusMemory = FocusMemory(handle)
    private val query = MutableStateFlow(handle.get<String>(QUERY).orEmpty())
    private val results = PagedSection(viewModelScope, appGeneration)

    val uiState: StateFlow<SearchUiState> = combine(query, results.state, ::SearchUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState(query.value, SectionState.Loading))

    init {
        search(debounceMillis = 0)
        viewModelScope.launch {
            settings.map { Triple(it.language, it.region, it.includeAdult) }.distinctUntilChanged().drop(1).collect { results.reload() }
        }
    }

    fun onKey(key: KeyboardKey) = setQuery(editQuery(query.value, key))

    /** Hardware keyboard / remote digits. */
    fun onTyped(char: Char) = setQuery(typeIntoQuery(query.value, char))

    fun loadMore() = results.loadMore()

    fun retry() = results.reload()

    private fun setQuery(new: String) {
        val old = query.value
        if (new == old) return
        query.value = new
        handle[QUERY] = new
        if (new.trim() != old.trim()) search(if (new.isBlank()) 0 else SEARCH_DEBOUNCE_MILLIS)
    }

    private fun search(debounceMillis: Long) {
        val q = query.value.trim()
        val source = if (q.isEmpty()) {
            PageSource({ repository.trending(TrendingWindow.Week, it) }) { it.toHero() }
        } else {
            PageSource({ repository.searchMulti(q, it) }) { it.toHero() }
        }
        results.load(source, debounceMillis)
    }

    private companion object {
        const val QUERY = "search.query"
    }
}
