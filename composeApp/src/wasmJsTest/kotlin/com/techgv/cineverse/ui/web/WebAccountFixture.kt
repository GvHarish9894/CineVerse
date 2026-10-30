@file:OptIn(io.ktor.utils.io.InternalAPI::class)

package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.settings.MutableSettingsValues
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.settings.SettingsStore
import com.techgv.cineverse.data.settings.SettingsValues
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.web.WebProxyHttp
import com.techgv.cineverse.di.*
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.assertTrue

/** Owned fake engine, in-memory guest/import stores and fake CSRF only. No real HTTP, cookies, IndexedDB or locks. */
internal class WebAccountFixture(ownerDispatcher: CoroutineDispatcher = Dispatchers.Unconfined) {
    companion object {
        private var next = 0
        const val GUEST = """{"signedIn":false,"accountObjectId":null,"displayName":null,"sessionGeneration":"0"}"""
        const val SIGNED_A = """{"signedIn":true,"accountObjectId":"fixture-account-a","displayName":"Fixture viewer","sessionGeneration":"-9223372036854775808"}"""
        const val SIGNED_A_NEXT = """{"signedIn":true,"accountObjectId":"fixture-account-a","displayName":"Fixture viewer","sessionGeneration":"7"}"""
        const val SIGNED_B = """{"signedIn":true,"accountObjectId":"fixture-account-b","displayName":"Other viewer","sessionGeneration":"9"}"""
        val A = AuthSession(AccountObjectId("fixture-account-a"), "Fixture viewer", Long.MIN_VALUE)
        val A_NEXT = AuthSession(AccountObjectId("fixture-account-a"), "Fixture viewer", 7)
        val REF = MediaRef(MediaType.Movie, 7)
        private const val EMPTY_PAGE = """{"page":1,"results":[],"total_pages":1,"total_results":0}"""
    }
    var sessionBody = SIGNED_A
    var sessionStatus = HttpStatusCode.OK
    var logoutStatus = HttpStatusCode.NoContent
    /** Whether the fake server cleared the session cookie, independent of the reported status. */
    var logoutLands = true
    var writeStatus = HttpStatusCode.OK
    var watchlistOnTmdb = false
    var failOfferWrites = false
    var beforeReply: suspend (HttpMethod, String) -> Unit = { _, _ -> }
    val calls = mutableListOf<Pair<HttpMethod, String>>()
    val unexpected = mutableListOf<String>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    fun gate() = CompletableDeferred<Unit>().also { gates += it }
    fun count(path: String) = calls.count { it.second == path }
    val ownerScope = CoroutineScope(SupervisorJob() + ownerDispatcher)
    private val id = "cineverse.g11.account.test.${++next}"

    val guestRows = mutableListOf(GuestLibraryRow(REF, LibraryKind.WATCHLIST, null, 1, """{"title":"Fixture film"}"""))
    val offers = mutableMapOf<String, ImportOfferRecord>()
    val ledger = mutableMapOf<Triple<String, MediaRef, LibraryKind>, ImportStepState>()

