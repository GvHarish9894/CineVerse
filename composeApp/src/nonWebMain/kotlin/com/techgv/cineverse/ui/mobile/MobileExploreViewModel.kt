package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.tmdb.MovieList
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.data.tmdb.TvList
import com.techgv.cineverse.presentation.BrowseSelection
import com.techgv.cineverse.presentation.ProductBrowseStateHolder
import com.techgv.cineverse.presentation.StateSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull

internal enum class MobileExploreType { Movies, Tv, People, Trending }

internal class MobileExplorePage(val browse: ProductBrowseStateHolder, val storageFailed: MutableStateFlow<Boolean>)

/** One back-stack entry owns at most four holders; switching types retains real pages, not a shell cache. */
internal class MobileExploreViewModel(
    private val graph: MobileApplication,
    private val savedState: SavedStateHandle,
    private val initial: BrowseSelection = BrowseSelection.Movies(MovieList.Popular),
    private val scope: CoroutineScope? = null,
) : ViewModel() {
    private val pages = mutableMapOf<MobileExploreType, MobileExplorePage>()
    private val initialType = savedState.get<String>("mobileExplore.type")?.let(MobileExploreType::valueOf) ?: initial.type()
    private val mutablePage = MutableStateFlow(page(initialType))
    val currentPage = mutablePage.asStateFlow()

    fun selectType(type: MobileExploreType) {
        savedState["mobileExplore.type"] = type.name
        mutablePage.value = page(type)
    }

    fun select(selection: BrowseSelection) {
        selectType(selection.type())
        mutablePage.value.browse.select(selection)
    }

    fun retryStorage() {
        val page = mutablePage.value
        page.storageFailed.value = false
        graph.retrySettings()
        // The graph may recover to equal settings: explicitly restart this holder's reads too.
        page.browse.retryStorage()
    }

    fun retry() {
        graph.retrySettings()
        mutablePage.value.browse.retry()
    }

    fun retryGenres() {
        retryStorage()
        mutablePage.value.browse.retryGenres()
    }

    private fun page(type: MobileExploreType): MobileExplorePage = pages.getOrPut(type) {
        val failed = MutableStateFlow(false)
        val selection = if (initial.type() == type) initial else when (type) {
            MobileExploreType.Movies -> BrowseSelection.Movies(MovieList.Popular)
            MobileExploreType.Tv -> BrowseSelection.Tv(TvList.Popular)
            MobileExploreType.People -> BrowseSelection.People
            MobileExploreType.Trending -> BrowseSelection.Trending(TrendingWindow.Week)
        }
        MobileExplorePage(ProductBrowseStateHolder(graph.catalog, graph.generation, graph.settings.filterNotNull(),
            ExploreSaver(savedState, type), selection, scope ?: viewModelScope,
            onStorageFailure = { failed.value = true }), failed)
    }
}

internal fun BrowseSelection.type(): MobileExploreType = when (this) {
    is BrowseSelection.Movies -> MobileExploreType.Movies
    is BrowseSelection.Tv -> MobileExploreType.Tv
    BrowseSelection.People -> MobileExploreType.People
    is BrowseSelection.Trending -> MobileExploreType.Trending
    is BrowseSelection.Discover -> error("Native Explore has no Discover controls")
}

private class ExploreSaver(private val handle: SavedStateHandle, type: MobileExploreType) : StateSaver {
    private val prefix = "mobileExplore.${type.name}."
    override fun getString(key: String): String? = handle[prefix + key]
    override fun getInt(key: String): Int? = handle[prefix + key]
    override fun getBoolean(key: String): Boolean? = handle[prefix + key]
    override fun putString(key: String, value: String?) { handle[prefix + key] = value }
    override fun putInt(key: String, value: Int?) { handle[prefix + key] = value }
    override fun putBoolean(key: String, value: Boolean?) { handle[prefix + key] = value }
}
