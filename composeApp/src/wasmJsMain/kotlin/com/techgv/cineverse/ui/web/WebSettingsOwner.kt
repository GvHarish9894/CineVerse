package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.settings.HapticStrength
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.presentation.SettingsStateHolder
import com.techgv.cineverse.presentation.Toggle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Observed preferences only; null is unknown, never a fabricated Settings default. */
data class WebSettingsState(
    val settings: Settings? = null,
    val readFailure: StorageException? = null,
    val writeFailure: StorageException? = null,
    val pendingWrites: Int = 0,
    val holderSettings: Settings? = null,
)

/** Route-local readers/writes; the injected runtime holder remains owned by the application. */
class WebSettingsOwner(
    private val repository: SettingsRepository,
    private val holder: SettingsStateHolder,
    parentScope: CoroutineScope,
    private val session: StateFlow<WebSessionState>,
    private val retryUpstreamStorage: () -> Unit,
    private val onSignIn: () -> Unit,
    private val onSignOut: (AuthSession) -> Unit,
    private val onImport: (AuthSession) -> Unit,
) : AutoCloseable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val reads = MutableStateFlow(0L)
    private val mutableState = MutableStateFlow(WebSettingsState())
    val state: StateFlow<WebSettingsState> = mutableState.asStateFlow()
    private var closed = false

    init {
        scope.launch {
            reads.collectLatest {
                repository.settings.catch { failure ->
                    if (failure !is StorageException) throw failure
                    mutableState.update { it.copy(readFailure = failure) }
                }.collect { settings ->
                    mutableState.update { it.copy(settings = settings, readFailure = null) }
                }
            }
        }
        scope.launch {
            holder.state.collect { observed ->
                mutableState.update { it.copy(holderSettings = observed?.settings) }
            }
        }
    }

    /** Reaches all readers even for equal recovered values; never replays an edit. */
    fun retryStorage() {
        if (closed) return
        retryUpstreamStorage()
        holder.retryStorage()
        reads.update { it + 1 }
    }

    fun setToggle(toggle: Toggle, checked: Boolean) = edit {
        when (toggle) {
            Toggle.Glow -> it.copy(glow = checked)
            Toggle.Motion -> it.copy(motion = checked)
            Toggle.Haptics -> it.copy(haptics = checked)
            Toggle.Sounds -> it.copy(sounds = checked)
            Toggle.Adult -> it.copy(includeAdult = checked)
        }
    }

    fun selectLanguage(language: String) {
        require(language in Settings.LANGUAGES)
        edit { it.copy(language = language) }
    }

    fun selectRegion(region: String) {
        require(region in Settings.REGIONS)
        edit { it.copy(region = region) }
    }

    fun selectStrength(strength: HapticStrength) = edit { it.copy(strength = strength) }

    private fun edit(transform: (Settings) -> Settings) {
        val observed = state.value
        if (closed || observed.settings == null || observed.readFailure != null) return
        mutableState.update { it.copy(pendingWrites = it.pendingWrites + 1) }
        scope.launch {
            try {
                // The repository transforms the transaction's latest values, not the rendered snapshot.
                repository.update(transform)
                ensureActive()
            } catch (failure: StorageException) {
                ensureActive()
                mutableState.update { it.copy(writeFailure = failure) }
            } finally {
                if (!closed) mutableState.update { it.copy(pendingWrites = it.pendingWrites - 1) }
            }
        }
    }

    fun dismissWriteFailure() {
        if (!closed) mutableState.update { it.copy(writeFailure = null) }
    }

    fun signIn() {
        if (!closed && session.value == WebSessionState.Ready(null)) onSignIn()
    }

    fun signOut() = withAccount(onSignOut)
    fun importLibrary() = withAccount(onImport)

    private fun withAccount(action: (AuthSession) -> Unit) {
        if (closed) return
        (session.value as? WebSessionState.Ready)?.session?.let(action)
    }

    override fun close() {
        closed = true
        scope.cancel()
    }
}
