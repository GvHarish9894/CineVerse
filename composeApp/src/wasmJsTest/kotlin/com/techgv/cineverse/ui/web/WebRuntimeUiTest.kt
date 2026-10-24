@file:OptIn(io.ktor.utils.io.InternalAPI::class, androidx.compose.ui.test.ExperimentalTestApi::class)

package com.techgv.cineverse.ui.web

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.data.settings.*
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.di.*
import com.techgv.cineverse.util.GenerationCounter
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
import kotlin.test.*

/** Real existing graph/holders and fake remote transport/settings only; never a real sign-in or write. */
class WebRuntimeUiTest {
    private class Fixture : AutoCloseable {
        var failSession = true
        var failSettings = true
        var sessionReads = 0
        var catalogReads = 0
        var canceledReads = 0
        var gate: CompletableDeferred<Unit>? = null
        private val values = object : SettingsValues {
            override fun string(key: String): String? = null
            override fun boolean(key: String): Boolean? = if (key == "motion") true else null
            override fun stringSet(key: String): Set<String>? = null
        }
        val app = koinApplication { modules(sharedModules(WebConfig("/api")) + module {
            single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
            single<SettingsStore> { object : SettingsStore {
                override val data: Flow<SettingsValues> get() = flow {
                    if (failSettings) throw BrowserStorageException(BrowserStorageFailure.Blocked)
                    emit(values); awaitCancellation()
                }
                override suspend fun edit(transform: (MutableSettingsValues) -> Unit) { error("No writes allowed in root fixture") }
            } }
            single<HttpClient> { HttpClient(object : HttpClientEngineBase("web-root-fixture") {
                override val config = HttpClientEngineConfig().apply { dispatcher = Dispatchers.Unconfined }
                override suspend fun execute(data: HttpRequestData): HttpResponseData {
                    check(data.method == HttpMethod.Get) { "Root fixture forbids protected mutations" }
                    val session = data.url.encodedPath == "/api/session"
                    if (session) sessionReads++ else {
                        catalogReads++
                        try { gate?.await() } catch (canceled: CancellationException) { canceledReads++; throw canceled }
                    }
                    val body = when {
                        session && failSession -> """{"error":"upstream_error"}"""
                        session -> """{"signedIn":false,"accountObjectId":null,"displayName":null,"sessionGeneration":"0"}"""
                        data.url.encodedPath.contains("/genre/") -> """{"genres":[]}"""
                        else -> """{"page":1,"results":[],"total_pages":1,"total_results":0}"""
                    }
                    return HttpResponseData(if (session && failSession) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                        GMTDate(), headersOf(HttpHeaders.ContentType, "application/json"), HttpProtocolVersion.HTTP_1_1, ByteReadChannel(body), callContext())
                }
            }) }
        }) }
        val runtime = app.koin.get<WebRuntime>()
        val catalog = app.koin.get<CatalogRepository>()
        val generation = app.koin.get<GenerationCounter>()
        override fun close() { runtime.stop(); app.close() }
    }

    @Test fun rootAuthAndStorageFailuresRequireSeparateExplicitReadRetries() = runComposeUiTest {
        val f = Fixture()
        val history = FakeHistory()
        try {
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, history) }
            waitUntil(timeoutMillis = 10_000) { f.sessionReads > 0 && f.runtime.session.value is WebSessionState.Failed && f.runtime.storageFailure.value != null }
            onNodeWithText("Sign in").assertDoesNotExist()
            onNodeWithText("Saved settings are unavailable. Retry storage to continue.").assertExists()
            assertEquals(0, f.catalogReads)
            runOnIdle { f.failSession = false; f.runtime.revalidateSession() }
            waitUntil(timeoutMillis = 10_000) { f.runtime.session.value is WebSessionState.Ready }
            onNodeWithText("Sign in").assertExists()
            assertEquals(0, f.catalogReads)
            runOnIdle { f.failSettings = false }
            onNodeWithText("Retry").performClick()
            waitUntil(timeoutMillis = 10_000) { f.catalogReads >= 7 }
            onNodeWithText("Discover your next favourite").assertExists()
            assertNull(f.runtime.storageFailure.value)
            assertEquals(2, f.sessionReads)
        } finally { f.close() }
    }

    @Test fun leavingHomeCancelsRouteReadsAndLateResultsCannotNavigate() = runComposeUiTest {
        val f = Fixture()
        val history = FakeHistory()
        try {
            f.failSession = false; f.failSettings = false
            f.gate = CompletableDeferred()
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, history) }
            waitUntil(timeoutMillis = 10_000) { f.catalogReads >= 7 }
            onNodeWithText("People").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 10_000) { f.canceledReads >= 7 }
            runOnIdle { f.gate!!.complete(Unit) }
            onNodeWithText("This destination is not implemented in this preview. No account changes were made.").assertExists()
            assertEquals("/people", history.url)
        } finally { f.close() }
    }
}
