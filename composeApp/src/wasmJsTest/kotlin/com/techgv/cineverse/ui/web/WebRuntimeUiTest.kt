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
        var settingsSubscriptions = 0
        var activeSettingsReaders = 0
        var withTitles = false
        val storageRevision = MutableStateFlow(0)
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
                    settingsSubscriptions++
                    activeSettingsReaders++
                    try {
                        storageRevision.collect {
                            if (failSettings) throw BrowserStorageException(BrowserStorageFailure.Blocked)
                            emit(values)
                        }
                    } finally { activeSettingsReaders-- }
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
                        withTitles && !data.url.encodedPath.contains("/person/") -> """{"page":1,"results":[{"id":7,"media_type":"movie","title":"Loaded film","name":"Loaded series","overview":"Retained public overview","vote_average":8.1,"vote_count":20}],"total_pages":1,"total_results":1}"""
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

    private fun ComposeUiTest.failTrendingRead(f: Fixture) {
        onNodeWithText("Retained public overview").assertExists()
        runOnIdle { f.failSettings = true }
        onNodeWithTag("web-home").performScrollToIndex(1)
        onNodeWithText("Today").assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 10_000) { f.runtime.storageFailure.value == BrowserStorageFailure.Blocked }
        onNodeWithTag("home-trending-recovery").assertExists()
    }

    private fun ComposeUiTest.retryWithin(tag: String) =
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag(tag))).assertIsDisplayed().performClick()

    @Test fun loadedHomeStorageRetryRecoversOwnerReadsWhenRecoveredPreferencesAreEqual() = runComposeUiTest {
        val f = Fixture()
        val history = FakeHistory()
        try {
            f.failSession = false; f.failSettings = false; f.withTitles = true
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, history) }
            onNodeWithText("Retained public overview").assertExists()
            val settingsBefore = f.runtime.settingsHolder.state.value?.settings
            failTrendingRead(f)
            runOnIdle { f.storageRevision.value++ }
            waitUntil(timeoutMillis = 10_000) { f.activeSettingsReaders == 0 }
            onNodeWithTag("web-home").performScrollToIndex(3)
            onNodeWithTag("home-popularMovies-movie:7").assertIsDisplayed()
            val subscriptions = f.settingsSubscriptions
            val reads = f.catalogReads
            runOnIdle { f.failSettings = false } // No new settings emission: explicit recovery must re-subscribe.
            onNodeWithTag("web-home").performScrollToIndex(0)
            retryWithin("home-storage-recovery")
            waitUntil(timeoutMillis = 10_000) { f.runtime.storageFailure.value == null && f.activeSettingsReaders >= 3 }
            onNodeWithText("Retained public overview").assertExists()
            assertEquals(settingsBefore, f.runtime.settingsHolder.state.value?.settings)
            assertTrue(f.settingsSubscriptions > subscriptions)
            assertEquals(reads + 1, f.catalogReads)
            onNodeWithTag("home-storage-recovery").assertDoesNotExist()
        } finally { f.close() }
    }

    @Test fun ordinaryRowRecoveryLeavesOnlyTruthfulHistoricalStorageNotice() = runComposeUiTest {
        val f = Fixture()
        val history = FakeHistory()
        try {
            f.failSession = false; f.failSettings = false; f.withTitles = true
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, history) }
            failTrendingRead(f)
            runOnIdle { f.failSettings = false }
            retryWithin("home-trending-recovery")
            onNodeWithTag("home-trending-recovery").assertDoesNotExist()
            onNodeWithTag("web-home").performScrollToIndex(0)
            onNodeWithText("Retained public overview").assertExists()
            assertEquals(BrowserStorageFailure.Blocked, f.runtime.storageFailure.value)
            onNodeWithText("Last reported storage error: blocked access. Check browser storage access, then retry reads.").assertExists()
        } finally { f.close() }
    }

    @Test fun retryAfterLeavingHomeCannotRestartDisposedHolderReads() = runComposeUiTest {
        val f = Fixture()
        val history = FakeHistory()
        try {
            f.failSession = false; f.failSettings = false; f.withTitles = true
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, history) }
            failTrendingRead(f)
            onNodeWithText("People").performClick()
            val reads = f.catalogReads
            runOnIdle { f.failSettings = false }
            onNodeWithText("Retry").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 10_000) { f.runtime.storageFailure.value == null }
            assertEquals(reads, f.catalogReads)
            assertEquals("/people", history.url)
            onNodeWithText("This destination is not implemented in this preview. No account changes were made.").assertExists()
        } finally { f.close() }
    }

}
