package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.PersonDetail
import com.techgv.cineverse.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class MobilePersonState(
    val context: Long = 0,
    val invalid: Boolean = false,
    val settingsFailed: Boolean = false,
    val section: SectionState<PersonDetail> = SectionState.Loading,
    val expanded: Boolean = false,
    val index: Int = 0,
    val offset: Int = 0,
)

private data class MobilePersonScalars(val expanded: Boolean, val index: Int, val offset: Int)

/** Present malformed values are rejected, never coerced, repaired or erased. */
private fun mobilePersonScalars(id: Int, saved: SavedStateHandle): MobilePersonScalars? = try {
    require(id > 0)
    val expanded = saved.get<Any?>("bioExpanded")
    val index = saved.get<Any?>("person.index")
    val offset = saved.get<Any?>("person.offset")
    require(expanded == null || expanded is Boolean)
    require(index == null || index is Int && index >= 0)
    require(offset == null || offset is Int && offset >= 0)
    MobilePersonScalars(expanded as? Boolean ?: false, index as? Int ?: 0, offset as? Int ?: 0)
} catch (_: IllegalArgumentException) { null }

/**
 * Entry-owned, initially inactive. Root forwards visibility/resume and idle generation changes;
 * graph.contextChanges is also observed. rawSettings MUST be the real cold reader, not graph.settings.
 * Each actual settings emission owns a fresh shared holder with immutable input and gated saver writes.
 */
