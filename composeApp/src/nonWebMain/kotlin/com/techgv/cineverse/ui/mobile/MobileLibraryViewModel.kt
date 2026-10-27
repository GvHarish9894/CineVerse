package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class MobileLibraryPosition(
    val index: Int = 0,
    val offset: Int = 0,
    val moviePage: Int? = null,
    val tvPage: Int? = null,
) {
    fun page(media: MediaType) = if (media == MediaType.Movie) moviePage else tvPage
}

internal data class MobileLibraryState(
    val context: Long = 0,
    val invalid: Boolean = false,
    val failed: Boolean = false,
    val library: LibraryUiState? = null,
    val position: MobileLibraryPosition = MobileLibraryPosition(),
    val scrollReady: Boolean = false,
)

/** Entry-owned, initially inactive. Parent must revoke visibility before navigation/background/disposal. */
internal class MobileLibraryViewModel(
    private val graph: MobileApplication,
    private val repository: LibraryRepository,
    private val savedState: SavedStateHandle,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val ownerScope = scope ?: viewModelScope
    private val saver = SavedStateHandleStateSaver(savedState)
    private val valid = validSavedState()
    private var tab = if (valid) saver.getString("library.tab")?.let(LibraryTab::valueOf) ?: LibraryTab.Watchlist else LibraryTab.Watchlist
    private val mutableState = MutableStateFlow(MobileLibraryState(invalid = !valid))
    val state = mutableState.asStateFlow()
    private var active = false
    private var closed = false
    private var failed = false
    private var serial = 0L
    private var child: Job? = null
    private var holder: LibraryStateHolder? = null
    private var context: ReadContext? = null
    private var positionIdentity: MobileIdentity? = null
    // shortcut: process-restored positions have no source proof; restore deep pages/scroll only within this owner.
    private val positions = mutableMapOf<LibraryTab, MobileLibraryPosition>()
    private val blockedRestore = mutableSetOf<MediaType>()
    private data class ReadContext(val identity: MobileIdentity, val preferences: Triple<String, String, Boolean>, val generation: Long, val tab: LibraryTab)

    private val observer = ownerScope.launch {
        combine(graph.identity, graph.settings, graph.identityFailed, graph.settingsFailed, graph.storageFailed) { _, _, _, _, _ -> Unit }
            .collect { reconcile() }
    }

    fun setActive(value: Boolean) {
        if (closed || active == value) return
        active = value
        reconcile()
    }

    private fun currentContext(): ReadContext? {
        if (!active || closed || !valid || failed || graph.identityFailed.value || graph.settingsFailed.value || graph.storageFailed.value) return null
        val identity = graph.identity.value ?: return null
        val settings = graph.settings.value ?: return null
        return ReadContext(identity, Triple(settings.language, settings.region, settings.includeAdult), graph.generation.current, tab)
    }

    private fun reconcile(expectedSerial: Long = serial) {
        if (expectedSerial != serial) return
        val next = currentContext()
        if (next == null) {
            revoke()
            return
        }
        if (context == next && child != null) return
        val token = revoke()
        if (token != serial || next != currentContext()) return
        if (positionIdentity != next.identity) {
            positions.clear()
            positionIdentity = next.identity
        }
        context = next
        blockedRestore.clear()
        val job = SupervisorJob(ownerScope.coroutineContext[Job])
        child = job
        val reads = CoroutineScope(ownerScope.coroutineContext + job + CoroutineExceptionHandler { _, failure ->
            if (failure is StorageException) storageFailure(token) else throw failure
        })
        // Install authority before publication or constructor-launched session subscriptions can reenter.
        val start = reads.launch(start = CoroutineStart.LAZY) {
            if (!accepts(token) || child !== job) return@launch
            val library = LibraryStateHolder(repository, saver, reads) { storageFailure(token) }
            if (!accepts(token) || child !== job) return@launch
            holder = library
            // Before initial session collection, invalidation is sufficient; no duplicate first-page reload.
            library.revalidate()
            if (!accepts(token) || holder !== library) return@launch
            library.state.collect { accept(token, library, it) }
        }
        mutableState.value = MobileLibraryState(token, position = positions[tab] ?: MobileLibraryPosition())
        if (accepts(token) && child === job) start.start()
    }

    /** Revoke both display and callback authority synchronously, then cancel only the detached old work. */
    private fun revoke(): Long {
        val token = ++serial
        val previous = child
        child = null
        context = null
        holder = null
        mutableState.value = MobileLibraryState(token, invalid = !valid,
            failed = failed || graph.identityFailed.value || graph.settingsFailed.value || graph.storageFailed.value,
            position = positions[tab] ?: MobileLibraryPosition())
        previous?.cancel()
        return token
    }

    private fun storageFailure(token: Long) {
        if (!accepts(token)) return
        failed = true
        revoke()
    }

    private fun accepts(token: Long): Boolean = token == serial && context != null && context == currentContext()

    /** A graph event may precede the owner's collector; never render or dispatch from that stale snapshot. */
    fun visibleState(snapshot: MobileLibraryState): MobileLibraryState =
        if (snapshot.library != null && !accepts(snapshot.context)) snapshot.copy(library = null, scrollReady = false,
            failed = failed || graph.identityFailed.value || graph.settingsFailed.value || graph.storageFailed.value) else snapshot

    private fun accept(token: Long, library: LibraryStateHolder, payload: LibraryUiState) {
        if (!accepts(token) || holder !== library) return
        if (!payload.sourceIdentityKnown || payload.sourceIdentity != context?.identity?.session || payload.tab != tab) {
            mutableState.value = mutableState.value.copy(library = null, scrollReady = false)
            return
        }
        var position = positions[tab] ?: MobileLibraryPosition()
        var settled = true
        val more = mutableListOf<MediaType>()
        MediaType.entries.forEach { media ->
            val group = payload.group(media)
            val error = group.section is SectionState.Error || group.moreError != null || group.refreshError != null
            if (error) blockedRestore += media
            val loaded = group.loadedPage
            val target = position.page(media) ?: 1
            val needsPage = loaded != null && loaded < target && group.hasMore
            if (group.section == SectionState.Loading || error || needsPage) settled = false
            if (needsPage && !group.loadingMore && media !in blockedRestore) more += media
            if (loaded != null && !error && (!needsPage || !group.hasMore)) {
                position = if (media == MediaType.Movie) position.copy(moviePage = loaded) else position.copy(tvPage = loaded)
                saver.putInt("mobileLibrary.${tab.name}.${if (media == MediaType.Movie) "moviePage" else "tvPage"}", loaded)
            }
        }
        positions[tab] = position
        mutableState.value = MobileLibraryState(token, library = payload, position = position, scrollReady = settled)
        more.forEach {
            if (!accepts(token) || holder !== library) return
            library.loadMore(it)
        }
    }

    private fun confirmed(token: Long): LibraryUiState? = mutableState.value.library?.takeIf {
        accepts(token) && it.sourceIdentityKnown && it.sourceIdentity == context?.identity?.session
    }

    fun selectTab(token: Long, value: LibraryTab) {
        if (confirmed(token) == null || value == tab) return
        saver.putString("library.tab", value.name)
        tab = value
        val revision = revoke()
        reconcile(revision)
    }

    fun saveScroll(token: Long, index: Int, offset: Int) {
        if (confirmed(token) == null || !mutableState.value.scrollReady || index < 0 || offset < 0) return
        val position = (positions[tab] ?: MobileLibraryPosition()).copy(index = index, offset = offset)
        positions[tab] = position
        saver.putInt("mobileLibrary.${tab.name}.index", index)
        saver.putInt("mobileLibrary.${tab.name}.offset", offset)
        mutableState.value = mutableState.value.copy(position = position)
    }

    fun loadMore(token: Long, media: MediaType) {
        val group = confirmed(token)?.group(media) ?: return
        if (group.section !is SectionState.Error && group.moreError == null && group.refreshError == null) holder?.loadMore(media)
    }

    fun retry(token: Long, media: MediaType) {
        if (confirmed(token) == null) return
        blockedRestore -= media
        holder?.retry(media)
    }

    fun retryStorage(token: Long = mutableState.value.context) {
        if (token != serial || !active || closed || !valid) return
        val revision = revoke()
        if (revision != serial || !active || closed) return
        failed = false
        graph.retrySettings()
        if (revision != serial || !active || closed) return
        graph.retryIdentity()
        if (revision != serial || !active || closed) return
        graph.retryLibraryStorage()
        reconcile(revision) // Equal recovered values must restart this repository's actual session/guest readers.
    }

    fun refresh(token: Long) {
        if (confirmed(token) == null) return
        val revision = revoke()
        reconcile(revision)
    }

    fun open(token: Long, ref: MediaRef, navigator: MobileNavigator) {
        val library = confirmed(token) ?: return
        val entries = (library.group(ref.mediaType).section as? SectionState.Content)?.value.orEmpty()
        if (entries.any { it.title.ref == ref }) navigator.open(MobileDestination.Detail(ref))
    }

    fun signIn(token: Long, onSignIn: () -> Unit) {
        if (confirmed(token)?.signedIn == false) onSignIn()
    }

    fun explore(token: Long, onExplore: () -> Unit) {
        if (confirmed(token) != null) onExplore()
    }

    private fun validSavedState(): Boolean = try {
        val rawTab = savedState.get<Any?>("library.tab")
        require(rawTab == null || rawTab is String)
        (rawTab as? String)?.let(LibraryTab::valueOf)
        LibraryTab.entries.forEach { tab ->
            val prefix = "mobileLibrary.${tab.name}."
            listOf("moviePage", "tvPage").forEach { key ->
                val value = savedState.get<Any?>(prefix + key)
                require(value == null || value is Int && value in 1..500)
            }
            val index = savedState.get<Any?>(prefix + "index")
            val offset = savedState.get<Any?>(prefix + "offset")
            require((index == null) == (offset == null))
            require(index == null || index is Int && index >= 0)
            require(offset == null || offset is Int && offset >= 0)
        }
        true
    } catch (_: IllegalArgumentException) { false }

    fun close() {
        if (closed) return
        closed = true
        revoke()
        observer.cancel()
    }

    override fun onCleared() = close()
}
