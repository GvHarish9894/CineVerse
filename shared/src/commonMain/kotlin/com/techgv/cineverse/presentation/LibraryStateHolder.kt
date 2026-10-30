package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.storage.StorageException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.LibraryEntry
import com.techgv.cineverse.data.library.LibraryPage
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryTab(val label: String, val kind: LibraryKind) {
    Watchlist("Watchlist", LibraryKind.WATCHLIST),
    Favourites("Favourites", LibraryKind.FAVOURITE),
    Rated("Rated", LibraryKind.RATING),
}

/** One of the two groups of a tab (§7.8): its own paging, loading and error. */
data class LibraryGroup(
    val section: SectionState<List<LibraryEntry>> = SectionState.Loading,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    /** A next-page load failed; the loaded items stay. */
    val moreError: SectionErrorKind? = null,
    /** A revalidation failed: the shown items may be stale; "Try again" reloads. */
    val refreshError: SectionErrorKind? = null,
    /** Last account page in this completed result (1..500); null before a read or for unpaged guest data. */
    val loadedPage: Int? = null,
)

data class LibraryUiState(
    /** Null while the identity is unknown or for a guest; see [signedIn]. */
    val accountName: String? = null,
    val signedIn: Boolean = false,
    val tab: LibraryTab = LibraryTab.Watchlist,
    val movies: LibraryGroup = LibraryGroup(),
    val tv: LibraryGroup = LibraryGroup(),
    /** False until the repository emits a source; known null means guest source, not verified web guest. */
    val sourceIdentityKnown: Boolean = false,
    /** Token-free identity captured for these groups; compare the full value with authoritative web Ready. */
    val sourceIdentity: AuthSession? = null,
) {
    fun group(media: MediaType) = if (media == MediaType.Movie) movies else tv
}

