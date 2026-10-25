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
        var paged = false
        var failNextPage = false
        var language = "en-US"
        data class Read(val path: String, val page: String?, val language: String?, val sort: String?, val genres: String?)
        val reads = mutableListOf<Read>()
        val storageRevision = MutableStateFlow(0)
        var gate: CompletableDeferred<Unit>? = null
        private val values = object : SettingsValues {
            override fun string(key: String): String? = if (key == "language") language else null
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
            single { SettingsRepository(get<SettingsStore>()) }
            single<HttpClient> { HttpClient(object : HttpClientEngineBase("web-root-fixture") {
                override val config = HttpClientEngineConfig().apply { dispatcher = Dispatchers.Unconfined }
                override suspend fun execute(data: HttpRequestData): HttpResponseData {
                    check(data.method == HttpMethod.Get) { "Root fixture forbids protected mutations" }
                    val session = data.url.encodedPath == "/api/session"
                    if (session) sessionReads++ else {
                        catalogReads++
                        reads += Read(data.url.encodedPath, data.url.parameters["page"], data.url.parameters["language"], data.url.parameters["sort_by"], data.url.parameters["with_genres"])
                        try { gate?.await() } catch (canceled: CancellationException) { canceledReads++; throw canceled }
                    }
                    val body = when {
                        session && failSession -> """{"error":"upstream_error"}"""
                        session -> """{"signedIn":false,"accountObjectId":null,"displayName":null,"sessionGeneration":"0"}"""
                        data.url.encodedPath.contains("/genre/") -> """{"genres":[{"id":28,"name":"Action"}]}"""
                        failNextPage && data.url.parameters["page"] == "2" -> """{"error":"upstream_error"}"""
                        withTitles && data.url.encodedPath.contains("/person/") -> """{"page":1,"results":[{"id":42,"name":"Loaded person","known_for_department":"Acting","known_for":[]}],"total_pages":1,"total_results":1}"""
                        withTitles -> """{"page":${data.url.parameters["page"] ?: "1"},"results":[{"id":${if (data.url.parameters["page"] == "2") 8 else 7},"media_type":"${if (data.url.encodedPath.contains("/tv/") || data.url.encodedPath.endsWith("/tv")) "tv" else "movie"}","title":"Loaded film","name":"Loaded series","overview":"Retained public overview","vote_average":8.1,"vote_count":20}],"total_pages":${if (paged) 2 else 1},"total_results":2}"""
                        else -> """{"page":1,"results":[],"total_pages":1,"total_results":0}"""
                    }
                    return HttpResponseData(if ((session && failSession) || (failNextPage && data.url.parameters["page"] == "2")) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                        GMTDate(), headersOf(HttpHeaders.ContentType, "application/json"), HttpProtocolVersion.HTTP_1_1, ByteReadChannel(body), callContext())
                }
            }) }
        }) }
        val runtime = app.koin.get<WebRuntime>()
        val catalog = app.koin.get<CatalogRepository>()
        val generation = app.koin.get<GenerationCounter>()
        val settings = app.koin.get<SettingsRepository>().settings
        override fun close() { runtime.stop(); app.close() }
    }

    @Test fun rootAuthAndStorageFailuresRequireSeparateExplicitReadRetries() = runComposeUiTest {
        val f = Fixture()
        val history = FakeHistory()
        try {
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, history) }
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
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, history) }
            waitUntil(timeoutMillis = 10_000) { f.catalogReads >= 7 }
            onNodeWithText("My Library").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 10_000) { f.canceledReads >= 7 }
            runOnIdle { f.gate!!.complete(Unit) }
            onNodeWithText("This destination is not implemented in this preview. No account changes were made.").assertExists()
            assertEquals("/library?tab=watchlist", history.url)
        } finally { f.close() }
    }

    @Test fun browseSourcesFiltersPeopleAndHistoryUseRealGraphWithoutWrites() = runComposeUiTest {
        val f = Fixture()
        val history = FakeHistory("/movies?list=upcoming")
        try {
            f.failSession = false; f.failSettings = false; f.withTitles = true
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, history) }
            onNodeWithTag("browse-movie:7").assertExists()
            assertTrue(f.reads.any { it.path == "/api/tmdb/movie/upcoming" })
            onNodeWithText("Action").performClick()
            onNodeWithText("Movies · Discover").assertExists()
            waitUntil { f.reads.any { it.path == "/api/tmdb/discover/movie" && it.genres == "28" && it.sort == "popularity.desc" } }
            runOnIdle { history.back() }
            onNodeWithText("Movies · Upcoming").assertExists()
            runOnIdle { history.forward() }
            onNodeWithText("Movies · Discover").assertExists()
            onNodeWithText("People").performClick()
            onNodeWithTag("browse-person:42").assertExists()
            assertTrue(f.reads.any { it.path == "/api/tmdb/person/popular" })
            onNodeWithTag("browse-person:42").performClick()
            assertEquals("/person/42", history.url)
            onNodeWithText("This destination is not implemented in this preview. No account changes were made.").assertExists()
        } finally { f.close() }
    }

    @Test fun browseStorageRetryResubscribesRuntimeAndHolderWithoutRecreationOrFallback() = runComposeUiTest {
        val f = Fixture()
        val history = FakeHistory("/movies")
        try {
            f.failSession = true; f.failSettings = false; f.withTitles = true
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, history) }
            onNodeWithTag("browse-movie:7").assertExists()
            onNodeWithText("Sign in").assertDoesNotExist()
            runOnIdle { f.failSettings = true; f.storageRevision.value++ }
            waitUntil { f.activeSettingsReaders == 0 }
            onNodeWithTag("browse-settings-recovery").assertExists()
            val subscriptions = f.settingsSubscriptions
            val reads = f.catalogReads
            runOnIdle { f.failSettings = false }
            onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("browse-storage-recovery"))).performClick()
            waitUntil { f.runtime.storageFailure.value == null && f.activeSettingsReaders >= 3 }
            assertTrue(f.settingsSubscriptions > subscriptions)
            assertEquals(reads, f.catalogReads)
            onNodeWithTag("browse-movie:7").assertExists()
            onNodeWithTag("browse-settings-recovery").assertDoesNotExist()
            onNodeWithText("Sign in").assertDoesNotExist()
            assertEquals("/movies?list=popular", history.url)
        } finally { f.close() }
    }

    @Test fun browseSettingsChangeUsesGraphInvalidationAndNewLanguage() = runComposeUiTest {
        val f = Fixture()
        val history = FakeHistory("/tv?list=on_the_air&sort=title.asc")
        try {
            f.failSession = false; f.failSettings = false; f.withTitles = true
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, history) }
            onNodeWithTag("browse-tv:7").assertExists()
            onNodeWithTag("browse-movie:7").assertDoesNotExist()
            val generation = f.generation.current
            assertTrue(f.reads.any { it.path == "/api/tmdb/discover/tv" && it.language == "en-US" })
            runOnIdle { f.language = "es-ES"; f.storageRevision.value++ }
            waitUntil { f.reads.any { it.path == "/api/tmdb/discover/tv" && it.language == "es-ES" && it.sort == "name.asc" } }
            assertTrue(f.generation.current > generation)
            onNodeWithText("TV Shows · Discover").assertExists()
            onNodeWithTag("browse-tv:7").assertExists()
        } finally { f.close() }
    }

    @Test fun leavingBrowseCancelsGridAndGenreReadsAndRetryCannotReviveOwner() = runComposeUiTest {
        val f = Fixture()
        val history = FakeHistory("/movies?list=now_playing")
        try {
            f.failSession = false; f.failSettings = false
            f.gate = CompletableDeferred()
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, history) }
            waitUntil { f.catalogReads >= 2 }
            onNodeWithText("My Library").performClick()
            waitUntil { f.canceledReads >= 2 }
            val reads = f.catalogReads
            runOnIdle { f.gate!!.complete(Unit); f.runtime.retryStorage() }
            assertEquals(reads, f.catalogReads)
            assertEquals("/library?tab=watchlist", history.url)
            onNodeWithText("This destination is not implemented in this preview. No account changes were made.").assertExists()
        } finally { f.close() }
    }

    @Test fun browseNextPageFailureRetainsCardsAndRetriesOnlyOnExplicitAction() = runComposeUiTest {
        val f = Fixture()
        val history = FakeHistory("/movies")
        try {
            f.failSession = false; f.failSettings = false; f.withTitles = true; f.paged = true; f.failNextPage = true
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, history) }
            onNodeWithTag("web-browse").performScrollToNode(hasText("Load more"))
            onNodeWithText("Load more").performClick()
            onNodeWithText("Could not load results. Loaded results are retained.").assertExists()
            assertEquals(1, f.reads.count { it.page == "2" })
            onNodeWithTag("browse-movie:7").assertExists()
            runOnIdle { f.failNextPage = false }
            onNodeWithText("Retry").performClick()
            waitUntil { f.reads.count { it.page == "2" } == 2 }
            onNodeWithTag("browse-movie:8").assertExists()
            onNodeWithText("2 titles loaded · not a total result count").assertExists()
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
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, history) }
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
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, history) }
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
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, history) }
            failTrendingRead(f)
            onNodeWithText("My Library").performClick()
            val reads = f.catalogReads
            runOnIdle { f.failSettings = false }
            onNodeWithText("Retry").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 10_000) { f.runtime.storageFailure.value == null }
            assertEquals(reads, f.catalogReads)
            assertEquals("/library?tab=watchlist", history.url)
            onNodeWithText("This destination is not implemented in this preview. No account changes were made.").assertExists()
        } finally { f.close() }
    }

}
