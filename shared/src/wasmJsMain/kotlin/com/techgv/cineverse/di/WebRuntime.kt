package com.techgv.cineverse.di

import com.techgv.cineverse.data.auth.AccountDefaultsMetadata
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.LibraryImporter
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.library.LibraryStateSnapshot
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.web.WebApprovalAttempt
import com.techgv.cineverse.data.web.WebProxyException
import com.techgv.cineverse.data.web.WebProxyHttp
import com.techgv.cineverse.data.web.WebWriteOutcome
import com.techgv.cineverse.data.web.decodeWebSession
import com.techgv.cineverse.presentation.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerializationException

/** Unknown/failed verification is distinct from a verified guest. The old identity is never replaced by a hint. */
sealed interface WebSessionState {
    data object Checking : WebSessionState
    data class Ready(val session: AuthSession?) : WebSessionState
    data class Failed(val error: TmdbError) : WebSessionState
}

/** Browser composition-root lifetime. No Koin lookup in observers, repositories or holders. */
@OptIn(ExperimentalCoroutinesApi::class)
class WebRuntime internal constructor(
    private val scope: CoroutineScope,
    private val http: HttpClient,
    private val database: BrowserDatabase,
    private val changes: BrowserChanges,
    private val browserAuth: BrowserAuthRepository,
    private val catalog: CatalogRepository,
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
    importer: LibraryImporter,
) {
    private val auth = browserAuth.web
    private val sessionState = MutableStateFlow<WebSessionState>(WebSessionState.Checking)
    val session: StateFlow<WebSessionState> = sessionState.asStateFlow()
    private val storageIssue = MutableStateFlow<BrowserStorageFailure?>(null)
    val storageFailure: StateFlow<BrowserStorageFailure?> = storageIssue.asStateFlow()
    private val settingsReads = MutableStateFlow(0L)
    private var started = false
    private var closed = false
    private var syncing: Job? = null
    private var softReading: Job? = null
    private var syncRevision = 0L
    /** Read-only (GET) proxy for soft session reads: no CSRF, no auth repository revision or state. */
    private val sessionReads = WebProxyHttp(http) { null }
    private val completedImports = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val importsCompleted: Flow<Unit> = completedImports.asSharedFlow()
    private val cacheInvalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Every TmdbCache generation bump this runtime performs plus session/storage changes: a re-read hint, never identity. */
    val contextInvalidations: Flow<Unit> = merge(cacheInvalidations, session.drop(1).map { }, storageFailure.drop(1).map { })
    val settingsHolder = SettingsStateHolder(settings, browserAuth, scope, ::reportStorageFailure)
    val importController = ImportLibraryStateHolder(importer, scope,
        onImported = { completedImports.tryEmit(Unit) }, onStorageFailure = ::reportStorageFailure)

    /** Future shells supply their existing route saver and scoped holder lifetime, not another DI scope. */
    fun libraryHolder(saver: StateSaver, ownerScope: CoroutineScope) =
        LibraryStateHolder(library, saver, ownerScope, ::reportStorageFailure)

    fun libraryActions(uiScope: CoroutineScope, onOutcome: (LibraryWriteOutcome) -> Unit) =
        LibraryActionsStateHolder(library, scope, uiScope, onOutcome, ::reportStorageFailure)

    /** Detail's origin-tagged account state: the snapshot names the identity it was read under. */
    fun libraryStateWithOrigin(ref: MediaRef): Flow<LibraryStateSnapshot> = library.stateWithOrigin(ref)

    fun reportStorageFailure(failure: StorageException) {
        storageIssue.value = (failure as? BrowserStorageException)?.failure ?: BrowserStorageFailure.Unavailable
    }

    /** Called exactly once by the Wasm entry point; safe against duplicate host startup. */
    fun start() {
        check(!closed) { "Browser runtime is closed" }
        if (started) return
        started = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            settingsReads.flatMapLatest {
                settings.settings.map { Triple(it.language, it.region, it.includeAdult) }
                    .distinctUntilChanged().catch { failure ->
                        if (failure is StorageException) reportStorageFailure(failure) else throw failure
                    }
            }.distinctUntilChanged().drop(1).collect {
                catalog.onContentSettingsChanged()
                invalidateLibrary()
                cacheInvalidations.tryEmit(Unit)
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previous = changes.changes.value
            changes.changes.drop(1).collect { revision ->
                if (revision.session != previous.session || revision.revalidate != previous.revalidate) softRevalidate()
                if (revision.library != previous.library) invalidateLibrary()
                previous = revision
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            auth.session.drop(1).collect { identity ->
                if (sessionState.value != WebSessionState.Checking && auth.isVerifiedSession(identity)) {
                    catalog.onAccountChanged()
                    invalidateLibrary()
                    cacheInvalidations.tryEmit(Unit)
                    sessionState.value = WebSessionState.Ready(identity)
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessionState.map { (it as? WebSessionState.Ready)?.session }.distinctUntilChanged().collectLatest {
                importController.onAccount(it)
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            auth.sessionExpired.collect {
                catalog.onAccountChanged()
                invalidateLibrary()
                cacheInvalidations.tryEmit(Unit)
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            browserAuth.verification.collect {
                if (sessionState.value != WebSessionState.Checking) revalidateSession()
            }
        }
        revalidateSession()
    }

    private fun invalidateLibrary() {
        library.invalidateAccountLists()
        auth.session.value?.let(library::onExternalWrites)
    }

    /**
     * Focus/visibility/peer-session hints: a background read that keeps the current state and invalidates nothing.
     * Only a verified identity that differs from the rendered Ready session takes the hard path. Latest hint wins.
     */
    private fun softRevalidate() {
        if (closed || sessionState.value == WebSessionState.Checking) return
        softReading?.cancel()
        val ticket = syncRevision
        softReading = scope.launch {
            // A failed hint read keeps the current state: it is not evidence against a verified identity.
            val changed = try { readsOtherSession() } catch (_: TmdbException) { false }
            if (changed && !closed && ticket == syncRevision) revalidateSession()
        }
    }

    /** True unless the server's identity equals the rendered Ready session that auth still verifies. Changes no state. */
    private suspend fun readsOtherSession(): Boolean {
        val identity = try { decodeWebSession(sessionReads.call("/api/session")) }
        catch (_: SerializationException) { throw WebProxyException("malformed_session", null, null, WebWriteOutcome.NotApplicable, TmdbError.Malformed) }
        val ready = sessionState.value as? WebSessionState.Ready
        return ready == null || ready.session != identity || !auth.isVerifiedSession(identity)
    }

    /** Hard verification (boot, explicit retry, recoveries, identity change): revoke tickets BEFORE the authoritative read. */
    fun revalidateSession() {
        if (closed) return
        softReading?.cancel()
        auth.invalidateSession()
        catalog.onAccountChanged()
        invalidateLibrary()
        cacheInvalidations.tryEmit(Unit)
        importController.later()
        sessionState.value = WebSessionState.Checking
        syncing?.cancel()
        val ticket = ++syncRevision
        syncing = scope.launch {
            try {
                val identity = auth.revalidate() // Also bootstraps the readable CSRF cookie.
                if (closed || ticket != syncRevision) return@launch
                sessionState.value = if (auth.isVerifiedSession(identity)) WebSessionState.Ready(identity)
                    else WebSessionState.Failed(TmdbError.Http(409, null))
                if (sessionState.value is WebSessionState.Ready) invalidateLibrary()
            } catch (failure: TmdbException) {
                if (!closed && ticket == syncRevision) sessionState.value = WebSessionState.Failed(failure.error)
            }
        }
    }

    /** Read only after capturing Ready.session; failures do not undo a committed sign-in or apply preferences. */
    suspend fun accountDefaults(expected: AuthSession): AccountDefaultsMetadata = try { auth.accountDefaults(expected) }
    catch (failure: WebProxyException) {
        if (failure.code in setOf("stale_session", "session_expired", "not_signed_in")) browserAuth.requestVerification()
        throw failure
    }

    suspend fun startApproval(): WebApprovalAttempt = auth.startApproval()

    /**
     * Browser UI uses this explicit controller, never native RequestCode/AccountDto defaults. A soft read; only a
     * changed identity runs (and awaits) the hard transition. Read failures propagate; the state is kept.
     */
    suspend fun approvalReturned() {
        try {
            if (readsOtherSession() && !closed) {
                revalidateSession()
                syncing?.join()
            }
        } finally { changes.publish(BrowserChangeTopic.Session) }
    }

    suspend fun cancelApproval() {
        try { auth.cancelApproval() }
        catch (failure: TmdbException) {
            revalidateSession() // An unconfirmed cancel/read must not leave the old verified guest rendered.
            throw failure
        } finally { changes.publish(BrowserChangeTopic.Session) }
    }

    /** Re-subscribe failed settings reads. Failed mutations require an explicit consumer action, never replay here. */
    fun retryStorage() {
        if (closed) return
        scope.launch {
            try {
                settings.current()
                storageIssue.value = null
                settingsReads.update { it + 1 }
                settingsHolder.retryStorage()
            } catch (failure: StorageException) { reportStorageFailure(failure) }
        }
    }

    /** Host teardown: cancel collectors/requests first; resources close only after all app children have stopped. */
    fun stop() {
        if (closed) return
        closed = true
        syncRevision++
        val job = scope.coroutineContext.job
        job.invokeOnCompletion {
            changes.close()
            database.close()
            http.close()
        }
        job.cancel()
    }

    /** Awaited teardown for tests/explicit hosts. Call outside the graph-owned app scope. */
    suspend fun close() {
        stop()
        scope.coroutineContext.job.join()
    }
}
