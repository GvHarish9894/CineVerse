package com.techgv.cineverse.ui.mobile

import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.search.RecentQueryStore
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.presentation.LibraryActionsStateHolder
import com.techgv.cineverse.presentation.LibraryWriteOutcome
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** A received null session is guest; no emission is still unknown. */
data class MobileIdentity(val session: AuthSession?)

/** One native graph, independent of activities/controllers, rotations and screen compositions. */
class MobileApplication(
    val catalog: CatalogRepository,
    val generation: GenerationCounter,
    private val settingsRepository: SettingsRepository,
    private val auth: AuthRepository,
    library: LibraryRepository,
    val recentQueries: RecentQueryStore,
    appScope: CoroutineScope,
) {
    private val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job]))
    val settingsFlow = settingsRepository.settings
    private val mutableSettings = MutableStateFlow<Settings?>(null)
    val settings = mutableSettings.asStateFlow()
    private val mutableSettingsFailed = MutableStateFlow(false)
    val settingsFailed = mutableSettingsFailed.asStateFlow()
    private val mutableIdentity = MutableStateFlow<MobileIdentity?>(null)
    val identity = mutableIdentity.asStateFlow()
    private val mutableIdentityFailed = MutableStateFlow(false)
    val identityFailed = mutableIdentityFailed.asStateFlow()
    private val mutableOutcomes = MutableSharedFlow<LibraryWriteOutcome>(extraBufferCapacity = 1)
    val outcomes = mutableOutcomes.asSharedFlow()
    private val mutableStorageFailed = MutableStateFlow(false)
    val storageFailed = mutableStorageFailed.asStateFlow()
    val actions = LibraryActionsStateHolder(library, scope, scope,
        onOutcome = { mutableOutcomes.tryEmit(it) },
        onStorageFailure = { mutableStorageFailed.value = true },
    )
    private var settingsJob: Job? = null
    private var identityJob: Job? = null

    init {
        retrySettings()
        retryIdentity()
        scope.launch {
            try { catalog.configuration() } catch (_: com.techgv.cineverse.data.tmdb.TmdbException) {
                // Image URL builder retains the reviewed constant fallback.
            }
        }
    }

    fun retrySettings() {
        if (settingsJob?.isActive == true) return
        mutableSettingsFailed.value = false
        settingsJob = scope.launch {
            settingsFlow.distinctUntilChanged().catch {
                if (it !is StorageException) throw it
                mutableSettingsFailed.value = true // Keep the last good value, never invent persisted preferences.
            }.collect { next ->
                val previous = mutableSettings.value
                if (previous != null && Triple(previous.language, previous.region, previous.includeAdult) !=
                    Triple(next.language, next.region, next.includeAdult)) catalog.onContentSettingsChanged()
                // Invalidate before publishing to screen holders: their reloads use the new app generation.
                mutableSettings.value = next
            }
        }
    }

    fun retryIdentity() {
        if (identityJob?.isActive == true) return
        mutableIdentityFailed.value = false
        identityJob = scope.launch {
            auth.session.catch {
                mutableIdentity.value = null
                mutableIdentityFailed.value = true
            }.collect {
                actions.onIdentity(it)
                mutableIdentity.value = MobileIdentity(it)
            }
        }
    }

    fun retryLibraryStorage() {
        mutableStorageFailed.value = false
        actions.retryStorage()
    }

    fun close() = scope.cancel()
}

/** Persisted names are unchanged; mobile uses the kit's 6/10/16 ms, not TV's 6/12/20. */
fun Settings.mobileAppearance() = AppearanceSettings(
    reduceMotion = motion,
    ambientGlow = glow,
    haptics = haptics,
    hapticStrength = com.techgv.cineverse.ui.haptics.HapticStrength.valueOf(strength.name),
)
