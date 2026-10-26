@file:OptIn(io.ktor.utils.io.InternalAPI::class)

package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.library.ImportOfferRecord
import com.techgv.cineverse.data.library.ImportOfferStore
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.web.WebApprovalAttempt
import com.techgv.cineverse.data.web.WebProxyHttp
import com.techgv.cineverse.di.*
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

/** Owned fake engine/stores/opener only. No production opener, cookie access or real HTTP fallback. */
internal class WebApprovalFixture {
    companion object {
        private var next = 0
        const val GUEST = """{"signedIn":false,"accountObjectId":null,"displayName":null,"sessionGeneration":"0"}"""
        const val SIGNED = """{"signedIn":true,"accountObjectId":"fixture-account","displayName":"Fixture viewer","sessionGeneration":"-9223372036854775808"}"""
        const val APPROVAL = """{"approveUrl":"https://www.themoviedb.org/auth/access?request_token=isolated-fixture","attemptId":"server-fixture"}"""
    }
    var sessionBody = GUEST
    var sessionStatus = HttpStatusCode.OK
    var startBody = APPROVAL
    var startStatus = HttpStatusCode.OK
    var cancelStatus = HttpStatusCode.NoContent
    var beforeReply: suspend (String) -> Unit = {}
    var throwOnOpen = false
    val opened = mutableListOf<WebApprovalAttempt>()
    val calls = mutableListOf<Pair<HttpMethod, String>>()
    val unexpected = mutableListOf<String>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    fun gate() = CompletableDeferred<Unit>().also { gates += it }
    fun count(path: String) = calls.count { it.second == path }
    val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val id = "cineverse.g7.approval.test.${++next}"
    private val client = HttpClient(object : HttpClientEngineBase("g7-approval-fixture") {
        override val config = HttpClientEngineConfig().apply { dispatcher = Dispatchers.Unconfined }
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val path = data.url.encodedPath
            calls += data.method to path
            val allowed = when (path) {
                "/api/session" -> data.method == HttpMethod.Get
                "/api/auth/start", "/api/auth/cancel" -> data.method == HttpMethod.Post && data.headers["X-CV-CSRF"] == "g7-fixture-csrf"
                else -> false
            }
            if (!allowed) {
                unexpected += "Unexpected fake request"
                error("Unexpected fake request")
            }
            val (body, status) = when (path) {
                "/api/session" -> sessionBody to sessionStatus
                "/api/auth/start" -> startBody to startStatus
                else -> "" to cancelStatus
            }
            beforeReply(path)
            return HttpResponseData(status, GMTDate(), headersOf(HttpHeaders.ContentType, "application/json"),
                HttpProtocolVersion.HTTP_1_1, ByteReadChannel(body), callContext())
        }
    })
    private val values = object : SettingsValues {
        override fun string(key: String): String? = null
        override fun boolean(key: String): Boolean? = null
        override fun stringSet(key: String): Set<String>? = null
    }
    private val app = koinApplication { modules(sharedModules(WebConfig("/api")) + module {
        single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
        single { BrowserDatabase(id) } // Lazy/unopened: preferences and import offers below are fake-only.
        single { BrowserChanges("$id.bus") }
        single<HttpClient> { client }
        single { WebProxyHttp(client) { "g7-fixture-csrf" } }
        single<SettingsStore> { object : SettingsStore {
            override val data: Flow<SettingsValues> = MutableStateFlow(values)
            override suspend fun edit(transform: (MutableSettingsValues) -> Unit) { error("No settings writes in G7") }
        } }
        single { SettingsRepository(get<SettingsStore>()) }
        single<ImportOfferStore> { object : ImportOfferStore {
            override suspend fun offer(accountObjectId: String) = ImportOfferRecord(accountObjectId, imported = false, complete = true)
            override suspend fun put(offer: ImportOfferRecord) { error("No import writes in G7") }
        } }
    }) }
    val runtime = app.koin.get<WebRuntime>()
    val owner = WebApprovalOwner(runtime, WebApprovalOpener {
        opened += it
        if (throwOnOpen) error("Fake opener failure")
    }, ownerScope)

    suspend fun start() {
        runtime.start()
        runtime.session.first { it !is WebSessionState.Checking }
    }
    suspend fun settled(phase: WebApprovalPhase) = owner.state.first {
        it.phase == phase && (phase == WebApprovalPhase.Closed || it.canCheck)
    }
    suspend fun refresh() {
        runtime.revalidateSession()
        runtime.session.first { it !is WebSessionState.Checking }
    }
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
