package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.library.LibraryStateSnapshot
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal enum class MobileDetailSegment { Overview, Cast, Videos, Reviews, Seasons }
internal data class MobileDetailScroll(val index: Int = 0, val offset: Int = 0)
internal data class MobileDetailAuthority(val lifetime: Long, val context: DetailReadContext, val identity: MobileIdentity)
internal data class MobileDetailState(
    val invalid: Boolean = false,
    val section: SectionState<Detail> = SectionState.Loading,
    val retained: Detail? = null,
    val stale: Boolean = false,
    val settingsFailed: Boolean = false,
    val libraryFailed: Boolean = false,
    val identityFailed: Boolean = false,
    val account: LibraryState = LibraryState.Unknown,
    val authority: MobileDetailAuthority? = null,
    val segment: MobileDetailSegment = MobileDetailSegment.Overview,
    val expanded: Boolean = false,
)

private data class MobileDetailEntry(val ref: MediaRef, val segment: MobileDetailSegment, val expanded: Boolean)

/** Reject wrong scalar types before creating a reader; malformed saved data is never rewritten. */
private fun validateMobileDetail(media: String, id: Int, saved: SavedStateHandle): MobileDetailEntry? = try {
    val type = when (media) {
        "movie" -> MediaType.Movie
        "tv" -> MediaType.Tv
        else -> throw IllegalArgumentException("Invalid title media")
    }
    require(id > 0)
    val segment = saved.get<Any?>("mobileDetail.segment").let {
        require(it == null || it is String)
        if (it == null) MobileDetailSegment.Overview else MobileDetailSegment.valueOf(it as String)
    }
    require(type == MediaType.Tv || segment != MobileDetailSegment.Seasons)
    val expanded = saved.get<Any?>("mobileDetail.expanded").let {
        require(it == null || it is Boolean)
        it as? Boolean ?: false
    }
    MobileDetailSegment.entries.forEach { tab ->
        listOf("index", "offset").forEach { scalar ->
            val raw = saved.get<Any?>("mobileDetail.${tab.name}.$scalar")
            require(raw == null || (raw is Int && raw >= 0))
        }
    }
    MobileDetailEntry(MediaRef(type, id), segment, expanded)
} catch (_: IllegalArgumentException) { null }

/** One actual producer snapshot proves completion, including cache hits, never HTTP freshness. */
internal fun DetailUiState.completedMobileDetail(ref: MediaRef, generation: Long): DetailReadContent? {
    val context = request ?: return null
    val completed = completion ?: return null
    if (settingsRead != DetailSettingsRead.Ready || context.ref != ref || context.appGeneration != generation ||
        completed.context != context || completed.result != detail) return null
    val content = (completed.result as? SectionState.Content)?.value ?: return null
    return retained?.takeIf { it.context == context && it.detail == content && content.ref == ref }
}

/** Source proof belongs to this exact repository emission, not the UI identity or equal button DTOs. */
internal fun LibraryStateSnapshot.mobileDetailAccount(identity: MobileIdentity?): LibraryState =
    if (identity != null && sourceIdentityKnown && sourceIdentity == identity.session) state else LibraryState.Unknown

/**
 * One native back-stack entry. Required flows are the raw settings reader and this title's repository
 * stateWithOrigin, never graph.settings.filterNotNull() or actions.state. Initially inactive: the root must
 * forward visibility/resume via setActive and idle generation/context invalidations via contextChanged.
 */
