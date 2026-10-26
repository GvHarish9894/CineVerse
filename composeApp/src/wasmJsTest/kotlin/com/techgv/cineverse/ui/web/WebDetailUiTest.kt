package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WebDetailUiTest {
    private fun movieDto(): MovieDetailDto = MovieDetailDto(7, title = "Fixture film", tagline = "Fixture tagline", overview = "Fixture overview",
        releaseDate = "2026-10-01", runtime = 121, genres = listOf(GenreDto(18, "Drama")), voteAverage = 8.1, voteCount = 15,
        credits = CreditsDto(cast = listOf(CastDto(42, name = "Fixture actor", character = "Actual role")), crew = listOf(CrewDto(43, name = "Fixture director", job = "Director"))),
        videos = VideosDto(listOf(VideoDto(key = "invalid", site = "YouTube", type = "Trailer", official = true),
            VideoDto(key = "aB_09-xYz12", name = "Actual trailer", site = "YouTube", type = "Trailer"))),
        recommendations = PageDto(results = listOf(MediaItemDto(81, title = "Related film", mediaType = "movie"))))
    private fun movie(): Detail = movieDto().toDetail("US")
    private val known = LibraryState(true, true, false, 7.5, emptySet())

    private fun completed(detail: Detail): WebDetailReadState {
        val context = DetailReadContext(detail.ref, "en-US", "US", 0, 1)
        val result = SectionState.Content(detail)
        return WebDetailReadState(result, retained = detail, producer = DetailUiState(result,
            DetailSettingsRead.Ready, context, DetailReadCompletion(context, result), DetailReadContent(context, detail)))
    }

    @Test fun delayedCatalogRestoresActualSavedScrollThenPersistsUserScrollWithoutReapplyingOnRetry() = runComposeUiTest {
        val entry = WebDetailOwnerTest.entry(values = mapOf("detail.index" to "4", "detail.offset" to "37"))
        val gate = CompletableDeferred<Unit>()
        val api = WebDetailCatalogFake()
        val fixture = movieDto()
        api.movie = { _, _ ->
            gate.await()
            fixture.copy(title = if (api.calls.size == 1) fixture.title else "Retried fixture film")
        }
        val generation = GenerationCounter()
        val catalog = CatalogRepository(api, generation, { Settings() })
        val settings = flowOf(Settings())
        val session = MutableStateFlow<WebSessionState>(WebSessionState.Ready(null))
        val storage = MutableStateFlow<BrowserStorageFailure?>(null)
        lateinit var owner: WebDetailOwner
        var account by mutableStateOf(LibraryState.Unknown)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            val scope = rememberCoroutineScope()
            val current = remember(entry) {
                WebDetailOwner(entry, catalog, generation, settings, session, storage, {}, { throw it }, null, { emptyFlow() }, scope)
            }
            owner = current
            DisposableEffect(current) { onDispose { current.close() } }
            val state by current.state.collectAsState()
            WebDetailContent(entry, state, account, session.value, false, null,
                {}, current::retryDetail, { _, _, _ -> }, {}, {}, { _, _, _ -> })
        }
        onNodeWithText("Loading title…").assertExists()
        runOnIdle {
            assertEquals(listOf("movie:7:en-US"), api.calls)
            assertFalse(gate.isCompleted)
            assertEquals("4", entry.getString("detail.index"))
            assertEquals("37", entry.getString("detail.offset"))
        }
        runOnIdle { gate.complete(Unit) }
        onNodeWithTag("detail-video-aB_09-xYz12").assertIsDisplayed()
        val list = onNodeWithTag("web-detail")
        val restoredPosition = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        runOnIdle {
            assertEquals(4, entry.getInt("detail.index"))
            assertEquals(37, entry.getInt("detail.offset"))
        }
        // Compare actual lazy-list semantics with an index-4 scroll, not just unchanged saved scalars.
        list.performScrollToIndex(4)
        val indexFourPosition = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(37f, restoredPosition - indexFourPosition)
        waitUntil { entry.getInt("detail.index") == 4 && entry.getInt("detail.offset") == 0 }
        list.performScrollToIndex(2)
        waitUntil { entry.getInt("detail.index") == 2 && entry.getInt("detail.offset") == 0 }
        val userPosition = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        runOnIdle {
            account = known
            catalog.invalidateTitle(MediaType.Movie, 7)
            owner.retryDetail()
        }
        waitUntil { owner.state.value.retained?.title == "Retried fixture film" }
        assertEquals(userPosition, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
        runOnIdle {
            assertEquals(2, entry.getInt("detail.index"))
            assertEquals(0, entry.getInt("detail.offset"))
        }
    }

    @Test fun initialErrorAndLoadingRetryNeverOverwriteSavedTargetWithoutRealContent() = runComposeUiTest {
        val entry = WebDetailOwnerTest.entry(values = mapOf("detail.index" to "4", "detail.offset" to "37"))
        var state by mutableStateOf(WebDetailReadState(SectionState.Error(SectionErrorKind.Offline)))
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebDetailContent(entry, state, LibraryState.Unknown, WebSessionState.Checking, false, null,
                {}, { state = WebDetailReadState() }, { _, _, _ -> }, {}, {}, { _, _, _ -> })
        }
        onNodeWithText("Offline. Could not load this title.").assertExists()
        runOnIdle {
            assertEquals("4", entry.getString("detail.index"))
            assertEquals("37", entry.getString("detail.offset"))
        }
        onNodeWithText("Retry").performClick()
        onNodeWithText("Loading title…").assertExists()
        onNodeWithTag("web-detail").performScrollToIndex(2)
        runOnIdle {
            assertEquals("4", entry.getString("detail.index"))
            assertEquals("37", entry.getString("detail.offset"))
        }
    }

    @Test fun movieRendersRealMetadataAndFallbackLabelsAndActualTrailerCallback() = runComposeUiTest {
        val detail = movie().copy(taglineInEnglish = true, overviewInEnglish = true)
        val plays = mutableListOf<Triple<Title, List<Video>, Video>>()
        val entry = WebDetailOwnerTest.entry()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebDetailContent(entry, completed(detail), known, WebSessionState.Ready(null), true, null,
                {}, {}, { _, _, _ -> }, {}, {}, { title, videos, video -> plays += Triple(title, videos, video) })
        }
        onNodeWithTag("web-detail").performScrollToNode(hasText("Fixture tagline (English)"))
        onNodeWithText("Fixture overview (English)").assertExists()
        onNodeWithText("2026 · 2026-10-01 · Drama · 2h 1m").assertExists()
        onNodeWithText("Play trailer").performClick()
        assertEquals(detail.toTitle(), plays.single().first)
        assertEquals(listOf("aB_09-xYz12"), plays.single().second.map { it.key })
        assertEquals("aB_09-xYz12", plays.single().third.key)
        onNodeWithTag("detail-video-invalid").assertDoesNotExist()
        onNodeWithText("Screenplay").assertDoesNotExist()
        onNodeWithText("Producer").assertDoesNotExist()
    }

    @Test fun trailerCallbacksCarryTheFullValidatedVideoListAndTheClickedVideo() = runComposeUiTest {
        val detail = movie().copy(cast = emptyList(), leadCrew = null, recommendations = emptyList(), reviews = emptyList(), facts = emptyList(),
            videos = listOf(Video("aB_09-xYz12", "First trailer", "Trailer", true), Video("invalid", "Bad key", "Clip", false),
                Video("Zz_98-wVu34", "Second clip", "Clip", false), Video("aB_09-xYz12", "Duplicate", "Teaser", false)))
        val plays = mutableListOf<Triple<Title, List<Video>, Video>>()
        val entry = WebDetailOwnerTest.entry()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebDetailContent(entry, completed(detail), LibraryState.Unknown, WebSessionState.Checking, false, null,
                {}, {}, { _, _, _ -> }, {}, {}, { title, videos, video -> plays += Triple(title, videos, video) })
        }
        onNodeWithText("Play trailer").performClick()
        onNodeWithTag("web-detail").performScrollToNode(hasTestTag("detail-video-Zz_98-wVu34"))
        onNodeWithTag("detail-video-Zz_98-wVu34").performClick()
        val validated = detail.webDetailVideos()
        assertEquals(listOf("aB_09-xYz12", "Zz_98-wVu34"), validated.map { it.key })
        assertEquals(listOf(validated, validated), plays.map { it.second })
        assertEquals(listOf(validated[0], validated[1]), plays.map { it.third })
        assertTrue(plays.all { it.first == detail.toTitle() })
    }

    @Test fun knownActionsUseActualKindsUnknownAndPendingRemainDisabled() = runComposeUiTest {
        val detail = movie()
        val actions = mutableListOf<LibraryKind>()
        var account by mutableStateOf(LibraryState.Unknown)
        var session by mutableStateOf<WebSessionState>(WebSessionState.Ready(null))
        val entry = WebDetailOwnerTest.entry()
        setThemedContent(ShellStyle.Web) {
            WebDetailContent(entry, completed(detail), account, session, true, null,
                {}, {}, { kind, _, _ -> actions += kind }, {}, {}, { _, _, _ -> })
        }
        onNodeWithTag("web-detail").performScrollToNode(hasText("Watchlist · unknown"))
        onNodeWithText("Watchlist · unknown").assertIsNotEnabled()
        onNodeWithText("Rating · unknown").assertIsNotEnabled()
        runOnIdle { account = known.copy(pending = setOf(LibraryKind.FAVOURITE)) }
        onNodeWithText("Favourite").assertIsNotEnabled()
        onNodeWithText("In watchlist").performClick()
        onNodeWithText("Rated 7.5").performClick()
        assertEquals(listOf(LibraryKind.WATCHLIST, LibraryKind.RATING), actions)
        runOnIdle { account = known.copy(pending = setOf(LibraryKind.RATING)) }
        onNodeWithText("Rated 7.5").assertIsNotEnabled()
        runOnIdle { session = WebSessionState.Checking }
        onNodeWithText("Watchlist · unknown").assertIsNotEnabled()
        onNodeWithText("Add to watchlist").assertDoesNotExist()
    }

    @Test fun libraryCallbackCapturesRenderedContextAndFullReadyWithoutReadingNewValues() = runComposeUiTest {
        val entry = WebDetailOwnerTest.entry()
        val rendered = completed(movie())
        val ready = WebSessionState.Ready(null)
        val callbacks = mutableListOf<Triple<LibraryKind, DetailReadContext?, WebSessionState.Ready?>>()
        setThemedContent(ShellStyle.Web) {
            WebDetailContent(entry, rendered, known, ready, true, null,
                {}, {}, { kind, context, session -> callbacks += Triple(kind, context, session) }, {}, {}, { _, _, _ -> })
        }
        onNodeWithTag("web-detail").performScrollToNode(hasText("In watchlist"))
        onNodeWithText("In watchlist").performClick()
        assertEquals(Triple(LibraryKind.WATCHLIST, rendered.producer.request, ready), callbacks.single())
    }

    @Test fun rawContentWithoutCompletionMetadataNeverEnablesLibraryActions() = runComposeUiTest {
        val entry = WebDetailOwnerTest.entry()
        setThemedContent(ShellStyle.Web) {
            WebDetailContent(entry, WebDetailReadState(SectionState.Content(movie())), known, WebSessionState.Ready(null), true, null,
                {}, {}, { _, _, _ -> error("Unproven content dispatched") }, {}, {}, { _, _, _ -> })
        }
        onNodeWithTag("web-detail").performScrollToNode(hasText("Watchlist · unknown"))
        onNodeWithText("Watchlist · unknown").assertIsNotEnabled()
    }

    @Test fun castAndLeadCrewEmitActualPersonIdsAndBackReachesItsCallback() = runComposeUiTest {
        val detail = movie().copy(videos = emptyList(), recommendations = emptyList(), facts = emptyList())
        val routes = mutableListOf<WebRoute>()
        var back = 0
        val entry = WebDetailOwnerTest.entry()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebDetailContent(entry, completed(detail), known, WebSessionState.Ready(null), true, null,
                {}, {}, { _, _, _ -> }, { routes += it }, { back++ }, { _, _, _ -> })
        }
        onNodeWithText("← Back").performClick()
        onNodeWithTag("web-detail").performScrollToIndex(2)
        onNodeWithTag("detail-person-42").performClick()
        onNodeWithTag("detail-person-43").performClick()
        assertEquals(1, back)
        assertEquals(listOf<WebRoute>(WebRoute.Person(42), WebRoute.Person(43)), routes)
    }

    @Test fun recommendationEmitsActualMediaAndTitleId() = runComposeUiTest {
        val detail = movie().copy(cast = emptyList(), leadCrew = null, videos = emptyList(), facts = emptyList())
        val routes = mutableListOf<WebRoute>()
        val entry = WebDetailOwnerTest.entry()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebDetailContent(entry, completed(detail), known, WebSessionState.Ready(null), true, null,
                {}, {}, { _, _, _ -> }, { routes += it }, {}, { _, _, _ -> })
        }
        onNodeWithTag("web-detail").performScrollToIndex(2)
        onNodeWithTag("detail-recommendation-movie-81").performClick()
        assertEquals(listOf<WebRoute>(WebRoute.Detail("movie", 81)), routes)
    }

    @Test fun footerEmitsActualLibraryDestinationWithoutLoadedDetail() = runComposeUiTest {
        val routes = mutableListOf<WebRoute>()
        val entry = WebDetailOwnerTest.entry()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebDetailContent(entry, WebDetailReadState(SectionState.Empty), LibraryState.Unknown, WebSessionState.Checking, false, null,
                {}, {}, { _, _, _ -> }, { routes += it }, {}, { _, _, _ -> })
        }
        onNodeWithTag("web-detail").performScrollToIndex(2)
        onNodeWithText("My Library").performClick()
        assertEquals(listOf<WebRoute>(WebRoute.Library()), routes)
    }

    @Test fun tvPhoneLayoutUsesRealSeasonAndCreatorIdsWithoutFakePlayerOrTotals() = runComposeUiTest {
        val detail = TvDetailDto(81, name = "Fixture series", networks = listOf(NetworkDto(9, "Fixture network")),
            createdBy = listOf(CreatorDto(42, "Fixture creator")), seasons = listOf(SeasonSummaryDto(901, 2, "Second season"))).toDetail("US")
        val routes = mutableListOf<WebRoute>()
        val entry = WebDetailOwnerTest.entry(WebRoute.Detail("tv", 81))
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Box(Modifier.width(390.dp)) {
                WebDetailContent(entry, completed(detail), LibraryState.Unknown, WebSessionState.Checking, false, null,
                    {}, {}, { _, _, _ -> }, { routes += it }, {}, { _, _, _ -> error("No trailer exists") })
            }
        }
        onNodeWithText("Fixture series").assertExists()
        onNodeWithText("Play trailer").assertDoesNotExist()
        onNodeWithText("User score").assertDoesNotExist()
        onNodeWithTag("web-detail").performScrollToIndex(2)
        onNodeWithTag("detail-person-42").performClick()
        onNodeWithTag("web-detail").performScrollToIndex(3)
        onNodeWithTag("detail-season-2").performClick()
        assertEquals(listOf(WebRoute.Person(42), WebRoute.Season(81, 2)), routes)
        onNodeWithText("24 episodes").assertDoesNotExist()
    }

    @Test fun loadingErrorNotFoundAndRetainedContentAreSeparate() = runComposeUiTest {
        val entry = WebDetailOwnerTest.entry()
        var state by mutableStateOf(WebDetailReadState())
        var retries = 0
        setThemedContent(ShellStyle.Web) {
            WebDetailContent(entry, state, LibraryState.Unknown, WebSessionState.Checking, false, null,
                {}, { retries++ }, { _, _, _ -> }, {}, {}, { _, _, _ -> })
        }
        onNodeWithText("Loading title…").assertExists()
        runOnIdle { state = WebDetailReadState(SectionState.Error(SectionErrorKind.Offline), retained = movie()) }
        onNodeWithText("Offline. Could not load this title. Last loaded detail is retained, not a fresh result.").assertExists()
        onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
        onNodeWithTag("web-detail").performScrollToNode(hasText("Fixture film"))
        onNodeWithText("Fixture film").assertExists()
        onNodeWithText("Watchlist · unknown").assertIsNotEnabled()
        runOnIdle { state = WebDetailReadState(SectionState.Error(SectionErrorKind.NotAvailable)) }
        onNodeWithTag("web-detail").performScrollToIndex(0)
        onNodeWithText("Title not found or unavailable.").assertExists()
        onNodeWithText("Loading title…").assertDoesNotExist()
        onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun unverifiedEqualContentDoesNotEnableActionsBeforeProducerCompletion() = runComposeUiTest {
        val detail = movie()
        val entry = WebDetailOwnerTest.entry()
        var failed by mutableStateOf(true)
        var recoveries = 0
        var replacementCompleted by mutableStateOf(false)
        setThemedContent(ShellStyle.Web) {
            WebDetailContent(entry, if (replacementCompleted) completed(detail) else WebDetailReadState(SectionState.Content(detail), retained = detail, settingsFailed = failed, freshnessUnverified = true),
                known, WebSessionState.Ready(null), true, null, { recoveries++ }, {}, { _, _, _ -> }, {}, {}, { _, _, _ -> })
        }
        onNodeWithText("Saved preferences could not be read. Retained detail is stale and unverified.").assertExists()
        onNodeWithText("Retry").performClick()
        assertEquals(1, recoveries)
        runOnIdle { failed = false }
        onNodeWithText("Detail replacement is not verified. Retained detail is stale; completion may come from cache.").assertExists()
        onNodeWithTag("web-detail").performScrollToNode(hasText("Watchlist · unknown"))
        onNodeWithText("Watchlist · unknown").assertIsNotEnabled()
        runOnIdle { replacementCompleted = true }
        onNodeWithText("In watchlist").assertIsEnabled()
    }

    @Test fun reviewsAndFactsUseOnlyAvailableMappedDataAndStorageDisablesKnownActions() = runComposeUiTest {
        val detail = MovieDetailDto(7, title = "Reviewed fixture", reviews = PageDto(results = listOf(ReviewDto(id = "review-1", author = "Fixture reviewer", content = "<p>Actual **review**</p>"))), status = "Released").toDetail("US")
        val entry = WebDetailOwnerTest.entry()
        setThemedContent(ShellStyle.Web) {
            WebDetailContent(entry, completed(detail), known, WebSessionState.Ready(null), true, BrowserStorageFailure.Blocked,
                {}, {}, { _, _, _ -> }, {}, {}, { _, _, _ -> })
        }
        onNodeWithTag("web-detail").performScrollToNode(hasText("Watchlist · unknown"))
        onNodeWithText("Watchlist · unknown").assertIsNotEnabled()
        onNodeWithTag("web-detail").performScrollToNode(hasText("Actual review"))
        onNodeWithText("Actual review").assertExists()
        onNodeWithText("Released").assertExists()
        onNodeWithText("Runtime").assertDoesNotExist()
        onNodeWithText("Rating: 0.0").assertDoesNotExist()
    }
}
