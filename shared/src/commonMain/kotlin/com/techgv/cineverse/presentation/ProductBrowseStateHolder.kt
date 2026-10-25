package com.techgv.cineverse.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProductBrowseUiState(
    val selection: BrowseSelection,
    val grid: SectionState<PagedItems> = SectionState.Loading,
    /** False until a real settings observation arrives; a read failure never invents defaults. */
    val settingsKnown: Boolean = false,
    val settingsError: SectionErrorKind? = null,
    val genres: Map<Int, String> = emptyMap(),
    val genresLoading: Boolean = false,
    val genresError: SectionErrorKind? = null,
)

/**
 * Cross-kind product Browse/Explore owner. Legacy TV Browse keeps its fixed-kind/index-tab contract.
 * Uses the same cached catalog and PagedSection, not shell networking. Saved selection wins over [initialSelection].
 * [onStorageFailure] reports only live-context typed failures; the owner explicitly calls [retryStorage].
 */
class ProductBrowseStateHolder(
    private val catalog: CatalogRepository,
    private val appGeneration: GenerationCounter,
    private val settings: Flow<Settings>,
    private val saver: StateSaver,
    initialSelection: BrowseSelection,
    scope: CoroutineScope? = null,
    private val onStorageFailure: (StorageException) -> Unit = {},
) : ViewModel() {
    private val scope = scope ?: viewModelScope
    private val selection = BrowseSelection.restoreFrom(saver) ?: initialSelection
    private val state = MutableStateFlow(ProductBrowseUiState(selection))
    private val grid = PagedSection(this.scope, appGeneration, onStorageFailure)
    private val settingsReads = MutableStateFlow(0L)
    private val sourceGeneration = GenerationCounter()
    private val genresGeneration = GenerationCounter()
    private var genresJob: Job? = null
    private var contentSettings: Triple<String, String, Boolean>? = null
    private var settingsApp = appGeneration.current

    val uiState: StateFlow<ProductBrowseUiState> = combine(state, grid.state) { state, grid -> state.copy(grid = grid) }
        .stateIn(this.scope, SharingStarted.Eagerly, state.value)

    init {
        selection.saveTo(saver)
        loadSelection()
        this.scope.launch {
            settingsReads.collectLatest { read ->
                val source = sourceGeneration.current
                var app = appGeneration.current
                var first = true
                try {
                    settings.collect { value ->
                        currentCoroutineContext().ensureActive()
                        if (source != sourceGeneration.current) return@collect
                        val next = Triple(value.language, value.region, value.includeAdult)
                        val changed = (contentSettings != null && contentSettings != next) ||
                            settingsApp != appGeneration.current || (first && read > 0 && contentSettings == null)
                        contentSettings = next
                        settingsApp = appGeneration.current
                        app = settingsApp
                        state.update { it.copy(settingsKnown = true, settingsError = null) }
                        if (changed) {
                            loadSelection()
                        } else if (first && read > 0) {
                            grid.retryStorage()
                            if (state.value.genresError != null) loadGenres()
                        }
                        first = false
                    }
                } catch (failure: StorageException) {
                    currentCoroutineContext().ensureActive()
                    if (source == sourceGeneration.current && app == appGeneration.current) {
                        state.update { it.copy(settingsError = SectionErrorKind.Generic) }
                        onStorageFailure(failure)
                    }
                }
            }
        }
    }

    /** Exact typed source replacement, including cross-kind Home handoffs. Equal selections are a no-op. */
    fun select(selection: BrowseSelection) {
        if (selection == state.value.selection) return
        sourceGeneration.bump()
        selection.saveTo(saver)
        state.update { it.copy(selection = selection) }
        loadSelection()
        // A suspended/dead observer belongs to the old source; retain the last real tuple across subscriptions.
        retryStorage()
    }

    private fun loadSelection() {
        grid.load(state.value.selection.pageSource(catalog))
        state.update { it.copy(genres = emptyMap(), genresError = null) }
        loadGenres()
    }

    /** Viewport pagination never retries an error automatically. Retry is an explicit owner action. */
    fun loadMore() {
        if ((grid.state.value as? SectionState.Content)?.value?.moreError == null) grid.loadMore()
    }

    /** Retries the failed next page, not page 1; initial/empty/refresh retries read page 1. */
    fun retry() {
        if (state.value.settingsError != null) return retryStorage()
        if (grid.retryStorage()) return
        val loaded = (grid.state.value as? SectionState.Content)?.value
        if (loaded?.moreError != null) grid.loadMore() else grid.reload(retainContent = true)
    }

    /** Re-subscribe dead settings readers even when recovered settings compare equal; no writes or action replay. */
    fun retryStorage() { settingsReads.update { it + 1 } }

    /** People has no genre work, loading indicator or error. Other sources use only their actual media genres. */
    fun retryGenres() = loadGenres()

    private fun loadGenres() {
        genresJob?.cancel()
        val gen = genresGeneration.bump()
        val app = appGeneration.current
        val media = when (val selection = state.value.selection) {
            is BrowseSelection.Movies -> listOf(MediaType.Movie)
            is BrowseSelection.Tv -> listOf(MediaType.Tv)
            is BrowseSelection.Discover -> listOf(selection.media)
            is BrowseSelection.Trending -> MediaType.entries
            BrowseSelection.People -> emptyList()
        }
        state.update { it.copy(genresLoading = media.isNotEmpty()) }
        if (media.isEmpty()) return
        genresJob = scope.launch {
            val result = try {
                Result.success(media.flatMap { catalog.genres(it).genres }.filter { it.name.isNotBlank() }.associate { it.id to it.name })
            } catch (failure: StorageException) {
                Result.failure(failure)
            } catch (failure: TmdbException) {
                Result.failure(failure)
            }
            currentCoroutineContext().ensureActive()
            if (gen != genresGeneration.current) return@launch
            if (app != appGeneration.current) {
                state.update { it.copy(genres = emptyMap()) }
                return@launch loadGenres()
            }
            result.fold(
                onSuccess = { genres -> state.update { it.copy(genres = genres, genresLoading = false, genresError = null) } },
                onFailure = { failure ->
                    state.update { it.copy(genresLoading = false, genresError = failure.toSectionErrorKind()) }
                    if (failure is StorageException) onStorageFailure(failure)
                },
            )
        }
    }
}
