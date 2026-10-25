package com.techgv.cineverse.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.search.RecentQueryStore
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.SEARCH_QUERY_MAX_LENGTH
import com.techgv.cineverse.data.tmdb.SearchFacet
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.data.tmdb.toHero
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SearchInputError { TooLong }
enum class RecentMutationStatus { Idle, Pending, Succeeded, Failed }

data class ProductSearchUiState(
    val query: String,
    val facet: SearchFacet,
    /** Null means no source: invalid input, or settings have not yet been observed. */
    val results: SectionState<PagedItems>? = null,
    val settingsKnown: Boolean = false,
    val settingsError: SectionErrorKind? = null,
    /** Null is UNKNOWN, not an invented empty durable history. Failures retain the last real observation. */
    val recentQueries: List<String>? = null,
    val recentReadError: SectionErrorKind? = null,
    val recentMutation: RecentMutationStatus = RecentMutationStatus.Idle,
) {
    val normalizedQuery: String get() = query.trim()
    val inputError: SearchInputError? get() = if (normalizedQuery.length > SEARCH_QUERY_MAX_LENGTH) SearchInputError.TooLong else null
    /** These are Trending titles (week), NOT trending-search analytics. Facets apply only to nonblank queries. */
    val showingSuggestions: Boolean get() = normalizedQuery.isEmpty()

    /** Loaded/deduplicated items only, never a total. Inactive facets and suggestions have unknown counts.
     * PageDto's absent totalResults is indistinguishable from zero; no count fanout is performed.
     */
    fun loadedCount(facet: SearchFacet): Int? = if (facet != this.facet || showingSuggestions) null else when (val result = results) {
        is SectionState.Content -> result.value.items.size
        SectionState.Empty -> 0
        else -> null
    }
}

/**
 * Product Search owner; TV SearchStateHolder and its saved keys/keyboard remain unchanged.
 * Call actions on the owner dispatcher. Initial restore, facet/settings changes and explicit retry are immediate;
 * changed normalized nonblank input waits 300ms. Whitespace-only edits save raw input without replacing work.
 * Networking waits for the first real settings observation. Recovery never recreates this owner or replays writes.
 *
 * Saved keys are productSearch.query (raw String) and productSearch.facet (SearchFacet.name).
 * Missing keys mean empty/Multi. Unknown enum values throw IllegalArgumentException BEFORE any jobs/writes;
 * StateSaver adapters MUST reject wrong primitive types (external routes must be validated by their shell).
 * An over-budget restored String is retained with TooLong, not truncated or sent to the catalog.
 * Supply typed catalog/recent/settings/saver dependencies and an entry lifetime, never a service locator.
 */