/**
 * §7.8 My Library. Guest: Room flows, newest first. Signed in: two independently paged TMDB groups per tab,
 * each its own generation scope (§1.2) so a movie-page response never invalidates a TV-page one. Lists
 * revalidate on entry and after any library mutation; a revalidation keeps the shown items until it lands.
 * [scope]: see [HomeStateHolder].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryStateHolder(
    private val repository: LibraryRepository,
    private val saver: StateSaver,
    scope: CoroutineScope? = null,
    private val onStorageFailure: (StorageException) -> Unit = {},
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val guestReads = MutableStateFlow(0L)
    private val tab = MutableStateFlow(saver.getString(TAB)?.let(LibraryTab::valueOf) ?: LibraryTab.Watchlist)
    private val _state = MutableStateFlow(LibraryUiState(tab = tab.value))
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val generations = MediaType.entries.associateWith { GenerationCounter() }
    // Defer one-shot paging until replacement succeeds, so retained and fresh pages never mix.
    private val reloading = mutableSetOf<MediaType>()
    private val pendingMore = mutableSetOf<MediaType>()

    /** The signed-in scope the account groups load for; null for a guest. */
    private var listScope: Pair<AuthSession, LibraryTab>? = null

    init {
        this.scope.launch {
            combine(repository.session.distinctUntilChanged(), tab) { c, t -> c to t }.collectLatest { (c, t) ->
                generations.values.forEach { it.bump() }
                reloading.clear()
                pendingMore.clear()
                listScope = c?.let { it to t }
                _state.update { it.copy(
                    accountName = c?.displayName, signedIn = c != null, tab = t,
                    movies = LibraryGroup(), tv = LibraryGroup(),
                    sourceIdentityKnown = true, sourceIdentity = c,
                ) }
                if (c == null) {
                    guestReads.flatMapLatest { combine(repository.guestEntries(t.kind, MediaType.Movie), repository.guestEntries(t.kind, MediaType.Tv)) { m, tv ->
                        LibraryGroup(m.toSection()) to LibraryGroup(tv.toSection())
                    }.catch { failure ->
                        if (failure !is StorageException) throw failure
                        currentCoroutineContext().ensureActive()
                        onStorageFailure(failure)
                        MediaType.entries.forEach { media -> setGroup(media) { it.refreshFailed(SectionErrorKind.Generic) } }
                    } }.collect { (m, tv) ->
                        currentCoroutineContext().ensureActive()
                        _state.update { it.copy(movies = m, tv = tv) }
                    }
                } else {
                    MediaType.entries.forEach(::reload)
                    repository.listRevision.drop(1).collect { MediaType.entries.forEach(::reload) }
                }
            }
        }
    }

    /** §7.8: selects [t] (TV: on focus); the previous tab's loads are dropped. */
    fun selectTab(t: LibraryTab) {
        saver.putString(TAB, t.name)
        tab.value = t
    }

    /**
     * "Revalidate on entry": past the 10-min list cache, so remote changes show. A load already in flight is
     * replaced (its generation is dropped); before the first load the invalidation alone is enough.
     */
    fun revalidate() {
        repository.invalidateAccountLists()
        MediaType.entries.forEach(::reload)
    }

    fun retry(media: MediaType) {
        if (listScope == null) { guestReads.update { it + 1 }; return }
        val group = _state.value.group(media)
        if (group.section is SectionState.Content && group.refreshError == null) loadMore(media) else reload(media)
    }

    fun loadMore(media: MediaType) {
        val (c, t) = listScope ?: return
        val group = _state.value.group(media)
        val loadedPage = group.loadedPage ?: return
        if (!group.hasMore || group.section !is SectionState.Content || loadedPage >= MAX_PAGE) return
        if (media in reloading) { pendingMore += media; return }
        if (group.loadingMore) return
        val generation = generations.getValue(media).bump()
        val next = loadedPage + 1
        setAccountGroup(media, c, t, generation) { it.copy(loadingMore = true, moreError = null) }
        scope.launch {
            try {
                val page = repository.accountPage(c, t.kind, media, next).checked(next)
                currentCoroutineContext().ensureActive()
                if (!generations.getValue(media).isCurrent(generation)) return@launch
                setAccountGroup(media, c, t, generation) {
                    val shown = (it.section as? SectionState.Content)?.value.orEmpty()
                    val known = shown.mapTo(HashSet()) { e -> e.title.ref }
                    // Items shift between pages when the list changes remotely: never show a title twice.
                    it.copy(
                        section = SectionState.Content(shown + page.entries.filter { e -> e.title.ref !in known }),
                        hasMore = page.hasMore && page.page < MAX_PAGE, loadingMore = false, moreError = null,
                        loadedPage = page.page,
                    )
                }
            } catch (failure: StorageException) {
                currentCoroutineContext().ensureActive()
                if (generations.getValue(media).isCurrent(generation)) {
                    onStorageFailure(failure)
                    setAccountGroup(media, c, t, generation) { it.copy(loadingMore = false, moreError = SectionErrorKind.Generic) }
                }
            } catch (e: TmdbException) {
                currentCoroutineContext().ensureActive()
                if (generations.getValue(media).isCurrent(generation)) {
                    setAccountGroup(media, c, t, generation) { it.copy(loadingMore = false, moreError = e.toSectionErrorKind()) }
                }
            }
        }
    }

    /** Reloads the pages loaded so far (cache hits unless a write invalidated them). */
    private fun reload(media: MediaType) {
        val (c, t) = listScope ?: return
        val generation = generations.getValue(media).bump()
        val pageCount = _state.value.group(media).loadedPage ?: 1
        reloading += media
        pendingMore -= media
        if (_state.value.group(media).loadedPage == null) setAccountGroup(media, c, t, generation) { LibraryGroup() }
        scope.launch {
            var completed = false
            try {
                val pages = mutableListOf<LibraryPage>()
                for (n in 1..pageCount) {
                    pages += repository.accountPage(c, t.kind, media, n).checked(n)
                    currentCoroutineContext().ensureActive()
                    if (!generations.getValue(media).isCurrent(generation)) return@launch
                    if (!pages.last().hasMore) break
                }
                setAccountGroup(media, c, t, generation) { LibraryGroup(
                    pages.flatMap { it.entries }.distinctBy { it.title.ref }.toSection(),
                    pages.last().hasMore && pages.last().page < MAX_PAGE,
                    loadedPage = pages.last().page,
                ) }
                completed = true
            } catch (failure: StorageException) {
                currentCoroutineContext().ensureActive()
                if (generations.getValue(media).isCurrent(generation)) {
                    onStorageFailure(failure)
                    setAccountGroup(media, c, t, generation) { it.refreshFailed(SectionErrorKind.Generic) }
                }
            } catch (e: TmdbException) {
                currentCoroutineContext().ensureActive()
                if (!generations.getValue(media).isCurrent(generation)) return@launch
                // A failed revalidation keeps the items already shown, marked stale with the error and a retry.
                setAccountGroup(media, c, t, generation) { it.refreshFailed(e.toSectionErrorKind()) }
            } finally {
                if (generations.getValue(media).isCurrent(generation)) {
                    reloading -= media
                    val requestedMore = pendingMore.remove(media)
                    if (completed && requestedMore && currentCoroutineContext().isActive && listScope == (c to t)) loadMore(media)
                }
            }
        }
    }

    private fun LibraryPage.checked(expectedPage: Int): LibraryPage {
        if (page != expectedPage || page !in 1..MAX_PAGE) throw TmdbException(TmdbError.Malformed)
        return this
    }

    private fun setGroup(media: MediaType, change: (LibraryGroup) -> LibraryGroup) = _state.update {
        if (media == MediaType.Movie) it.copy(movies = change(it.movies)) else it.copy(tv = change(it.tv))
    }

    private fun setAccountGroup(
        media: MediaType, identity: AuthSession, selectedTab: LibraryTab, generation: Long,
        change: (LibraryGroup) -> LibraryGroup,
    ) = _state.update {
        if (!it.sourceIdentityKnown || it.sourceIdentity != identity || it.tab != selectedTab ||
            !generations.getValue(media).isCurrent(generation)) it
        else if (media == MediaType.Movie) it.copy(movies = change(it.movies)) else it.copy(tv = change(it.tv))
    }

    private fun LibraryGroup.refreshFailed(error: SectionErrorKind): LibraryGroup =
        if (section is SectionState.Content) copy(loadingMore = false, refreshError = error)
        else LibraryGroup(SectionState.Error(error), loadedPage = loadedPage)

    private fun List<LibraryEntry>.toSection(): SectionState<List<LibraryEntry>> =
        if (isEmpty()) SectionState.Empty else SectionState.Content(this)

    private companion object {
        const val TAB = "library.tab"
        const val MAX_PAGE = 500
    }
}