internal class MobilePersonViewModel(
    private val graph: MobileApplication,
    val id: Int,
    private val savedState: SavedStateHandle,
    private val rawSettings: Flow<Settings> = graph.settingsFlow,
    parentScope: CoroutineScope? = null,
) : ViewModel() {
    private val parent = parentScope ?: viewModelScope
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val saver = SavedStateHandleStateSaver(savedState)
    private val valid = mobilePersonScalars(id, savedState) != null
    private var active = false
    private var closed = false
    private var serial = 0L
    private var readerEpoch = 0L
    private var recovering: Long? = null
    private var reader: Job? = null
    private data class Stamp(val generation: Long, val event: Long, val failed: Boolean)
    private fun stamp() = Stamp(graph.generation.current, graph.contextChanges.value, graph.settingsFailed.value)
    private var ownerStamp = stamp()
    private class Read(val token: Long, val epoch: Long, val stamp: Stamp, val job: Job) {
        lateinit var holder: PersonStateHolder
    }
    private var read: Read? = null
    private val mutableState = MutableStateFlow(initialState())
    val state = mutableState.asStateFlow()
    val imageBase: String get() = graph.catalog.imageBaseUrl()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previous = stamp()
            combine(graph.contextChanges, graph.settingsFailed) { _, _ -> stamp() }.collect { next ->
                if (next != previous) {
                    previous = next
                    if (active && !closed && recovering == null && ownerStamp != next) contextChanged()
                }
            }
        }
    }

    private fun initialState(failed: Boolean = graph.settingsFailed.value): MobilePersonState {
        val scalars = mobilePersonScalars(id, savedState)
        return MobilePersonState(serial, invalid = !valid || scalars == null, settingsFailed = failed,
            expanded = scalars?.expanded ?: false, index = scalars?.index ?: 0, offset = scalars?.offset ?: 0)
    }

    private fun alive() = !closed && job.isActive && active
    private fun callback(token: Long) = alive() && token == serial && ownerStamp == stamp()
    private fun readerCurrent(epoch: Long, source: Stamp) = alive() && valid && epoch == readerEpoch && source == stamp() && !source.failed
    private fun current(candidate: Read) = read === candidate && candidate.job.isActive && candidate.token == serial &&
        readerCurrent(candidate.epoch, candidate.stamp) && mobilePersonScalars(id, savedState) != null
    private fun confirmed(candidate: Read): Boolean = current(candidate) &&
        (state.value.section as? SectionState.Content)?.value?.id == id

    /** Detach and revoke BEFORE publication/cancellation; reentrant owners can only supersede us. */
    private fun revoke(failed: Boolean = graph.settingsFailed.value): Long {
        val token = ++serial
        recovering = null
        readerEpoch++
        ownerStamp = stamp()
        val oldReader = reader
        val oldRead = read
        reader = null
        read = null
        mutableState.value = initialState(failed)
        oldRead?.job?.cancel()
        oldReader?.cancel()
        return token
    }

    private fun subscribe(token: Long) {
        if (!callback(token) || !valid || graph.settingsFailed.value || mobilePersonScalars(id, savedState) == null) return
        val epoch = readerEpoch
        val source = stamp()
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                rawSettings.collect { captured ->
                    ensureActive()
                    if (readerCurrent(epoch, source)) replace(captured, epoch, source)
                }
            } catch (_: StorageException) {
                if (readerCurrent(epoch, source)) revoke(failed = true)
            }
        }
        if (!callback(token)) { next.cancel(); return }
        reader = next
        next.start()
    }

    private fun replace(settings: Settings, epoch: Long, source: Stamp) {
        val scalars = mobilePersonScalars(id, savedState) ?: return
        if (!readerCurrent(epoch, source)) return
        val candidate = Read(++serial, epoch, source, SupervisorJob(job))
        val old = read
        read = candidate
        ownerStamp = source
        mutableState.value = initialState(failed = false)
        old?.job?.cancel()
        if (!current(candidate)) { candidate.job.cancel(); return }
        val snapshot = SavedStateHandleStateSaver(SavedStateHandle(mapOf(
            "bioExpanded" to scalars.expanded, "person.index" to scalars.index, "person.offset" to scalars.offset)))
        val gatedSaver = object : StateSaver {
            override fun getString(key: String) = snapshot.getString(key)
            override fun getInt(key: String) = snapshot.getInt(key)
            override fun getBoolean(key: String) = snapshot.getBoolean(key)
            override fun putString(key: String, value: String?) { if (confirmed(candidate)) saver.putString(key, value) }
            override fun putInt(key: String, value: Int?) { if (confirmed(candidate)) saver.putInt(key, value) }
            override fun putBoolean(key: String, value: Boolean?) { if (confirmed(candidate)) saver.putBoolean(key, value) }
        }
        val child = CoroutineScope(scope.coroutineContext + candidate.job)
        candidate.holder = PersonStateHolder(id, graph.catalog, flowOf(settings), graph.generation, gatedSaver, child)
        if (!current(candidate)) { candidate.job.cancel(); return }
        child.launch {
            candidate.holder.person.collect { section ->
                if (!current(candidate)) return@collect
                val person = (section as? SectionState.Content)?.value
                mutableState.value = state.value.copy(section = if (person != null && person.id != id)
                    SectionState.Error(SectionErrorKind.NotAvailable) else section,
                    expanded = candidate.holder.bioExpanded.value)
            }
        }
    }

    fun setActive(value: Boolean) {
        if (closed || active == value) return
        active = value
        val token = revoke()
        if (value) subscribe(token)
    }

    fun contextChanged() {
        if (!alive()) return
        val token = revoke()
        subscribe(token)
    }

    fun retry(token: Long) {
        if (!callback(token) || !valid) return
        val revision = revoke()
        if (!callback(revision)) return
        // Defer only our synchronous graph recovery events; explicit hide/close/context still supersedes.
        recovering = revision
        try { graph.retrySettings() } finally { if (recovering == revision) recovering = null }
        if (serial == revision && alive()) {
            val latest = revoke()
            subscribe(latest)
        }
    }

    /** Render-time guard closes the gap before an asynchronous graph-event collector runs. */
    fun visibleState(snapshot: MobilePersonState): MobilePersonState =
        if (callback(snapshot.context)) snapshot else snapshot.copy(section = SectionState.Loading,
            settingsFailed = graph.settingsFailed.value)

    fun toggleBiography(token: Long) {
        val candidate = read ?: return
        if (token != candidate.token || !confirmed(candidate)) return
        candidate.holder.toggleBio()
        if (confirmed(candidate)) mutableState.value = state.value.copy(expanded = candidate.holder.bioExpanded.value)
    }

    fun saveScroll(token: Long, index: Int, offset: Int) {
        val candidate = read ?: return
        if (index < 0 || offset < 0 || token != candidate.token || !confirmed(candidate)) return
        saver.putInt("person.index", index)
        if (!confirmed(candidate)) return
        saver.putInt("person.offset", offset)
        if (confirmed(candidate)) mutableState.value = state.value.copy(index = index, offset = offset)
    }

    fun open(token: Long, ref: MediaRef, navigator: MobileNavigator) {
        val candidate = read ?: return
        if (token != candidate.token || !confirmed(candidate) || ref.id <= 0) return
        val person = (state.value.section as? SectionState.Content)?.value ?: return
        if (person.knownFor.any { it.ref == ref } || person.credits.any { it.ref == ref })
            navigator.open(MobileDestination.Detail(ref))
    }

    fun back(token: Long, onBack: () -> Unit) { if (callback(token)) onBack() }

    fun close() {
        if (closed) return
        closed = true
        active = false
        revoke()
        job.cancel()
    }

    override fun onCleared() = close()
}