internal class MobileDetailViewModel(
    private val graph: MobileApplication,
    media: String,
    id: Int,
    private val savedState: SavedStateHandle,
    rawSettings: Flow<Settings>,
    private val libraryState: Flow<LibraryStateSnapshot>,
    parentScope: CoroutineScope? = null,
) : ViewModel() {
    private val entry = validateMobileDetail(media, id, savedState)
    val ref: MediaRef? get() = entry?.ref
    val imageBase: String get() = graph.catalog.imageBaseUrl()
    private val parent = parentScope ?: viewModelScope
    private val job = Job(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private var closed = false
    private var visible = false
    private var readsAllowed = false
    private var revision = 0L
    private var accountRevision = 0L
    private var accountJob: Job? = null
    private var snapshot = LibraryStateSnapshot(LibraryState.Unknown)
    private var accountFailed = false
    private val mutableState = MutableStateFlow(MobileDetailState(invalid = entry == null,
        segment = entry?.segment ?: MobileDetailSegment.Overview, expanded = entry?.expanded ?: false))
    val state = mutableState.asStateFlow()
    private val holder = entry?.let { valid ->
        DetailStateHolder(valid.ref, graph.catalog, flow {
            if (reading() && !graph.settingsFailed.value) rawSettings.collect { actual ->
                if (reading() && !graph.settingsFailed.value) emit(actual)
            }
        }, graph.generation, scope = scope)
    }

    init {
        holder?.cancelReads()
        job.invokeOnCompletion {
            visible = false
            readsAllowed = false
            revision++
            snapshot = LibraryStateSnapshot(LibraryState.Unknown)
            holder?.cancelReads()
            publish()
        }
        if (entry != null) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { holder!!.state.collect { publish() } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                combine(graph.settingsFailed, graph.identity, graph.identityFailed, graph.storageFailed) { settingsFailed, _, _, _ -> settingsFailed }
                    .collect { settingsFailed ->
                        if (settingsFailed && readsAllowed) revoke()
                        publish()
                    }
            }
        }
    }

    private fun alive() = !closed && job.isActive && entry != null
    private fun active() = alive() && visible
    private fun reading() = active() && readsAllowed
    private fun privateReady() = reading() && !graph.settingsFailed.value && !graph.identityFailed.value &&
        !graph.storageFailed.value && !accountFailed

    private fun completion(): DetailReadContent? = if (reading() && !graph.settingsFailed.value)
        holder?.state?.value?.completedMobileDetail(entry!!.ref, graph.generation.current) else null

    private fun publish() {
        if (entry == null) return
        val producer = holder?.state?.value ?: return
        val verified = completion()
        val retained = producer.retained?.takeIf { it.context.ref == entry.ref && it.detail.ref == entry.ref }?.detail
        val wrongRef = (producer.detail as? SectionState.Content)?.value?.ref?.let { it != entry.ref } == true
        val identity = graph.identity.value.takeIf { privateReady() }
        mutableState.value = mutableState.value.copy(
            section = when {
                wrongRef -> SectionState.Error(SectionErrorKind.Generic)
                verified != null -> SectionState.Content(verified.detail)
                producer.detail is SectionState.Error -> producer.detail
                else -> SectionState.Loading
            },
            retained = retained,
            stale = verified == null && retained != null,
            settingsFailed = graph.settingsFailed.value || producer.settingsRead == DetailSettingsRead.Failed,
            libraryFailed = graph.storageFailed.value || accountFailed,
            identityFailed = graph.identityFailed.value,
            account = if (verified != null) snapshot.mobileDetailAccount(identity) else LibraryState.Unknown,
            authority = if (verified != null && identity != null) MobileDetailAuthority(revision, verified.context, identity) else null,
        )
    }

    /** Revoke before cancelling or invoking any graph callback that may synchronously reenter. */
    private fun revoke(): Long {
        val token = ++revision
        readsAllowed = false
        holder?.cancelReads()
        publish()
        return token
    }

    private fun stopAccount() {
        accountRevision++
        snapshot = LibraryStateSnapshot(LibraryState.Unknown)
        val previous = accountJob
        accountJob = null
        previous?.cancel()
    }

    private fun restartAccount() {
        stopAccount()
        if (!active()) return
        accountFailed = false
        val token = accountRevision
        publish()
        val next = scope.launch(start = CoroutineStart.LAZY) {
            val reader = currentCoroutineContext()[Job]!!
            try {
                libraryState.collect { value ->
                    if (active() && reader.isActive && token == accountRevision) {
                        snapshot = value
                        publish()
                    }
                }
            } catch (_: StorageException) {
                if (active() && reader.isActive && token == accountRevision) {
                    snapshot = LibraryStateSnapshot(LibraryState.Unknown)
                    accountFailed = true
                    publish()
                }
            }
        }
        accountJob = next
        next.start()
    }

    private fun restart(token: Long) {
        if (!active() || revision != token || graph.settingsFailed.value) return
        readsAllowed = true
        holder?.retryStorage() // Re-collect the ACTUAL raw reader, even for equal settings.
        if (active() && revision == token) restartAccount()
        publish()
    }

    fun setActive(active: Boolean) {
        if (closed || !job.isActive || visible == active) return
        visible = active
        if (entry == null) return // Invalid entries still allow their visible Back callback, never readers.
        val token = revoke()
        stopAccount()
        if (active) restart(token)
        publish()
    }

    fun retryReads() {
        if (!active()) return
        val token = revoke()
        stopAccount()
        if (!active() || revision != token) return
        graph.retrySettings()
        if (!active() || revision != token) return
        graph.retryIdentity()
        if (!active() || revision != token) return
        graph.retryLibraryStorage()
        restart(token) // No previous mutation is remembered or replayed.
    }

    fun contextChanged() {
        if (!active()) return
        val token = revoke()
        stopAccount()
        restart(token)
    }

    fun retryDetail() {
        if (reading() && !graph.settingsFailed.value) holder?.retry()
    }

    fun act(kind: LibraryKind, rendered: MobileDetailAuthority?) {
        if (!privateReady() || rendered == null || graph.identity.value != rendered.identity) return
        val completed = completion() ?: return
        if (rendered.lifetime != revision || rendered.context != completed.context ||
            !snapshot.mobileDetailAccount(rendered.identity).canToggle(kind)) return
        val title = completed.detail.toTitle()
        val expected = rendered.identity.session
        when (kind) {
            LibraryKind.WATCHLIST -> graph.actions.toggleWatchlist(title, expected)
            LibraryKind.FAVOURITE -> graph.actions.toggleFavourite(title, expected)
            LibraryKind.RATING -> graph.actions.openRate(title, expected)
        }
    }

    /** Public retained catalog IDs remain browsable, but never from a hidden or disposed callback. */
    fun browse(action: () -> Unit) { if (!closed && job.isActive && visible) action() }

    fun selectSegment(segment: MobileDetailSegment) {
        if (!alive() || (entry!!.ref.mediaType != MediaType.Tv && segment == MobileDetailSegment.Seasons)) return
        savedState["mobileDetail.segment"] = segment.name
        mutableState.value = state.value.copy(segment = segment)
    }

    fun setExpanded(expanded: Boolean) {
        if (!alive()) return
        savedState["mobileDetail.expanded"] = expanded
        mutableState.value = state.value.copy(expanded = expanded)
    }

    fun scroll(segment: MobileDetailSegment): MobileDetailScroll = if (entry == null) MobileDetailScroll() else MobileDetailScroll(
        savedState["mobileDetail.${segment.name}.index"] ?: 0, savedState["mobileDetail.${segment.name}.offset"] ?: 0)

    fun saveScroll(segment: MobileDetailSegment, index: Int, offset: Int) {
        if (!alive() || index < 0 || offset < 0) return
        savedState["mobileDetail.${segment.name}.index"] = index
        savedState["mobileDetail.${segment.name}.offset"] = offset
    }

    fun close() {
        if (closed) return
        closed = true
        visible = false
        revoke()
        stopAccount()
        job.cancel()
    }

    override fun onCleared() { close() }
}
