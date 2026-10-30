package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.tmdb.SearchFacet
import com.techgv.cineverse.presentation.ProductSearchStateHolder
import com.techgv.cineverse.presentation.StateSaver
import kotlinx.coroutines.CoroutineScope

/** One Search tab entry owns one holder; navigation/recomposition never commits a query. */
internal class MobileSearchViewModel(
    private val graph: MobileApplication,
    savedState: SavedStateHandle,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val saver = SearchSaver(savedState)
    // Validate both scalars before constructing the holder (and therefore before starting any reads).
    val search: ProductSearchStateHolder? = if (saver.valid()) ProductSearchStateHolder(
        graph.catalog, graph.generation, graph.settingsFlow, graph.recentQueries, saver,
        scope ?: viewModelScope,
    ) else null

    fun submit() { search?.commitQuery() }
    fun chooseQuery(query: String) { search?.setQuery(query); search?.commitQuery() }
    fun retrySettings() { graph.retrySettings(); search?.retrySettings() }
    fun retry() { graph.retrySettings(); search?.retry() }

    override fun onCleared() { search?.dispose() }
}

private class SearchSaver(private val handle: SavedStateHandle) : StateSaver {
    fun valid(): Boolean = try {
        getString("productSearch.query")
        getString("productSearch.facet")?.let(SearchFacet::valueOf)
        true
    } catch (_: IllegalArgumentException) { false }

    override fun getString(key: String): String? {
        val value = handle.get<Any?>(key)
        require(value == null || value is String)
        return value
    }
    override fun getInt(key: String): Int? = handle[key]
    override fun getBoolean(key: String): Boolean? = handle[key]
    override fun putString(key: String, value: String?) { handle[key] = value }
    override fun putInt(key: String, value: Int?) { handle[key] = value }
    override fun putBoolean(key: String, value: Boolean?) { handle[key] = value }
}
