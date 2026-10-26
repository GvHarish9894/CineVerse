package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.auth.*
import com.techgv.cineverse.data.settings.*
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.presentation.SettingsStateHolder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Isolated memory only; no browser storage, HTTP, auth, or import operations. */
internal class WebSettingsStoreFake : SettingsStore {
    private val revision = MutableStateFlow(0)
    private val mutex = Mutex()
    private var values = mapOf<String, Any>(
        "language" to "ta-IN", "region" to "GB", "motion" to true,
        "glow" to false, "haptics" to false, "include_adult" to true,
        "strength" to "Light", "sounds" to true,
        "search.recents" to setOf("fixture recent"),
        "account_defaults_pending" to "{}", "account_defaults_resolved" to setOf("fixture-account"),
        "unrelated" to "keep",
    )
    var readFailure: BrowserStorageFailure? = null
    var writeFailure: BrowserStorageFailure? = null
    var readGate: CompletableDeferred<Unit>? = null
    var writeGate: CompletableDeferred<Unit>? = null
    var subscriptions = 0
    var activeReaders = 0
    var writes = 0
    var cancelledWrites = 0
    fun snapshot(): Map<String, Any> = values.toMap()
    override val data: Flow<SettingsValues> = flow {
        subscriptions++
        activeReaders++
        try {
            readGate?.await()
            revision.collect {
                readFailure?.let { throw BrowserStorageException(it) }
                emit(Values(values))
            }
        } finally { activeReaders-- }
    }
    override suspend fun edit(transform: (MutableSettingsValues) -> Unit) {
        writes++
        try { writeGate?.await() }
        catch (canceled: CancellationException) { cancelledWrites++; throw canceled }
        mutex.withLock {
            writeFailure?.let { throw BrowserStorageException(it) }
            val next = Values(values)
            transform(next)
            values = next.map.toMap()
            revision.value++
        }
    }
    suspend fun externalChange(transform: (MutableSettingsValues) -> Unit) = mutex.withLock {
        val next = Values(values)
        transform(next)
        values = next.map.toMap()
        revision.value++
    }
    fun signalRead() { revision.value++ }
    private class Values(values: Map<String, Any>) : MutableSettingsValues {
        val map = values.toMutableMap()
        override fun string(key: String) = map[key] as? String
        override fun boolean(key: String) = map[key] as? Boolean
        override fun stringSet(key: String): Set<String>? = (map[key] as? Set<*>)?.map { it as String }?.toSet()
        override fun setString(key: String, value: String) { map[key] = value }
        override fun setBoolean(key: String, value: Boolean) { map[key] = value }
        override fun setStringSet(key: String, value: Set<String>) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }
}

internal class WebSettingsAuthFake : AuthRepository {
    override val session = MutableStateFlow<AuthSession?>(AuthSession(AccountObjectId("holder-hint"), "Not authoritative", 1))
    override val sessionExpired = emptyFlow<Unit>()
    override suspend fun createRequestCode(): RequestCode = error("Account protocol not owned here")
    override suspend fun awaitApproval(code: RequestCode): SignInOutcome = error("Account protocol not owned here")
    override suspend fun signOut(onSignedOutLocally: () -> Unit) = error("Use explicit host callback")
    override suspend fun expireSession(failed: AuthSession) = error("No live sessions")
}

internal class WebSettingsFixture(parent: CoroutineScope) : AutoCloseable {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    val store = WebSettingsStoreFake()
    val repository = SettingsRepository(store)
    val session = MutableStateFlow<WebSessionState>(WebSessionState.Checking)
    val failures = mutableListOf<StorageException>()
    val holder = SettingsStateHolder(repository, WebSettingsAuthFake(), scope) { failures += it }
    var upstreamRetries = 0
    var signIns = 0
    val signOuts = mutableListOf<AuthSession>()
    val imports = mutableListOf<AuthSession>()
    fun owner() = WebSettingsOwner(repository, holder, scope, session, { upstreamRetries++ },
        { signIns++ }, { signOuts += it }, { imports += it })
    val owner = owner()
    override fun close() { owner.close(); scope.cancel() }
}
