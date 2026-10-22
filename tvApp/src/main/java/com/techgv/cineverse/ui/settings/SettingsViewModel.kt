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
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.presentation.Choice
import com.techgv.cineverse.presentation.SettingsStateHolder
import com.techgv.cineverse.presentation.SettingsUiState
import com.techgv.cineverse.presentation.Toggle
import com.techgv.cineverse.ui.components.FocusMemory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** TV adapter over [SettingsStateHolder] (§7.9): focus memory. */
class SettingsViewModel(
    handle: SavedStateHandle,
    settings: SettingsRepository,
    auth: AuthRepository,
) : ViewModel() {
    val focusMemory = FocusMemory(handle)
    private val holder = SettingsStateHolder(settings, auth, viewModelScope)

    /** Null until the first DataStore read: the screen restores focus only once real values show. */
    val state: StateFlow<SettingsUiState?> = holder.state

    /** true once the local sign-out is done (remote revocation may still run), false if it failed. */
    val signOutResults: Flow<Boolean> = holder.signOutResults

    fun toggle(toggle: Toggle) = holder.toggle(toggle)

    /** ←→ (or OK = +1). Language / region changes invalidate the TMDB cache via AppContainer (§3.4). */
    fun cycle(choice: Choice, delta: Int) = holder.cycle(choice, delta)

    /** Resolves [offer] only while its account is still the signed-in one (a stale screen's click does nothing). */
    fun resolveAccountDefaults(offer: AccountDefaults, apply: Boolean) = holder.resolveAccountDefaults(offer, apply)

    /** §4.2: local sign-out first, remote revocation best-effort. */
    fun signOut() = holder.signOut()

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SettingsViewModel(createSavedStateHandle(), container.settingsRepository, container.authRepository) }
        }
    }
}
