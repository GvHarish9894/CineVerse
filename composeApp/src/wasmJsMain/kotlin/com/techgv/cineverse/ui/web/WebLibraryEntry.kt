package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Extract ONLY from the same immutable holder result, never from the current session or display name.
 * Root binding is blocked until LibraryUiState has a reviewed known-identity + AuthSession producer seam.
 */
sealed interface WebLibraryResultIdentity {
    data object Guest : WebLibraryResultIdentity
    data class Account(val session: AuthSession) : WebLibraryResultIdentity
}

internal fun WebSessionState.Ready.libraryIdentity(): WebLibraryResultIdentity =
    session?.let(WebLibraryResultIdentity::Account) ?: WebLibraryResultIdentity.Guest

internal fun WebRoute.libraryTab(): LibraryTab = when ((this as? WebRoute.Library)?.tab) {
    "watchlist" -> LibraryTab.Watchlist
    "favourites" -> LibraryTab.Favourites
    "ratings" -> LibraryTab.Rated
    else -> throw IllegalArgumentException("Invalid Library route")
}

internal fun LibraryTab.libraryRoute() = WebRoute.Library(when (this) {
    LibraryTab.Watchlist -> "watchlist"
    LibraryTab.Favourites -> "favourites"
    LibraryTab.Rated -> "ratings"
})

internal fun MediaType.libraryKey(scalar: String) = "library.${if (this == MediaType.Movie) "movie" else "tv"}.$scalar"

internal data class WebLibraryPosition(val page: Int = 1, val index: Int = 0, val offset: Int = 0)
internal data class WebLibraryRestore(val tab: LibraryTab, val positions: Map<MediaType, WebLibraryPosition>)

/** Read strings, not forgiving typed getters: present invalid history must never become an absent default. */
internal fun validateWebLibraryEntry(entry: WebEntry): WebLibraryRestore {
    val tab = entry.route.libraryTab()
    entry.getString("library.tab")?.let { require(it == tab.name) { "Contradictory Library tab" } }
    fun scalar(key: String, default: Int, page: Boolean = false): Int {
        val raw = entry.getString(key) ?: return default
        require(raw.matches(Regex("0|[1-9][0-9]*"))) { "Invalid Library scalar" }
        val number = raw.toIntOrNull() ?: throw IllegalArgumentException("Invalid Library scalar")
        require(if (page) number in 1..500 else number >= 0) { "Invalid Library scalar" }
        return number
    }
    val positions = MediaType.entries.associateWith { media ->
        require((entry.getString(media.libraryKey("index")) == null) == (entry.getString(media.libraryKey("offset")) == null)) { "Incomplete Library position" }
        WebLibraryPosition(scalar(media.libraryKey("page"), 1, true), scalar(media.libraryKey("index"), 0), scalar(media.libraryKey("offset"), 0))
    }
    return WebLibraryRestore(tab, positions)
}

/** One verified entry lifetime. Storage recovery keeps this owner/holder and never replays mutations. */
internal class WebLibraryOwner(
    val entry: WebEntry,
    parentScope: CoroutineScope,
    private val verified: WebSessionState.Ready,
    private val currentSession: () -> WebSessionState,
    private val resultIdentity: (LibraryUiState) -> WebLibraryResultIdentity?,
    private val resultPage: (LibraryUiState, MediaType) -> Int?,
    private val retryUpstreamStorage: () -> Unit,
    createHolder: (StateSaver, CoroutineScope) -> LibraryStateHolder,
) : AutoCloseable {
    val restored = validateWebLibraryEntry(entry)
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private var closed = false
    private val holder: LibraryStateHolder
    private val shown = MutableStateFlow<LibraryUiState?>(null)
    val state = shown.asStateFlow()
    private val pageState = MutableStateFlow(MediaType.entries.associateWith { null as Int? })
    val pages = pageState.asStateFlow()

    init {
        if (entry.getString("library.tab") == null) entry.putString("library.tab", restored.tab.name)
        holder = createHolder(entry, scope)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            holder.state.collect { state ->
                if (!usable(state)) { shown.value = null; return@collect }
                pageState.value = MediaType.entries.associateWith { media ->
                    if (verified.session == null) null else resultPage(state, media)?.takeIf { it in 1..500 }
                }
                shown.value = state
            }
        }
        // Invalidates the list cache before the holder's initial account reads (or replaces those in flight).
        holder.revalidate()
    }

    private fun usable(state: LibraryUiState): Boolean = !closed && currentSession() == verified &&
        state.tab == restored.tab && state.signedIn == (verified.session != null) && resultIdentity(state) == verified.libraryIdentity()

    fun visibleState(): LibraryUiState? = shown.value?.takeIf(::usable)

    fun loadMore(media: MediaType) {
        val group = visibleState()?.group(media) ?: return
        val page = pages.value[media] ?: return
        if (page >= 500 || !group.hasMore || group.loadingMore || group.moreError != null || group.refreshError != null || group.section !is SectionState.Content) return
        holder.loadMore(media)
    }

    fun retry(media: MediaType) {
        val group = visibleState()?.group(media) ?: return
        if (group.moreError != null && group.refreshError == null && (pages.value[media] ?: 500) >= 500) return
        holder.retry(media)
    }

    fun revalidate() {
        if (closed || currentSession() != verified) return
        if (verified.session == null) holder.retry(MediaType.Movie) else holder.revalidate()
    }

    fun retryStorage() {
        if (closed || currentSession() != verified) return
        retryUpstreamStorage()
        // Explicit resubscription is necessary even when the recovered upstream values compare equal.
        // Guest retry restarts both readers; account revalidate reloads retained pages, never a failed write.
        if (verified.session == null) holder.retry(MediaType.Movie) else holder.revalidate()
    }

    override fun close() {
        if (closed) return
        closed = true
        shown.value = null
        job.cancel()
    }
}
