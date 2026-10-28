@file:OptIn(io.ktor.utils.io.InternalAPI::class, androidx.compose.ui.test.ExperimentalTestApi::class)

package com.techgv.cineverse.ui.web

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.web.WebApprovalAttempt
import com.techgv.cineverse.data.web.WebProxyHttp
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
    private class Fixture(signedIn: Boolean = false, twoTrailerTitles: Boolean = false) : AutoCloseable {
        val calls = mutableListOf<String>()
        val http = mutableListOf<String>()
        val opened = mutableListOf<String>()
        /** Library writes as `<identity>:KIND/ref`: `fixture-account#<generation>` (account) or `guest`. */
        val writes = mutableListOf<String>()
        val trailerHost = WebTrailerHostFake()
        var backs = 0
        private var sessionBody = if (signedIn) WebApprovalFixture.SIGNED else WebApprovalFixture.GUEST
        /** When set, session reads wait for it: the runtime stays Checking. */
        var sessionGate: CompletableDeferred<Unit>? = null
        var videoGate: CompletableDeferred<Unit>? = null
        private val guestRows = MutableStateFlow<List<GuestLibraryRow>>(emptyList())
        private val account = mutableMapOf<MediaRef, LibraryState>()
        private val api = object : WebSeasonUnusedApi() {
            override suspend fun movieDetail(id: Int, language: String, append: String, sessionId: String?): MovieDetailDto {
                calls += "movie:$id"
                return MovieDetailDto(id, title = "Root film", overview = "Root overview", videos = VideosDto(listOf(
                    VideoDto(key = WebTrailerHostFake.TRAILER.key, name = "Official Trailer", site = "YouTube", type = "Trailer", official = true))))
            }
            override suspend fun videos(media: MediaType, id: Int, language: String): VideosDto {
                calls += "videos:$id"
                videoGate?.await()
                return VideosDto(listOf(
                    VideoDto(key = "tEaSeR_0001", name = "Teaser", site = "YouTube", type = "Teaser", official = true),
                    VideoDto(key = WebTrailerHostFake.TRAILER.key, name = "Official Trailer", site = "YouTube", type = "Trailer", official = true),
                ))
            }
            // Default Home: one trending movie; optional upcoming fixtures exercise two pending trailer presses.
            override suspend fun trending(window: TrendingWindow, language: String, page: Int) =
                PageDto(page, listOf(MediaItemDto(1, mediaType = "movie", title = "Root film", releaseDate = "2026-03-13")), 1, 1)
            override suspend fun movieList(list: MovieList, language: String, region: String, page: Int) =
                if (twoTrailerTitles && list == MovieList.Upcoming) PageDto(page, listOf(
                    MediaItemDto(1, title = "Root film"), MediaItemDto(2, title = "Second film")), 1, 2)
                else PageDto<MediaItemDto>(page, emptyList(), 1, 0)
            override suspend fun tvList(list: TvList, language: String, timezone: String?, page: Int) = PageDto<MediaItemDto>(page, emptyList(), 1, 0)
            override suspend fun popularPeople(language: String, page: Int) = PageDto<MediaItemDto>(page, emptyList(), 1, 0)
            override suspend fun genres(media: MediaType, language: String) = GenreListDto()
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
            // test perf: glow off; glow-follows-preference covered by WebRuntimeUiTest.ambientGlowFollowsTheSavedPreference.
            override fun boolean(key: String): Boolean? = if (key == "glow") false else null
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
                override fun observeKind(kind: LibraryKind, media: MediaType): Flow<List<GuestLibraryRow>> =
                    guestRows.map { rows -> rows.filter { it.kind == kind && it.ref.mediaType == media } }
                override fun observeTitle(ref: MediaRef): Flow<List<GuestLibraryRow>> = guestRows.map { rows -> rows.filter { it.ref == ref } }
                override suspend fun all(): List<GuestLibraryRow> = guestRows.value
                override suspend fun upsert(row: GuestLibraryRow) {
                    writes += "guest:${row.kind}/${row.ref.key}"
                    guestRows.value = guestRows.value.filterNot { it.ref == row.ref && it.kind == row.kind } + row
                }
                override suspend fun delete(ref: MediaRef, kind: LibraryKind) {
                    writes += "guest:$kind/${ref.key}"
                    guestRows.value = guestRows.value.filterNot { it.ref == ref && it.kind == kind }
                }
            } }
            single<AccountLibrarySource> { object : AccountLibrarySource {
                override suspend fun page(session: AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage =
                    error("No account list reads in root tests")
                override fun invalidateLists() = Unit
                override suspend fun states(session: AuthSession, ref: MediaRef) =
                    account[ref] ?: LibraryState(known = true, watchlist = false, favourite = false, rating = null, pending = emptySet())
                override suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState) {
                    writes += "${session.accountObjectId.value}#${session.sessionGeneration}:$kind/${ref.key}"
                    account[ref] = target.copy(pending = emptySet())
                }
            } }
            single<ImportOfferStore> { object : ImportOfferStore {
                override suspend fun offer(accountObjectId: String) = ImportOfferRecord(accountObjectId, imported = false, complete = true)
                override suspend fun put(offer: ImportOfferRecord) { error("No import writes in root tests") }
            } }
            single<HttpClient> { HttpClient(object : HttpClientEngineBase("web-root-fixture") {
                override val config = HttpClientEngineConfig().apply { dispatcher = Dispatchers.Unconfined }
                override suspend fun execute(data: HttpRequestData): HttpResponseData {
                    http += "${data.method.value} ${data.url.encodedPath}"
                    val logout = data.method == HttpMethod.Post && data.url.encodedPath == "/api/auth/logout" && data.headers["X-CV-CSRF"] == "root-fixture-csrf"
                    check(logout || data.method == HttpMethod.Get && data.url.encodedPath == "/api/session") { "Root fixture allows only session reads and logout" }
                    if (logout) sessionBody = WebApprovalFixture.GUEST else sessionGate?.await()
                    return HttpResponseData(if (logout) HttpStatusCode.NoContent else HttpStatusCode.OK, GMTDate(),
                        headersOf(HttpHeaders.ContentType, "application/json"), HttpProtocolVersion.HTTP_1_1,
                        ByteReadChannel(if (logout) "" else sessionBody), callContext())
                }
            }) }
            single { WebProxyHttp(get()) { "root-fixture-csrf" } }
        }) }
        val runtime = app.koin.get<WebRuntime>()
        val catalog = app.koin.get<CatalogRepository>()
        val generation = app.koin.get<GenerationCounter>()
        val repository = app.koin.get<SettingsRepository>()
        val recent = RecentQueryStore(app.koin.get<SettingsStore>())
        val opener = object : WebApprovalOpener {
            override fun openPending(): WebApprovalOpener.PendingTab {
                opened += "pending"
                return object : WebApprovalOpener.PendingTab {
                    override fun navigate(url: String) { opened += "navigate" }
                    override fun close() { opened += "close" }
                }
            }
            override fun open(approval: WebApprovalAttempt) { opened += "open" }
        }
        override fun close() { sessionGate?.complete(Unit); videoGate?.complete(Unit); runtime.stop(); app.close() }
        companion object { private var next = 0 }
    }

    private fun ComposeUiTest.root(f: Fixture, history: FakeHistory) {
        f.runtime.start()
        setContent {
            // test perf: CPU raster of the wide player; the 1000dp slot is covered by WebTrailerDialogTest
            WebApp(f.runtime, f.catalog, f.generation, f.repository.settings, f.recent, history, f.repository, f.opener, f.trailerHost,
                trailerDialogMaxWidth = 560.dp) { f.backs++ }
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
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Add to watchlist").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("Add to watchlist").assertIsEnabled()
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
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Saved in this browser · sign in to sync with TMDB").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("/library?tab=watchlist", history.url)
            go(history, "/settings")
            exists("web-settings")
            onNodeWithText("not implemented in this preview", substring = true).assertDoesNotExist()
            assertEquals(setOf("GET /api/session"), f.http.toSet())
            assertEquals(emptyList(), f.writes)
        } finally { f.close() }
    }

    @Test fun rapidHomeTrailerPressesKeepFirstPendingTitleAndOpeningTrigger() = runComposeUiTest {
        val f = Fixture(twoTrailerTitles = true)
        f.videoGate = CompletableDeferred()
        try {
            root(f, FakeHistory("/"))
            exists("web-home")
            onNodeWithTag("web-home").performScrollToNode(hasTestTag("home-trailer-movie:1"))
            val first = onNodeWithTag("home-trailer-movie:1").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            val second = onNodeWithTag("home-trailer-movie:2").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            runOnIdle { first(); second() } // Both real UI handlers, before any rendered loading-state update.
            waitUntil { f.calls.count { it == "videos:1" } == 1 }
            runOnIdle {
                assertFalse("videos:2" in f.calls)
                f.videoGate!!.complete(Unit)
            }
            exists("web-trailer-dialog") // Also proves requested title was not replaced by the ignored second press.
            onNodeWithContentDescription("Close").performClick()
            onNodeWithTag("home-trailer-movie:1").assertIsFocused()
            assertFalse("videos:2" in f.calls)
            assertEquals(emptyList(), f.writes)
        } finally { f.close() }
    }

    @Test fun detailTrailerCloseReturnsToTheLivePlayButton() = runComposeUiTest {
        val f = Fixture()
        try {
            root(f, FakeHistory("/movie/1"))
            exists("web-detail")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Play trailer").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Play trailer").performClick()
            exists("web-trailer-dialog")
            onNodeWithContentDescription("Close").performClick()
            onNodeWithText("Play trailer").assertIsFocused()
            assertEquals(0, f.trailerHost.live)
            assertEquals(emptyList(), f.writes)
        } finally { f.close() }
    }

    @Test fun detailRateCancelReturnsToTheActualRateTriggerWithoutWriting() = runComposeUiTest {
        val f = Fixture(signedIn = true)
        try {
            root(f, FakeHistory("/movie/1"))
            exists("web-detail")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Rate").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("Rate").performClick()
            exists("web-rate-dialog")
            onNodeWithContentDescription("Close").performClick()
            onNodeWithContentDescription("Rate").assertIsFocused()
            assertEquals(emptyList(), f.writes)
        } finally { f.close() }
    }

    @Test fun detailWatchlistDispatchesTheRenderedSessionThroughTheAppHolderAndToastsSynced() = runComposeUiTest {
        val f = Fixture(signedIn = true)
        val history = FakeHistory("/movie/1")
        try {
            root(f, history)
            val rendered = (f.runtime.session.value as WebSessionState.Ready).session!!
            exists("web-detail")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Add to watchlist").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("Add to watchlist").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synced to your TMDB account").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf("fixture-account#${rendered.sessionGeneration}:WATCHLIST/movie:1"), f.writes)
            onNodeWithText("Added “Root film” to Watchlist").assertExists()
            onNodeWithContentDescription("In watchlist").assertExists()
        } finally { f.close() }
    }

    // Root frames are costly (~1s per dialog enter under Mocha's 2s): one dialog lifecycle per test, and no idle wait
    // after a write (the outcome toast's enter animation). The toast wiring is asserted by the watchlist test.
    @Test fun detailRateOpensTheAppLevelDialogAndSubmitWritesOnce() = runComposeUiTest {
        val f = Fixture(signedIn = true)
        val history = FakeHistory("/movie/1")
        try {
            root(f, history)
            val rendered = (f.runtime.session.value as WebSessionState.Ready).session!!
            exists("web-detail")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Rate").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("Rate").performClick()
            exists("web-rate-dialog")
            onNodeWithTag("web-rate-stars").performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
            onNodeWithText("Submit rating").performClick()
            assertEquals(listOf("fixture-account#${rendered.sessionGeneration}:RATING/movie:1"), f.writes)
        } finally { f.close() }
    }

    @Test fun rateActionBehindTheOpenTrailerNeverComposesTheRateDialog() = runComposeUiTest {
        val f = Fixture(signedIn = true)
        val history = FakeHistory("/movie/1")
        try {
            root(f, history)
            exists("web-detail")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Rate").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Play trailer").performClick()
            exists("web-trailer-dialog")
            // The Detail button stays in the tree behind the trailer modal; its click action reaches openRate.
            onNodeWithContentDescription("Rate").performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithTag("web-rate-dialog").assertDoesNotExist()
            onNodeWithTag("web-trailer-dialog").assertExists()
            assertEquals(emptyList(), f.writes)
        } finally { f.close() }
    }

    @Test fun detailAccountStateIsMaskedUnknownWhileItsOriginIsNotTheRenderedReadySession() = runComposeUiTest {
        val f = Fixture(signedIn = true)
        val history = FakeHistory("/movie/1")
        try {
            root(f, history)
            val rendered = (f.runtime.session.value as WebSessionState.Ready).session!!
            exists("web-detail")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Add to watchlist").fetchSemanticsNodes().isNotEmpty() }
            f.sessionGate = CompletableDeferred()
            runOnIdle { f.runtime.revalidateSession() } // Checking: the repository still publishes the account-origin snapshot.
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Watchlist · unknown").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("Watchlist · unknown").assertIsNotEnabled()
            onNodeWithContentDescription("Rating · unknown").assertIsNotEnabled()
            var snapshot: LibraryStateSnapshot? = null
            val read = CoroutineScope(Dispatchers.Unconfined).launch {
                snapshot = f.runtime.libraryStateWithOrigin(MediaRef(MediaType.Movie, 1)).first { it.sourceIdentityKnown }
            }
            waitUntil(timeoutMillis = 10_000) { snapshot != null }
            read.cancel()
            assertEquals(rendered, snapshot!!.sourceIdentity) // Known account data, masked only because the render is not Ready.
            runOnIdle { f.sessionGate!!.complete(Unit) }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Add to watchlist").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(emptyList(), f.writes)
        } finally { f.close() }
    }

    @Test fun settingsSignOutOpensTheOwnerDialogAndLogsOutOnlyOnConfirm() = runComposeUiTest {
        val f = Fixture(signedIn = true)
        val history = FakeHistory("/settings")
        try {
            root(f, history)
            exists("web-settings")
            onNode(hasText("Sign out") and hasAnyAncestor(hasTestTag("settings-account"))).performClick()
            exists("web-sign-out-dialog")
            onNodeWithText("Sign out Fixture viewer from this browser?", substring = true).assertExists()
            assertEquals(setOf("GET /api/session"), f.http.toSet()) // Opening the dialog sends nothing.
            onNode(hasText("Sign out") and hasAnyAncestor(hasTestTag("web-sign-out-dialog"))).performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("This browser is now verified as signed out.").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, f.http.count { it == "POST /api/auth/logout" })
            assertEquals(WebSessionState.Ready(null), f.runtime.session.value)
        } finally { f.close() }
    }

    @Test fun settingsSignOutCancelRestoresTheOpeningButtonWithoutLogout() = runComposeUiTest {
        val f = Fixture(signedIn = true)
        try {
            root(f, FakeHistory("/settings"))
            exists("web-settings")
            val trigger = hasText("Sign out") and hasAnyAncestor(hasTestTag("settings-account"))
            onNode(trigger).performClick()
            exists("web-sign-out-dialog")
            onNodeWithContentDescription("Close").performClick()
            onNode(trigger).assertIsFocused()
            assertEquals(setOf("GET /api/session"), f.http.toSet())
            assertEquals(emptyList(), f.writes)
        } finally { f.close() }
    }

    @Test fun settingsImportOpensTheOwnerDialogWithoutWritesOrLogout() = runComposeUiTest {
        val f = Fixture(signedIn = true)
        val history = FakeHistory("/settings")
        try {
            root(f, history)
            exists("web-settings")
            onNodeWithText("Import guest library").performClick()
            exists("web-import-dialog")
            onNodeWithText("Nothing to import").assertExists() // The fake offer is complete: the owner reports it honestly.
            onNodeWithTag("web-sign-out-dialog").assertDoesNotExist()
            onNodeWithContentDescription("Close").performClick()
            onNodeWithText("Import guest library").assertIsFocused()
            assertEquals(setOf("GET /api/session"), f.http.toSet())
            assertEquals(emptyList(), f.writes)
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
            onNodeWithText("Continue to TMDB").assertExists()
            go(history, "/movie/1")
            exists("web-detail")
            onNodeWithTag("web-approval-dialog").assertExists()
            onNodeWithText("Continue to TMDB").assertExists()
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

    /** Home over the real graph; the root's 1-line `trailer = …` binding is the one Detail's dialog test covers. */
    @Test fun homePlayTrailerHandsTheRootTheTitlesRealValidatedVideoList() = runComposeUiTest {
        val f = Fixture()
        val routes = mutableListOf<WebRoute>()
        val played = mutableListOf<Pair<List<Video>, Video>>()
        try {
            f.runtime.start()
            waitUntil(timeoutMillis = 10_000) { f.runtime.session.value is WebSessionState.Ready }
            setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
                val entry = remember { WebNavigation(FakeHistory("/")).entry.value }
                WebHome(entry, f.catalog, f.generation, f.repository.settings, f.runtime.session.value, f.runtime, { route, _ -> routes += route },
                    playTrailer = { videos, video -> played += videos to video }, signIn = {})
            }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Play trailer").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Mar 13, 2026").assertExists() // The trending card's date, formatted; never the raw ISO string.
            onNodeWithText("Play trailer").performClick()
            waitUntil(timeoutMillis = 10_000) { played.isNotEmpty() }
            // The holder's pick (official trailer first) plus the rest of the fetched list; nothing invented, no route.
            val trailer = WebTrailerHostFake.TRAILER
            assertEquals(listOf(listOf(trailer, Video("tEaSeR_0001", "Teaser", "Teaser", official = true)) to trailer), played)
            assertEquals(emptyList(), routes)
            assertEquals(emptyList(), f.writes)
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
