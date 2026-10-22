package com.techgv.cineverse.ui.components

import com.techgv.cineverse.data.tmdb.MediaItemDto
import com.techgv.cineverse.data.tmdb.PageDto
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.tmdb.hasNextPage
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.key
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Loaded pages of one list; [moreError] is a failed next page (the loaded items stay). */
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
class PagedSection(private val scope: CoroutineScope, private val appGeneration: GenerationCounter) {
    private val generation = GenerationCounter()
    private val mutableState = MutableStateFlow<SectionState<PagedItems>>(SectionState.Loading)
    val state: StateFlow<SectionState<PagedItems>> = mutableState
    private var source: PageSource? = null
    private var job: Job? = null

    /** Replaces the list with page 1 of [source], after [debounceMillis] (Search, §7.7). */
    fun load(source: PageSource, debounceMillis: Long = 0) {
        this.source = source
        val gen = generation.bump()
        job?.cancel()
        mutableState.value = SectionState.Loading
        job = scope.launch {
            delay(debounceMillis)
            fetch(gen, source, 1)
        }
    }

    /** Reloads page 1 of the current source (Try again, settings change). */
    fun reload() {
        source?.let { load(it) }
    }

    /** Appends the next page; no-op while loading, after the last page, or before page 1 arrived. */
    fun loadMore() {
        val source = source ?: return
        val content = (mutableState.value as? SectionState.Content)?.value ?: return
        if (!content.hasMore || content.loadingMore || job?.isActive == true) return
        mutableState.value = SectionState.Content(content.copy(loadingMore = true, moreError = null))
        val gen = generation.current
        job = scope.launch { fetch(gen, source, content.page + 1) }
    }

    private suspend fun fetch(gen: Long, source: PageSource, page: Int) {
        val app = appGeneration.current
        val result = try {
            Result.success(source.fetch(page))
        } catch (e: TmdbException) {
            Result.failure(e)
        }
        if (gen != generation.current) return
        if (app != appGeneration.current) return reload()
        mutableState.value = result.fold(
            onSuccess = { dto -> commit(dto, source, page) },
            onFailure = { error ->
                val loaded = (mutableState.value as? SectionState.Content)?.value
                if (page > 1 && loaded != null) {
                    SectionState.Content(loaded.copy(loadingMore = false, moreError = error.toSectionErrorKind()))
                } else {
                    SectionState.Error(error.toSectionErrorKind())
                }
            },
        )
    }

    private fun commit(dto: PageDto<MediaItemDto>, source: PageSource, page: Int): SectionState<PagedItems> {
        val previous = if (page > 1) (mutableState.value as? SectionState.Content)?.value?.items.orEmpty() else emptyList()
        // Pages shift while TMDB re-ranks: drop repeats so lazy keys stay unique.
        val items = (previous + dto.results.mapNotNull(source.map)).distinctBy { it.key }
        return if (items.isEmpty()) SectionState.Empty else SectionState.Content(PagedItems(items, page, dto.hasNextPage))
    }
}
