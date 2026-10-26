@file:OptIn(io.ktor.utils.io.InternalAPI::class, androidx.compose.ui.test.ExperimentalTestApi::class)

package com.techgv.cineverse.ui.web

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.search.RecentQueryStore
import com.techgv.cineverse.data.settings.*
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.di.*
import com.techgv.cineverse.domain.*
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

/** Root wiring over the real graph/runtime with fake TMDB, session engine, stores, opener and trailer host only. */
class WebRootTest {
    private class Fixture(signedIn: Boolean = false) : AutoCloseable {
        val calls = mutableListOf<String>()
        val http = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val trailerHost = WebTrailerHostFake()
        var backs = 0
        private val sessionBody = if (signedIn) WebApprovalFixture.SIGNED else WebApprovalFixture.GUEST
        private val api = object : WebSeasonUnusedApi() {
            override suspend fun movieDetail(id: Int, language: String, append: String, sessionId: String?): MovieDetailDto {
                calls += "movie:$id"
                return MovieDetailDto(id, title = "Root film", overview = "Root overview", videos = VideosDto(listOf(
                    VideoDto(key = WebTrailerHostFake.TRAILER.key, name = "Official Trailer", site = "YouTube", type = "Trailer", official = true))))
            }
            override suspend fun videos(media: MediaType, id: Int, language: String): VideosDto = VideosDto().also { calls += "videos:$id" }
            override suspend fun tvDetail(id: Int, language: String, append: String, sessionId: String?): TvDetailDto {
                calls += "tv:$id"
                return TvDetailDto(id, name = "Root series", seasons = listOf(SeasonSummaryDto(10, 0, "Specials")))
            }
            override suspend fun season(seriesId: Int, seasonNumber: Int, language: String): SeasonDto {
                calls += "season:$seriesId:$seasonNumber"
                return SeasonDto(10, seasonNumber, "Specials")
            }
            override suspend fun person(id: Int, language: String, append: String): PersonDetailDto {
                calls += "person:$id"
                return webPersonDto(id)
            }
            override suspend fun searchMulti(query: String, language: String, includeAdult: Boolean, page: Int): PageDto<MediaItemDto> {
                calls += "search:$query"
                return PageDto(page, emptyList(), 1, 0)
            }
        }
        private val values = object : SettingsValues {
            override fun string(key: String): String? = null
            override fun boolean(key: String): Boolean? = null
            override fun stringSet(key: String): Set<String>? = null
        }
        private val id = "cineverse.root.test.${++next}"
        val app = koinApplication { modules(sharedModules(WebConfig("/api")) + module {
            single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
            single { BrowserDatabase(id) } // Lazy/unopened: every store below is fake.
            single { BrowserChanges("$id.bus") }
            single<TmdbApi> { api }
            single { TmdbCache(get(), maxEntries = 0) } // Nothing is retained, so every reader restart is an observable API read.
            single<SettingsStore> { object : SettingsStore {
                override val data: Flow<SettingsValues> get() = flow { emit(values); awaitCancellation() }
                override suspend fun edit(transform: (MutableSettingsValues) -> Unit) { error("No settings writes in root tests") }
            } }
            single { SettingsRepository(get<SettingsStore>()) }
            single<GuestLibraryStore> { object : GuestLibraryStore {
                override fun observeKind(kind: LibraryKind, media: MediaType): Flow<List<GuestLibraryRow>> = flowOf(emptyList())
                override fun observeTitle(ref: MediaRef): Flow<List<GuestLibraryRow>> = flowOf(emptyList())
                override suspend fun all(): List<GuestLibraryRow> = emptyList()
                override suspend fun upsert(row: GuestLibraryRow) { error("No library writes in root tests") }
                override suspend fun delete(ref: MediaRef, kind: LibraryKind) { error("No library writes in root tests") }
            } }
            single<ImportOfferStore> { object : ImportOfferStore {
                override suspend fun offer(accountObjectId: String) = ImportOfferRecord(accountObjectId, imported = false, complete = true)
                override suspend fun put(offer: ImportOfferRecord) { error("No import writes in root tests") }
            } }
            single<HttpClient> { HttpClient(object : HttpClientEngineBase("web-root-fixture") {
                override val config = HttpClientEngineConfig().apply { dispatcher = Dispatchers.Unconfined }
                override suspend fun execute(data: HttpRequestData): HttpResponseData {
                    http += "${data.method.value} ${data.url.encodedPath}"
                    check(data.method == HttpMethod.Get && data.url.encodedPath == "/api/session") { "Root fixture allows only session reads" }
                    return HttpResponseData(HttpStatusCode.OK, GMTDate(), headersOf(HttpHeaders.ContentType, "application/json"),
                        HttpProtocolVersion.HTTP_1_1, ByteReadChannel(sessionBody), callContext())
                }
            }) }
        }) }
        val runtime = app.koin.get<WebRuntime>()
        val catalog = app.koin.get<CatalogRepository>()
        val generation = app.koin.get<GenerationCounter>()
        val repository = app.koin.get<SettingsRepository>()
        val recent = RecentQueryStore(app.koin.get<SettingsStore>())
        val opener = WebApprovalOpener { opened += "open" }
        override fun close() { runtime.stop(); app.close() }
        companion object { private var next = 0 }
    }

