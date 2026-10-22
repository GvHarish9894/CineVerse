package com.techgv.cineverse.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.data.tmdb.toHero
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
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

const val SEARCH_DEBOUNCE_MILLIS = 300L

data class SearchUiState(val query: String, val results: SectionState<PagedItems>) {
    /** ≥ 1 non-blank character → search results; otherwise "Trending now" suggestions. */
    val searching: Boolean get() = query.isNotBlank()
}

/**
 * §7.7: an empty query shows trending (week) suggestions; otherwise `search/multi` (mixed titles and people, API order)
 * after a 300 ms debounce. Every query change is a new generation of the results section, so stale responses drop.
 * Input limits (TV: 28-character keyboard) belong to the shell. [scope]: see [HomeStateHolder].
 */
class SearchStateHolder(
    private val catalog: CatalogRepository,
    appGeneration: GenerationCounter,
    settings: Flow<Settings>,
    private val saver: StateSaver,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val query = MutableStateFlow(saver.getString(QUERY).orEmpty())
    private val section = PagedSection(this.scope, appGeneration)

    /** The results section itself: it turns Loading synchronously when a query change starts its generation. */
    val results: StateFlow<SectionState<PagedItems>> = section.state

    val uiState: StateFlow<SearchUiState> = combine(query, section.state, ::SearchUiState)
        .stateIn(this.scope, SharingStarted.Eagerly, SearchUiState(query.value, SectionState.Loading))

    init {
        search(debounceMillis = 0)
        this.scope.launch {
            settings.map { Triple(it.language, it.region, it.includeAdult) }.distinctUntilChanged().drop(1).collect { section.reload() }
        }
    }

    /** The current query (saved). */
    val currentQuery: String get() = query.value

    /** A changed query is saved; a change beyond whitespace searches again (an empty one at once). */
    fun setQuery(new: String) {
        val old = query.value
        if (new == old) return
        query.value = new
        saver.putString(QUERY, new)
        if (new.trim() != old.trim()) search(if (new.isBlank()) 0 else SEARCH_DEBOUNCE_MILLIS)
    }

    fun loadMore() = section.loadMore()

    fun retry() = section.reload()

    private fun search(debounceMillis: Long) {
        val q = query.value.trim()
        val source = if (q.isEmpty()) {
            PageSource({ catalog.trending(TrendingWindow.Week, it) }) { it.toHero() }
        } else {
            PageSource({ catalog.searchMulti(q, it) }) { it.toHero() }
        }
        section.load(source, debounceMillis)
    }

    private companion object {
        const val QUERY = "search.query"
    }
}
