package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.settings.HapticStrength
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.presentation.Choice
import com.techgv.cineverse.presentation.SettingsStateHolder
import com.techgv.cineverse.presentation.Toggle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal enum class MobilePreferencePicker { Language, Region }

internal data class MobileProfileState(
    val context: Long = 0,
    val invalid: Boolean = false,
    val settings: Settings? = null,
    val readFailed: Boolean = false,
    val writeFailed: Boolean = false,
    val identity: MobileIdentity? = null,
    val identityFailed: Boolean = false,
    val picker: MobilePreferencePicker? = null,
    val index: Int = 0,
    val offset: Int = 0,
)

private data class ProfileScalars(val index: Int, val offset: Int, val picker: MobilePreferencePicker?)

/** Present malformed values (including null) remain untouched; only absent keys use initial values. */
private fun profileScalars(saved: SavedStateHandle): ProfileScalars? {
    val index = saved.get<Any?>("profile.index")
    val offset = saved.get<Any?>("profile.offset")
    val picker = saved.get<Any?>("profile.picker")
    if (saved.contains("profile.index") && (index !is Int || index < 0)) return null
    if (saved.contains("profile.offset") && (offset !is Int || offset < 0)) return null
    if (saved.contains("profile.picker") && (picker !is String || MobilePreferencePicker.entries.none { it.name == picker })) return null
    return ProfileScalars(index as? Int ?: 0, offset as? Int ?: 0,
        MobilePreferencePicker.entries.firstOrNull { it.name == picker })
}

internal fun Settings.profileToggle(toggle: Toggle): Boolean = when (toggle) {
    Toggle.Haptics -> haptics
    Toggle.Sounds -> sounds
    Toggle.Glow -> glow
    Toggle.Motion -> motion
    Toggle.Adult -> includeAdult
}

/**
 * Entry-owned and initially inactive. Supply the graph's actual settings/auth repositories, not a
 * cached graph.settings flow. Root owns activation, disposal and account dialogs/callbacks.
 */