    private fun ComposeUiTest.root(f: Fixture, history: FakeHistory) {
        f.runtime.start()
        setContent {
            WebApp(f.runtime, f.catalog, f.generation, f.repository.settings, f.recent, history, f.repository, f.opener, f.trailerHost) { f.backs++ }
        }
        waitUntil(timeoutMillis = 10_000) { f.runtime.session.value is WebSessionState.Ready && f.runtime.settingsHolder.state.value?.settings != null }
    }

    /** An external history traversal to [url], restored through the same raw-history gate as a reload or popstate. */
    private fun ComposeUiTest.go(history: FakeHistory, url: String) = runOnIdle { history.push(url, ""); history.back(); history.forward() }

    private fun ComposeUiTest.exists(tag: String) = waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun deepRoutesComposeTheRealComponentsWithoutPlaceholdersOrWrites() = runComposeUiTest {
        val f = Fixture()
        val history = FakeHistory("/movie/1")
        try {
            root(f, history)
            exists("web-detail")
            waitUntil(timeoutMillis = 10_000) { "movie:1" in f.calls }
            onNodeWithText("Play trailer").assertExists()
            // Unmasked: the verified guest's origin-tagged snapshot matches the rendered Ready(null), so actions are live (none clicked).
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Add to watchlist").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Add to watchlist").assertIsEnabled()
            go(history, "/person/1")
            exists("web-person")
            waitUntil(timeoutMillis = 10_000) { "person:1" in f.calls }
            go(history, "/tv/1/season/0")
            exists("web-season")
            waitUntil(timeoutMillis = 10_000) { "season:1:0" in f.calls }
            go(history, "/search?q=x")
            exists("search-input")
            waitUntil(timeoutMillis = 10_000) { "search:x" in f.calls }
            go(history, "/library")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Guest library · saved on this browser; kept separate from account lists").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("/library?tab=watchlist", history.url)
            go(history, "/settings")
            exists("web-settings")
            onNodeWithText("not implemented in this preview", substring = true).assertDoesNotExist()
            assertEquals(setOf("GET /api/session"), f.http.toSet())
        } finally { f.close() }
    }

    @Test fun oneApprovalOwnerAboveRoutesSurvivesRouteChangesAndSettingsSignInShowsIt() = runComposeUiTest {
        val f = Fixture()
        val history = FakeHistory("/settings")
        try {
            root(f, history)
            exists("web-settings")
            onNode(hasText("Sign in") and hasAnyAncestor(hasTestTag("settings-account"))).performClick()
            exists("web-approval-dialog")
            onNodeWithText("Prepare sign-in").assertExists()
            go(history, "/movie/1")
            exists("web-detail")
            onNodeWithTag("web-approval-dialog").assertExists()
            onNodeWithText("Prepare sign-in").assertExists()
            go(history, "/person/1")
            exists("web-person")
            onNodeWithTag("web-approval-dialog").assertExists()
            assertTrue(f.opened.isEmpty())
            assertEquals(setOf("GET /api/session"), f.http.toSet()) // No start/cancel: the dialog was never recreated or dismissed.
        } finally { f.close() }
    }

    @Test fun runtimeInvalidationsFanInToPersonSeasonAndDetailRawReaders() = runComposeUiTest {
        val f = Fixture()
        val history = FakeHistory("/person/1")
        try {
            root(f, history)
            for ((url, read) in listOf("/person/1" to "person:1", "/tv/1/season/0" to "season:1:0", "/movie/1" to "movie:1")) {
                go(history, url)
                waitUntil(timeoutMillis = 10_000) { read in f.calls }
                runOnIdle { assertTrue(f.runtime.session.value is WebSessionState.Ready) }
                val before = f.calls.count { it == read }
                runOnIdle { f.runtime.revalidateSession() } // Bumps the generation; only the fanned-in event restarts the reader.
                waitUntil(timeoutMillis = 10_000) { f.calls.count { it == read } > before }
            }
        } finally { f.close() }
    }

    @Test fun signedInSettingsAccountActionsOpenHonestNoticesWithoutRuntimeOrAuthCalls() = runComposeUiTest {
        val f = Fixture(signedIn = true)
        val history = FakeHistory("/settings")
        try {
            root(f, history)
            exists("web-settings")
            val signed = f.runtime.session.value
            assertNotNull((signed as WebSessionState.Ready).session)
            val requests = f.http.size
            onNodeWithText("Sign out").performClick()
            exists("web-unavailable-notice")
            onNodeWithText("Sign out isn't available yet").assertExists()
            onNodeWithText("Signing out of the web app is not available in this build. You are still signed in as Fixture viewer. Nothing was changed.").assertExists()
            onNodeWithText("OK").performClick()
            onNodeWithTag("web-unavailable-notice").assertDoesNotExist()
            onNodeWithText("Import guest library").performClick()
            exists("web-unavailable-notice")
            onNodeWithText("Import isn't available yet").assertExists()
            onNodeWithText("Importing your guest library is not available in this build. Nothing was imported or changed.").assertExists()
            onNodeWithText("OK").performClick()
            onNodeWithTag("web-unavailable-notice").assertDoesNotExist()
            assertEquals(requests, f.http.size)
            assertEquals(signed, f.runtime.session.value)
            assertNull(f.runtime.storageFailure.value)
        } finally { f.close() }
    }

    @Test fun detailPlayTrailerOpensExactlyOneDialogAndEntryChangeRemovesTheFrame() = runComposeUiTest {
        val f = Fixture()
        val history = FakeHistory("/movie/1")
        try {
            root(f, history)
            exists("web-detail")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Play trailer").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Play trailer").performClick()
            exists("web-trailer-dialog")
            assertEquals(1, onAllNodesWithTag("web-trailer-dialog").fetchSemanticsNodes().size)
            waitUntil(timeoutMillis = 10_000) { f.trailerHost.creates == 1 }
            assertEquals(listOf(WebTrailerHostFake.src(WebTrailerHostFake.TRAILER.key, autoplay = 0)), f.trailerHost.srcs)
            onNodeWithTag("web-approval-dialog").assertDoesNotExist()
            go(history, "/person/1")
            exists("web-person")
            onNodeWithTag("web-trailer-dialog").assertDoesNotExist()
            assertEquals(1, f.trailerHost.creates)
            assertEquals(0, f.trailerHost.live)
            assertEquals(1, f.trailerHost.removes)
            go(history, "/movie/1")
            exists("web-detail")
            onNodeWithTag("web-trailer-dialog").assertDoesNotExist() // The cleared request never revives on a new entry.
            assertEquals(1, f.trailerHost.creates)
        } finally { f.close() }
    }
}
