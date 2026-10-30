package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.auth.NativeApprovalCoordinator
import com.techgv.cineverse.data.library.LibraryImporter
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.search.RecentQueryStore
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** A received null session is guest; no emission is still unknown. */
data class MobileIdentity(val session: AuthSession?)

/** One native graph, independent of activities/controllers, rotations and screen compositions. */
class MobileApplication(
    val catalog: CatalogRepository,
    val generation: GenerationCounter,
    private val settingsRepository: SettingsRepository,
    private val auth: AuthRepository,
    private val library: LibraryRepository,
    val recentQueries: RecentQueryStore,
    appScope: CoroutineScope,
    approval: NativeApprovalCoordinator,
    importer: LibraryImporter,
    /** The account owner and import holder keep unsynchronized state, so production confines them to the UI thread. */
    accountContext: CoroutineContext = EmptyCoroutineContext,
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
    private val mutableContextChanges = MutableStateFlow(0L)
    internal val contextChanges = mutableContextChanges.asStateFlow()
    val actions = LibraryActionsStateHolder(library, scope, scope,
        onOutcome = { mutableOutcomes.tryEmit(it) },
        onStorageFailure = { mutableStorageFailed.value = true; invalidateDetailContext() },
    )
    val account = MobileAccountOwner(this, approval, importer, settingsRepository, auth, CoroutineScope(scope.coroutineContext + accountContext))
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

    /** The entry owns validation; this cold reader never constructs a ref or reads storage for invalid routes. */
    internal fun detail(media: String, id: Int, savedState: SavedStateHandle, parentScope: CoroutineScope? = null) =
        MobileDetailViewModel(this, media, id, savedState, settingsFlow, flow {
            val type = when (media) {
                "movie" -> MediaType.Movie
                "tv" -> MediaType.Tv
                else -> return@flow
            }
            if (id <= 0) return@flow
            emitAll(library.stateWithOrigin(MediaRef(type, id)))
        }, parentScope)

    internal fun person(id: Int, savedState: SavedStateHandle, parentScope: CoroutineScope? = null) =
        MobilePersonViewModel(this, id, savedState, settingsFlow, parentScope)

    internal fun season(seriesId: Int, number: Int, savedState: SavedStateHandle, parentScope: CoroutineScope? = null) =
        MobileSeasonViewModel(this, seriesId, number, savedState, settingsFlow, parentScope)

    /** Trailer route: library actions and the import prompt both defer while it is current. */
    fun setPlayerActive(active: Boolean) {
        actions.setPlayerActive(active)
        account.setPlayerActive(active)
    }

    private fun invalidateDetailContext() {
        actions.closeRate()
        // GenerationCounter is a snapshot, so notify roots at the actual graph invalidation boundary.
        mutableContextChanges.value++
    }

    fun retrySettings() {
        if (settingsJob?.isActive == true) return
        val recovering = mutableSettingsFailed.value
        mutableSettingsFailed.value = false
        if (recovering) invalidateDetailContext()
        settingsJob = scope.launch {
            settingsFlow.distinctUntilChanged().catch {
                if (it !is StorageException) throw it
                mutableSettingsFailed.value = true // Keep the last good value, never invent persisted preferences.
                invalidateDetailContext()
            }.collect { next ->
                val previous = mutableSettings.value
                val contentChanged = previous != null && Triple(previous.language, previous.region, previous.includeAdult) !=
                    Triple(next.language, next.region, next.includeAdult)
                if (contentChanged) catalog.onContentSettingsChanged()
                // Invalidate before publishing to screen holders: their reloads use the new app generation.
                mutableSettings.value = next
                if (previous == null || contentChanged) invalidateDetailContext()
            }
        }
    }

    fun retryIdentity() {
        if (identityJob?.isActive == true) return
        val recovering = mutableIdentityFailed.value
        mutableIdentityFailed.value = false
        if (recovering) invalidateDetailContext()
        identityJob = scope.launch {
            auth.session.catch {
                actions.closeRate() // Revoke queued requests without manufacturing guest or changing auth.
                mutableIdentity.value = null
                mutableIdentityFailed.value = true
                invalidateDetailContext()
            }.collect {
                val previous = mutableIdentity.value
                actions.onIdentity(it)
                mutableIdentity.value = MobileIdentity(it)
                if (previous != mutableIdentity.value) invalidateDetailContext()
            }
        }
    }

    fun retryLibraryStorage() {
        val recovering = mutableStorageFailed.value
        mutableStorageFailed.value = false
        actions.retryStorage()
        if (recovering) invalidateDetailContext()
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