class ProductSearchStateHolder(
    private val catalog: CatalogRepository,
    private val appGeneration: GenerationCounter,
    private val settings: Flow<Settings>,
    private val recent: RecentQueryStore,
    private val saver: StateSaver,
    scope: CoroutineScope? = null,
    private val onStorageFailure: (StorageException) -> Unit = {},
) : ViewModel() {
    // Restore before creating child jobs so a strict-boundary failure cannot leak an owner.
    private val state = MutableStateFlow(ProductSearchUiState(
        saver.getString(QUERY).orEmpty(),
        saver.getString(FACET)?.let(SearchFacet::valueOf) ?: SearchFacet.Multi,
    ))
    private val parentScope = scope ?: viewModelScope
    private val ownerJob = Job(parentScope.coroutineContext[Job])
    private val owner = CoroutineScope(parentScope.coroutineContext + ownerJob)
    private val sourceGeneration = GenerationCounter()
    private val settingsGeneration = GenerationCounter()
    private val recentGeneration = GenerationCounter()
    private val mutationGeneration = GenerationCounter()
    private val writes = Mutex()
    private var sourceScope: CoroutineScope? = null
    private var section: PagedSection? = null
    private var settingsJob: Job? = null
    private var recentJob: Job? = null
    private var contentSettings: Triple<String, String, Boolean>? = null
    private var contextApp = appGeneration.current
    private var pendingDebounce = 0L

    val uiState: StateFlow<ProductSearchUiState> = state.asStateFlow()

    init {
        readSettings(recoverPage = false)
        retryRecentRead()
    }

    fun setQuery(query: String) = setInput(query, state.value.facet)
    fun selectFacet(facet: SearchFacet) = setInput(state.value.query, facet)

    /** Atomic URL/back-stack handoff; no intermediate query/old-facet request and no recent mutation. */
    fun setInput(query: String, facet: SearchFacet) {
        if (!ownerJob.isActive) return
        val old = state.value
        if (query == old.query && facet == old.facet) { liveContext(); return }
        saver.putString(QUERY, query)
        saver.putString(FACET, facet.name)
        val changedQuery = query.trim() != old.normalizedQuery
        val changedSource = changedQuery || facet != old.facet
        if (changedSource) invalidateSource()
        state.update { it.copy(query = query, facet = facet) }
        if (!changedSource) { liveContext(); return }
        pendingDebounce = if (changedQuery && query.isNotBlank()) SEARCH_DEBOUNCE_MILLIS else 0
        replaceSource(pendingDebounce)
        readSettings(recoverPage = false)
        retryRecentRead()
    }

    /** Viewport events cannot retry failed pages. */
    fun loadMore() {
        if (!liveContext()) return
        if ((section?.state?.value as? SectionState.Content)?.value?.moreError == null) section?.loadMore()
    }

    /** Explicit retry bypasses debounce; a failed next page retries THAT page, retaining loaded items. */
    fun retry() {
        if (!liveContext()) return
        if (state.value.settingsError != null || !state.value.settingsKnown) return retrySettings()
        val current = section ?: return
        if (current.retryStorage()) return
        val loaded = (current.state.value as? SectionState.Content)?.value
        if (loaded?.moreError != null) current.loadMore() else current.reload(retainContent = true)
    }

    /** Re-subscribe even if values recover equal; keep the same section and failed-page marker. */
    fun retrySettings() { if (liveContext()) readSettings(recoverPage = true) }

    private fun readSettings(recoverPage: Boolean) {
        settingsJob?.cancel()
        val read = settingsGeneration.bump()
        var app = appGeneration.current
        settingsJob = owner.launch {
            var first = true
            try {
                settings.collect { value ->
                    currentCoroutineContext().ensureActive()
                    if (read != settingsGeneration.current) return@collect
                    val next = Triple(value.language, value.region, value.includeAdult)
                    val changed = contentSettings != next || contextApp != appGeneration.current
                    contentSettings = next
                    app = appGeneration.current
                    if (changed) invalidateSource()
                    state.update { it.copy(settingsKnown = true, settingsError = null) }
                    if (changed) {
                        contextApp = appGeneration.current
                        replaceSource(if (first) pendingDebounce else 0)
                        retryRecentRead()
                    } else if (first && recoverPage) {
                        section?.retryStorage()
                    }
                    first = false
                }
            } catch (failure: StorageException) {
                currentCoroutineContext().ensureActive()
                if (read == settingsGeneration.current) {
                    if (app != appGeneration.current) readSettings(recoverPage = true)
                    else {
                        state.update { it.copy(settingsError = SectionErrorKind.Generic) }
                        onStorageFailure(failure)
                    }
                }
            }
        }
    }

    private fun invalidateSource() {
        sourceGeneration.bump()
        sourceScope?.cancel()
        sourceScope = null
        section = null
        mutationGeneration.bump()
        state.update { it.copy(results = null, recentMutation = RecentMutationStatus.Idle) }
    }

    private fun replaceSource(debounce: Long) {
        if (!ownerJob.isActive || !state.value.settingsKnown || state.value.inputError != null) return
        val query = state.value.normalizedQuery
        val facet = state.value.facet
        val generation = sourceGeneration.current
        val app = appGeneration.current
        val child = CoroutineScope(owner.coroutineContext + Job(ownerJob))
        sourceScope = child
        pendingDebounce = 0
        val current = PagedSection(child, appGeneration) { failure ->
            if (ownerJob.isActive && generation == sourceGeneration.current && app == appGeneration.current) onStorageFailure(failure)
        }
        section = current
        val source = if (query.isEmpty()) PageSource({ catalog.trending(TrendingWindow.Week, it) }) { it.toHero() }
        else PageSource({ catalog.search(facet, query, it) }) { it.toHero() }
        state.update { it.copy(results = SectionState.Loading) }
        current.load(source, debounce)
        child.launch {
            current.state.collect { result ->
                currentCoroutineContext().ensureActive()
                if (generation != sourceGeneration.current) return@collect
                if (app != appGeneration.current) { liveContext(); return@collect }
                state.update { it.copy(results = result) }
            }
        }
    }

    /** GenerationCounter is a snapshot, not a Flow; reconcile on observations, page responses and owner actions. */
    private fun liveContext(): Boolean {
        if (!ownerJob.isActive) return false
        if (contextApp == appGeneration.current) return true
        contextApp = appGeneration.current
        invalidateSource()
        replaceSource(0)
        readSettings(recoverPage = true)
        retryRecentRead()
        return false
    }

    /** Read recovery only: never record/clear, even after a failed explicit mutation. */
    fun retryRecentRead() {
        if (!ownerJob.isActive) return
        recentJob?.cancel()
        val read = recentGeneration.bump()
        val app = appGeneration.current
        recentJob = owner.launch {
            try {
                recent.observe().collect { queries ->
                    currentCoroutineContext().ensureActive()
                    if (read != recentGeneration.current) return@collect
                    if (app != appGeneration.current) { if (liveContext()) retryRecentRead(); return@collect }
                    state.update { it.copy(recentQueries = queries, recentReadError = null) }
                }
            } catch (failure: StorageException) {
                currentCoroutineContext().ensureActive()
                if (read == recentGeneration.current) {
                    if (app != appGeneration.current) { if (liveContext()) retryRecentRead() }
                    else {
                        state.update { it.copy(recentReadError = SectionErrorKind.Generic) }
                        onStorageFailure(failure)
                    }
                }
            }
        }
    }

    /** ONLY explicit submit/commit records input. Accepted writes keep order across input changes, not disposal.
     * Repeated submits are deliberate writes; failed mutations are never replayed by read recovery.
     */
    fun commitQuery() {
        val value = state.value
        if (value.inputError != null || value.showingSuggestions) return
        mutateRecent { recent.record(value.normalizedQuery) }
    }

    /** Explicit user intent only; clear removes only the RecentQueryStore-owned key. */
    fun clearRecentQueries() = mutateRecent { recent.clear() }

    private fun mutateRecent(operation: suspend () -> Unit) {
        if (!ownerJob.isActive) return
        liveContext()
        val mutation = mutationGeneration.bump()
        val source = sourceGeneration.current
        val app = appGeneration.current
        state.update { it.copy(recentMutation = RecentMutationStatus.Pending) }
        // Enter the FIFO mutex before returning: accepted explicit intents keep order across suspension.
        owner.launch(start = CoroutineStart.UNDISPATCHED) {
            writes.withLock {
                currentCoroutineContext().ensureActive()
                val failure = try { operation(); null } catch (failure: StorageException) { failure }
                currentCoroutineContext().ensureActive()
                if (mutation != mutationGeneration.current || source != sourceGeneration.current || app != appGeneration.current) return@withLock
                state.update { it.copy(recentMutation = if (failure == null) RecentMutationStatus.Succeeded else RecentMutationStatus.Failed) }
                if (failure != null) onStorageFailure(failure)
                else if (state.value.recentReadError != null) retryRecentRead()
            }
        }
    }

    /** Browser entry owners call dispose; native owners may instead cancel the supplied entry scope. */
    fun dispose() { owner.cancel() }
    override fun onCleared() { dispose() }

    private companion object {
        const val QUERY = "productSearch.query"
        const val FACET = "productSearch.facet"
    }
}