internal class MobileProfileViewModel(
    private val graph: MobileApplication,
    private val repository: SettingsRepository,
    private val auth: AuthRepository,
    private val saved: SavedStateHandle,
    parentScope: CoroutineScope? = null,
) : ViewModel() {
    private val parent = parentScope ?: viewModelScope
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private var active = false
    private var closed = false
    private var serial = 0L
    private var readerJob: Job? = null
    private var entryJob: Job? = null
    private var holder: SettingsStateHolder? = null
    private var recovering = false
    private data class Stamp(val generation: Long, val event: Long, val settingsFailed: Boolean,
        val identity: MobileIdentity?, val identityFailed: Boolean)
    private fun stamp() = Stamp(graph.generation.current, graph.contextChanges.value,
        graph.settingsFailed.value, graph.identity.value, graph.identityFailed.value)
    private var source = stamp()
    private val mutableState = MutableStateFlow(initialState())
    val state = mutableState.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(graph.contextChanges, graph.settingsFailed, graph.identity, graph.identityFailed) { _, _, _, _ -> stamp() }
                .collect { next -> if (active && !closed && !recovering && next != source) contextChanged() }
        }
    }

    private fun initialState(readFailed: Boolean = graph.settingsFailed.value): MobileProfileState {
        val scalars = profileScalars(saved)
        return MobileProfileState(serial, invalid = scalars == null, readFailed = readFailed,
            identity = graph.identity.value.takeUnless { graph.identityFailed.value }, identityFailed = graph.identityFailed.value,
            picker = scalars?.picker, index = scalars?.index ?: 0, offset = scalars?.offset ?: 0)
    }

    private fun current(token: Long) = active && !closed && job.isActive && token == serial && source == stamp() && profileScalars(saved) != null
    private fun editable(token: Long) = current(token) && state.value.settings != null && !state.value.readFailed && !state.value.writeFailed && !graph.settingsFailed.value

    /** Revoke before cancellation/publication, so late callbacks cannot reuse a superseded entry. */
    private fun revoke(readFailed: Boolean = graph.settingsFailed.value): Long {
        val token = ++serial
        source = stamp()
        val oldReader = readerJob; val oldEntry = entryJob
        readerJob = null; entryJob = null; holder = null
        mutableState.value = initialState(readFailed)
        oldReader?.cancel(); oldEntry?.cancel()
        return token
    }

    private fun subscribe(token: Long) {
        if (!current(token) || graph.settingsFailed.value) return
        val childJob = SupervisorJob(job)
        val child = CoroutineScope(scope.coroutineContext + childJob)
        entryJob = childJob
        holder = SettingsStateHolder(repository, auth, child) {
            if (current(token)) mutableState.value = state.value.copy(writeFailed = true)
        }
        val reader = child.launch(start = CoroutineStart.LAZY) {
            try {
                repository.settings.collect { observed ->
                    ensureActive()
                    if (current(token)) mutableState.value = state.value.copy(settings = observed, readFailed = false)
                }
            } catch (_: StorageException) {
                if (current(token)) revoke(readFailed = true)
            }
        }
        readerJob = reader
        reader.start()
    }

    fun setActive(value: Boolean) {
        if (closed || active == value) return
        active = value
        val token = revoke()
        if (value) subscribe(token)
    }

    fun contextChanged() {
        if (!active || closed) return
        val token = revoke()
        subscribe(token)
    }

    fun retryReads(token: Long) {
        if (!current(token)) return
        val revision = revoke()
        if (!current(revision)) return
        // Graph recovery can publish synchronously; hide/close/context still revoke this revision.
        recovering = true
        try { graph.retrySettings() } finally { recovering = false }
        if (active && !closed && serial == revision) subscribe(revoke())
    }

    fun retryIdentity(token: Long) {
        if (!current(token)) return
        val revision = revoke()
        if (!current(revision)) return
        recovering = true
        try { graph.retryIdentity() } finally { recovering = false }
        if (active && !closed && serial == revision) subscribe(revoke())
    }

    /** Render-time check also covers graph changes before the event collector gets a turn. */
    fun visibleState(snapshot: MobileProfileState): MobileProfileState = if (current(snapshot.context)) snapshot else
        snapshot.copy(invalid = profileScalars(saved) == null, settings = null, identity = null, identityFailed = graph.identityFailed.value,
            readFailed = graph.settingsFailed.value, picker = null)

    fun setToggle(token: Long, toggle: Toggle, target: Boolean) {
        if (editable(token) && state.value.settings!!.profileToggle(toggle) != target) holder?.toggle(toggle)
    }

    fun cycleStrength(token: Long, rendered: HapticStrength) {
        if (editable(token) && state.value.settings!!.strength == rendered) holder?.cycle(Choice.Strength, 1)
    }

    fun openPicker(token: Long, picker: MobilePreferencePicker) {
        if (!editable(token)) return
        saved["profile.picker"] = picker.name
        if (editable(token)) mutableState.value = state.value.copy(picker = picker)
    }

    fun dismissPicker(token: Long) {
        if (!current(token)) return
        saved.remove<String>("profile.picker")
        if (current(token)) mutableState.value = state.value.copy(picker = null)
    }

    fun select(token: Long, picker: MobilePreferencePicker, value: String) {
        val supported = when (picker) {
            MobilePreferencePicker.Language -> Settings.LANGUAGES
            MobilePreferencePicker.Region -> Settings.REGIONS
        }
        if (!editable(token) || state.value.picker != picker || value !in supported) return
        val childJob = entryJob ?: return
        dismissPicker(token)
        CoroutineScope(scope.coroutineContext + childJob).launch {
            try {
                repository.update { previous ->
                    // Validate the actual global target at the transaction, not a display-label mapping.
                    if (!editable(token)) throw CancellationException("Profile preference context revoked")
                    when (picker) {
                        MobilePreferencePicker.Language -> {
                            require(value in Settings.LANGUAGES)
                            previous.copy(language = value)
                        }
                        MobilePreferencePicker.Region -> {
                            require(value in Settings.REGIONS)
                            previous.copy(region = value)
                        }
                    }
                }
            } catch (_: StorageException) {
                if (current(token)) mutableState.value = state.value.copy(writeFailed = true)
            }
        }
    }

    fun signIn(token: Long, onSignIn: () -> Unit) {
        if (current(token) && !graph.identityFailed.value && state.value.identity == MobileIdentity(null)) onSignIn()
    }

    fun signOut(token: Long, rendered: AuthSession, onSignOut: (AuthSession) -> Unit) {
        if (current(token) && !graph.identityFailed.value && state.value.identity?.session == rendered && graph.identity.value?.session == rendered)
            onSignOut(rendered)
    }

    fun saveScroll(token: Long, index: Int, offset: Int) {
        if (!editable(token) || index < 0 || offset < 0 || state.value.identity == null && !state.value.identityFailed) return
        saved["profile.index"] = index
        if (!editable(token)) return
        saved["profile.offset"] = offset
        if (editable(token)) mutableState.value = state.value.copy(index = index, offset = offset)
    }

    fun close() {
        if (closed) return
        closed = true; active = false
        revoke()
        job.cancel()
    }

    override fun onCleared() = close()
}
