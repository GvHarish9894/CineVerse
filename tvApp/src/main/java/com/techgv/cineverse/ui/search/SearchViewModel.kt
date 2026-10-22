package com.techgv.cineverse.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.presentation.PagedItems
import com.techgv.cineverse.presentation.SavedStateHandleStateSaver
import com.techgv.cineverse.presentation.SearchStateHolder
import com.techgv.cineverse.presentation.SearchUiState
import com.techgv.cineverse.presentation.SectionState
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.KeyboardKey
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

const val SEARCH_MAX_CHARS = 28

/** §7.7 query editing: keyboard / hardware characters append up to 28 characters; Delete; Clear. */
fun editQuery(query: String, key: KeyboardKey): String = when (key) {
    is KeyboardKey.Char -> typeIntoQuery(query, key.char)
    KeyboardKey.Space -> typeIntoQuery(query, ' ')
    KeyboardKey.Delete -> query.dropLast(1)
    KeyboardKey.Clear -> ""
}

fun typeIntoQuery(query: String, char: Char): String = if (query.length < SEARCH_MAX_CHARS) query + char else query

/** TV adapter over [SearchStateHolder] (§7.7): on-screen keyboard (28-char cap), focus memory, Clear's focus report. */
class SearchViewModel(
    repository: TmdbRepository,
    appGeneration: GenerationCounter,
    settings: Flow<Settings>,
    handle: SavedStateHandle,
) : ViewModel() {
    val focusMemory = FocusMemory(handle)
    private val holder = SearchStateHolder(repository.catalog, appGeneration, settings, SavedStateHandleStateSaver(handle), viewModelScope)
    private var clearJob: Job? = null
    private val mutableClearSettled = MutableSharedFlow<SectionState<PagedItems>>(extraBufferCapacity = 1)

    /** The results of each Clear once they settled (§7.7: focus then returns to the first result or the keyboard). */
    val clearSettled: SharedFlow<SectionState<PagedItems>> = mutableClearSettled

    val uiState: StateFlow<SearchUiState> = holder.uiState

    fun onKey(key: KeyboardKey) {
        setQuery(editQuery(holder.currentQuery, key))
        if (key == KeyboardKey.Clear) {
            // setQuery already started the new generation (Loading), so this waits for its result.
            clearJob = viewModelScope.launch { mutableClearSettled.emit(holder.results.first { it !is SectionState.Loading }) }
        }
    }

    /** Hardware keyboard / remote digits. */
    fun onTyped(char: Char) = setQuery(typeIntoQuery(holder.currentQuery, char))

    fun loadMore() = holder.loadMore()

    fun retry() = holder.retry()

    private fun setQuery(new: String) {
        clearJob?.cancel() // a newer edit supersedes a Clear still waiting for its results
        holder.setQuery(new)
    }
}
