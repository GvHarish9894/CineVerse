package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.MediaItemDto
import com.techgv.cineverse.data.tmdb.PageDto
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.tmdb.hasNextPage
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.key
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Loaded pages of one list; [moreError] marks a failed next page or storage refresh (the loaded items stay). */
data class PagedItems(
    val items: List<Hero>,
    val page: Int,
    val hasMore: Boolean,
    val loadingMore: Boolean = false,
    val moreError: SectionErrorKind? = null,
)

/** Where a [PagedSection] reads from: one TMDB page per call, mapped to items (null items are dropped). */
class PageSource(val fetch: suspend (page: Int) -> PageDto<MediaItemDto>, val map: (MediaItemDto) -> Hero?)

/**
 * One independently loaded, paged list (§1.1 / §1.2 scope): a Home row, a Browse tab, the Search query.
 * Each [load] bumps the section's own generation and cancels the previous load; a result commits only
 * if both that generation and the app generation (auth / settings) are unchanged. A result that only
 * missed a newer app generation is reloaded, so the section never stays on stale data or "Loading".
 */
class PagedSection(
    private val scope: CoroutineScope,
    private val appGeneration: GenerationCounter,
    private val onStorageFailure: (StorageException) -> Unit = {},
) {
    private val generation = GenerationCounter()
    private val mutableState = MutableStateFlow<SectionState<PagedItems>>(SectionState.Loading)
    val state: StateFlow<SectionState<PagedItems>> = mutableState
    private var source: PageSource? = null
    private var job: Job? = null
    private var content: PagedItems? = null
    private var contentApp: Long? = null
    private var storageFailedPage: Int? = null

    /** Replaces the list with page 1 of [source], after [debounceMillis] (Search, §7.7). */
    fun load(source: PageSource, debounceMillis: Long = 0) {
        this.source = source
        content = null
        contentApp = null
        start(source, 1, debounceMillis)
    }

    private fun start(source: PageSource, page: Int, debounceMillis: Long = 0, retainContent: Boolean = false) {
        val gen = generation.bump()
        job?.cancel()
        storageFailedPage = null
        if (contentApp != appGeneration.current) content = null
        val loaded = content.takeIf { retainContent }
        mutableState.value = loaded?.let { SectionState.Content(it.copy(loadingMore = page > 1)) } ?: SectionState.Loading
        val app = appGeneration.current
        job = scope.launch {
            delay(debounceMillis)
            fetch(gen, app, source, page)
        }
    }

    /** Reloads page 1 of the current source (Try again, settings change). */
    fun reload(retainContent: Boolean = false) {
        source?.let { start(it, 1, retainContent = retainContent) }
    }

    /** Retries only a storage-failed read, including the failed next page; returns whether a retry started. */
    fun retryStorage(): Boolean {
        val page = storageFailedPage ?: return false
        val source = source ?: return false
        start(source, if (contentApp == appGeneration.current) page else 1, retainContent = true)
        return true
    }

    /** Appends the next page; no-op while loading, after the last page, or before page 1 arrived. */
    fun loadMore() {
        val source = source ?: return
        val content = (mutableState.value as? SectionState.Content)?.value ?: return
        if (contentApp != appGeneration.current) return reload()
        if (!content.hasMore || content.loadingMore || job?.isActive == true) return
        storageFailedPage = null
        mutableState.value = SectionState.Content(content.copy(loadingMore = true, moreError = null))
        val gen = generation.current
        val app = appGeneration.current
        job = scope.launch { fetch(gen, app, source, content.page + 1) }
    }

    private suspend fun fetch(gen: Long, app: Long, source: PageSource, page: Int) {
        currentCoroutineContext().ensureActive()
        if (gen != generation.current) return
        if (app != appGeneration.current) return reload()
        val result = try {
            Result.success(source.fetch(page))
        } catch (e: TmdbException) {
            Result.failure(e)
        } catch (e: StorageException) {
            Result.failure(e)
        }
        currentCoroutineContext().ensureActive()
        if (gen != generation.current) return
        if (app != appGeneration.current) return reload()
        mutableState.value = result.fold(
            onSuccess = { dto ->
                storageFailedPage = null
                commit(dto, source, page).also {
                    content = (it as? SectionState.Content)?.value
                    contentApp = app
                }
            },
            onFailure = { error ->
                val loaded = if (contentApp == app) content else null
                if (error is StorageException) {
                    storageFailedPage = page
                }
                if (loaded != null && (page > 1 || error is StorageException)) {
                    SectionState.Content(loaded.copy(loadingMore = false, moreError = error.toSectionErrorKind()))
                } else {
                    SectionState.Error(error.toSectionErrorKind())
                }
            },
        )
        (result.exceptionOrNull() as? StorageException)?.let(onStorageFailure)
    }

    private fun commit(dto: PageDto<MediaItemDto>, source: PageSource, page: Int): SectionState<PagedItems> {
        val previous = if (page > 1) content?.items.orEmpty() else emptyList()
        // Pages shift while TMDB re-ranks: drop repeats so lazy keys stay unique.
        val items = (previous + dto.results.mapNotNull(source.map)).distinctBy { it.key }
        return if (items.isEmpty()) SectionState.Empty else SectionState.Content(PagedItems(items, page, dto.hasNextPage))
    }
}
