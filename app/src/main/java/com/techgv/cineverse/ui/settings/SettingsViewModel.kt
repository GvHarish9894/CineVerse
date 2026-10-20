package com.techgv.cineverse.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.techgv.cineverse.AppContainer
import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.settings.AccountDefaults
import com.techgv.cineverse.data.settings.HapticStrength
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.ui.components.FocusMemory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [accountDefaults] is the pending offer of the signed-in account only. */
data class SettingsUiState(
    val settings: Settings,
    val accountName: String?,
    val accountDefaults: AccountDefaults?,
)

enum class Toggle { Haptics, Sounds, Glow, Motion, Adult }
enum class Choice { Strength, Language, Region }

class SettingsViewModel(
    handle: SavedStateHandle,
    private val settings: SettingsRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    val focusMemory = FocusMemory(handle)

    /** Null until the first DataStore read: the screen restores focus only once real values show. */
    val state: StateFlow<SettingsUiState?> =
        combine(settings.settings, auth.credentials, settings.accountDefaults) { s, credentials, defaults ->
            SettingsUiState(
                settings = s,
                accountName = credentials?.displayName,
                accountDefaults = defaults?.takeIf { it.accountObjectId == credentials?.accountObjectId?.value },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggle(toggle: Toggle) = edit {
        when (toggle) {
            Toggle.Haptics -> it.copy(haptics = !it.haptics)
            Toggle.Sounds -> it.copy(sounds = !it.sounds)
            Toggle.Glow -> it.copy(glow = !it.glow)
            Toggle.Motion -> it.copy(motion = !it.motion)
            Toggle.Adult -> it.copy(includeAdult = !it.includeAdult)
        }
    }

    /** ←→ (or OK = +1). Language / region changes invalidate the TMDB cache via AppContainer (§3.4). */
    fun cycle(choice: Choice, delta: Int) = edit {
        when (choice) {
            Choice.Strength -> it.copy(strength = HapticStrength.entries.cycle(it.strength, delta))
            Choice.Language -> it.copy(language = Settings.LANGUAGES.cycle(it.language, delta))
            Choice.Region -> it.copy(region = Settings.REGIONS.cycle(it.region, delta))
        }
    }

    fun resolveAccountDefaults(apply: Boolean) {
        viewModelScope.launch { settings.resolveAccountDefaults(apply) }
    }

    /** §4.2: local sign-out first (non-cancellable in the repository), remote revocation best-effort. */
    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }

    private fun edit(transform: (Settings) -> Settings) {
        viewModelScope.launch { settings.update(transform) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SettingsViewModel(createSavedStateHandle(), container.settingsRepository, container.authRepository) }
        }
    }
}
