package com.techgv.cineverse.di

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.LibraryImporter
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.web.WebApprovalAttempt
import com.techgv.cineverse.presentation.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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
    private val completedImports = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val importsCompleted: Flow<Unit> = completedImports.asSharedFlow()
    val settingsHolder = SettingsStateHolder(settings, browserAuth, scope, ::reportStorageFailure)
    val importController = ImportLibraryStateHolder(importer, scope,
        onImported = { completedImports.tryEmit(Unit) }, onStorageFailure = ::reportStorageFailure)

    /** Future shells supply their existing route saver and scoped holder lifetime, not another DI scope. */
    fun libraryHolder(saver: StateSaver) =
        LibraryStateHolder(library, saver, scope, ::reportStorageFailure)

    fun libraryActions(onOutcome: (LibraryWriteOutcome) -> Unit) =
        LibraryActionsStateHolder(library, scope, scope, onOutcome, ::reportStorageFailure)

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
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previous = changes.changes.value
            changes.changes.drop(1).collect { revision ->
                if (revision.session != previous.session || revision.revalidate != previous.revalidate) revalidateSession()
                if (revision.library != previous.library) invalidateLibrary()
                previous = revision
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            auth.session.drop(1).collect { identity ->
                catalog.onAccountChanged()
                invalidateLibrary()
                sessionState.value = WebSessionState.Ready(identity)
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

    /** Focus/visibility/session messages are hints: revoke tickets BEFORE dispatching authoritative verification. */
    fun revalidateSession() {
        if (closed) return
        auth.invalidateSession()
        catalog.onAccountChanged()
        invalidateLibrary()
        importController.later()
        sessionState.value = WebSessionState.Checking
        syncing?.cancel()
        syncing = scope.launch {
            try {
                val identity = auth.revalidate() // Also bootstraps the readable CSRF cookie.
                sessionState.value = WebSessionState.Ready(identity)
                invalidateLibrary()
            } catch (failure: TmdbException) {
                sessionState.value = WebSessionState.Failed(failure.error)
            }
        }
    }

    suspend fun startApproval(): WebApprovalAttempt = auth.startApproval()

    /** Browser UI uses this explicit controller, never native RequestCode/AccountDto defaults. */
    suspend fun approvalReturned() {
        try { auth.onApprovalReturned() }
        finally { changes.publish(BrowserChangeTopic.Session) }
    }

    suspend fun cancelApproval() {
        try { auth.cancelApproval() }
        finally { changes.publish(BrowserChangeTopic.Session) }
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