    private val client = HttpClient(object : HttpClientEngineBase("g11-account-fixture") {
        override val config = HttpClientEngineConfig().apply { dispatcher = Dispatchers.Unconfined }
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val path = data.url.encodedPath
            val method = data.method
            calls += method to path
            val csrf = data.headers["X-CV-CSRF"] == "g11-fixture-csrf"
            val reply: Pair<String, HttpStatusCode> = when {
                path == "/api/session" && method == HttpMethod.Get -> sessionBody to sessionStatus
                path == "/api/auth/logout" && method == HttpMethod.Post && csrf -> {
                    if (logoutLands) sessionBody = GUEST
                    (if (logoutStatus.isSuccess()) "" else """{"error":"upstream_error"}""") to logoutStatus
                }
                path.startsWith("/api/account/states/") && method == HttpMethod.Get ->
                    """{"watchlist":$watchlistOnTmdb,"favorite":false,"rated":false}""" to HttpStatusCode.OK
                path == "/api/account/watchlist" && method == HttpMethod.Post && csrf -> {
                    if (writeStatus.isSuccess()) watchlistOnTmdb = true
                    (if (writeStatus.isSuccess()) """{"status_code":1,"success":true}""" else """{"error":"upstream_error"}""") to writeStatus
                }
                path.startsWith("/api/account/") && method == HttpMethod.Get -> EMPTY_PAGE to HttpStatusCode.OK
                else -> {
                    unexpected += "Unexpected fake request"
                    error("Unexpected fake request")
                }
            }
            beforeReply(method, path)
            return HttpResponseData(reply.second, GMTDate(), headersOf(HttpHeaders.ContentType, "application/json"),
                HttpProtocolVersion.HTTP_1_1, ByteReadChannel(reply.first), callContext())
        }
    })
    private val values = object : SettingsValues {
        override fun string(key: String): String? = null
        override fun boolean(key: String): Boolean? = null
        override fun stringSet(key: String): Set<String>? = null
    }
    private val app = koinApplication { modules(sharedModules(WebConfig("/api")) + module {
        single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
        single { BrowserDatabase(id) } // Lazy/unopened: every store below is in-memory.
        single { BrowserChanges("$id.bus") }
        single<HttpClient> { client }
        single { WebProxyHttp(client) { "g11-fixture-csrf" } }
        single<SettingsStore> { object : SettingsStore {
            override val data: Flow<SettingsValues> = MutableStateFlow(values)
            override suspend fun edit(transform: (MutableSettingsValues) -> Unit) { error("No settings writes in G11") }
        } }
        single { SettingsRepository(get<SettingsStore>()) }
        single<ImportOperationGuard> { ImportOperationGuard { it() } }
        single<GuestLibraryStore> { object : GuestLibraryStore {
            override fun observeKind(kind: LibraryKind, media: MediaType) =
                flowOf(guestRows.filter { it.kind == kind && it.ref.mediaType == media })
            override fun observeTitle(ref: MediaRef) = flowOf(guestRows.filter { it.ref == ref })
            override suspend fun all() = guestRows.toList()
            override suspend fun upsert(row: GuestLibraryRow) { error("No guest writes in G11") }
            override suspend fun delete(ref: MediaRef, kind: LibraryKind) { error("No guest writes in G11") }
        } }
        single<ImportLedgerStore> { object : ImportLedgerStore {
            override suspend fun state(accountObjectId: String, ref: MediaRef, kind: LibraryKind) = ledger[Triple(accountObjectId, ref, kind)]
            override suspend fun put(accountObjectId: String, ref: MediaRef, kind: LibraryKind, state: ImportStepState) {
                ledger[Triple(accountObjectId, ref, kind)] = state
            }
        } }
        single<ImportOfferStore> { object : ImportOfferStore {
            override suspend fun offer(accountObjectId: String) = offers[accountObjectId]
            override suspend fun put(offer: ImportOfferRecord) {
                if (failOfferWrites) throw StorageException("Fake offer write failure")
                offers[offer.accountObjectId] = offer
            }
        } }
    }) }
    val runtime = app.koin.get<WebRuntime>()
    val owner = WebAccountOwner(runtime, ownerScope)

    suspend fun start() {
        runtime.start()
        runtime.session.first { it !is WebSessionState.Checking }
    }
    suspend fun refresh() {
        runtime.revalidateSession()
        runtime.session.first { it !is WebSessionState.Checking }
    }
    suspend fun signOutSettled(phase: WebSignOutPhase) = owner.state.first { it.signOut == phase }
    suspend fun prompt(match: (WebAccountState) -> Boolean) = owner.state.first { match(it) }
    fun dispose() {
        gates.forEach { it.complete(Unit) }
        owner.close()
        ownerScope.cancel()
        runtime.stop()
        app.close()
        assertTrue(unexpected.isEmpty(), "Fake engine saw an unexpected request; no network fallback exists")
    }
    suspend fun close() {
        dispose()
        runtime.close()
    }
}
