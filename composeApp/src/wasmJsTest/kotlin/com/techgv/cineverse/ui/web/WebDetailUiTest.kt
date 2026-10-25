package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WebDetailUiTest {
    private fun movie(): Detail = MovieDetailDto(7, title = "Fixture film", tagline = "Fixture tagline", overview = "Fixture overview",
        releaseDate = "2026-10-01", runtime = 121, genres = listOf(GenreDto(18, "Drama")), voteAverage = 8.1, voteCount = 15,
        credits = CreditsDto(cast = listOf(CastDto(42, name = "Fixture actor", character = "Actual role")), crew = listOf(CrewDto(43, name = "Fixture director", job = "Director"))),
        videos = VideosDto(listOf(VideoDto(key = "invalid", site = "YouTube", type = "Trailer", official = true),
            VideoDto(key = "aB_09-xYz12", name = "Actual trailer", site = "YouTube", type = "Trailer"))),
        recommendations = PageDto(results = listOf(MediaItemDto(81, title = "Related film", mediaType = "movie"))))
        .toDetail("US")
    private val known = LibraryState(true, true, false, 7.5, emptySet())

    @Test fun movieRendersRealMetadataAndFallbackLabelsAndActualTrailerCallback() = runComposeUiTest {
        val detail = movie().copy(taglineInEnglish = true, overviewInEnglish = true)
        val plays = mutableListOf<Pair<Title, Video>>()
        val entry = WebDetailOwnerTest.entry()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebDetailContent(entry, WebDetailReadState(SectionState.Content(detail)), known, WebSessionState.Ready(null), true, null,
                {}, {}, {}, {}, {}, { title, video -> plays += title to video })
        }
        onNodeWithTag("web-detail").performScrollToNode(hasText("Fixture tagline (English)"))
        onNodeWithText("Fixture overview (English)").assertExists()
        onNodeWithText("2026 · 2026-10-01 · Drama · 2h 1m").assertExists()
        onNodeWithText("Play trailer").performClick()
        assertEquals(detail.toTitle(), plays.single().first)
        assertEquals("aB_09-xYz12", plays.single().second.key)
        onNodeWithTag("detail-video-invalid").assertDoesNotExist()
        onNodeWithText("Screenplay").assertDoesNotExist()
        onNodeWithText("Producer").assertDoesNotExist()
    }

    @Test fun knownActionsUseActualKindsUnknownAndPendingRemainDisabled() = runComposeUiTest {
        val detail = movie()
        val actions = mutableListOf<LibraryKind>()
        var account by mutableStateOf(LibraryState.Unknown)
        var session by mutableStateOf<WebSessionState>(WebSessionState.Ready(null))
        val entry = WebDetailOwnerTest.entry()
        setThemedContent(ShellStyle.Web) {
            WebDetailContent(entry, WebDetailReadState(SectionState.Content(detail)), account, session, true, null,
                {}, {}, { actions += it }, {}, {}, { _, _ -> })
        }
        onNodeWithTag("web-detail").performScrollToNode(hasText("Watchlist · unknown"))
        onNodeWithText("Watchlist · unknown").assertIsNotEnabled()
        onNodeWithText("Rating · unknown").assertIsNotEnabled()
        runOnIdle { account = known.copy(pending = setOf(LibraryKind.FAVOURITE)) }
        onNodeWithText("Favourite").assertIsNotEnabled()
        onNodeWithText("In watchlist").performClick()
        onNodeWithText("Rated 7.5").performClick()
        assertEquals(listOf(LibraryKind.WATCHLIST, LibraryKind.RATING), actions)
        runOnIdle { session = WebSessionState.Checking }
        onNodeWithText("Watchlist · unknown").assertIsNotEnabled()
        onNodeWithText("Add to watchlist").assertDoesNotExist()
    }

    @Test fun castAndLeadCrewEmitActualPersonIdsAndBackReachesItsCallback() = runComposeUiTest {
        val detail = movie().copy(videos = emptyList(), recommendations = emptyList(), facts = emptyList())
        val routes = mutableListOf<WebRoute>()
        var back = 0
        val entry = WebDetailOwnerTest.entry()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebDetailContent(entry, WebDetailReadState(SectionState.Content(detail)), known, WebSessionState.Ready(null), true, null,
                {}, {}, {}, { routes += it }, { back++ }, { _, _ -> })
        }
        onNodeWithText("← Back").performClick()
        onNodeWithTag("web-detail").performScrollToIndex(2)
        onNodeWithTag("detail-person-42").performClick()
        onNodeWithTag("detail-person-43").performClick()
        assertEquals(1, back)
        assertEquals(listOf(WebRoute.Person(42), WebRoute.Person(43)), routes)
    }

    @Test fun recommendationEmitsActualMediaAndTitleId() = runComposeUiTest {
        val detail = movie().copy(cast = emptyList(), leadCrew = null, videos = emptyList(), facts = emptyList())
        val routes = mutableListOf<WebRoute>()
        val entry = WebDetailOwnerTest.entry()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebDetailContent(entry, WebDetailReadState(SectionState.Content(detail)), known, WebSessionState.Ready(null), true, null,
                {}, {}, {}, { routes += it }, {}, { _, _ -> })
        }
        onNodeWithTag("web-detail").performScrollToIndex(2)
        onNodeWithTag("detail-recommendation-movie-81").performClick()
        assertEquals(listOf(WebRoute.Detail("movie", 81)), routes)
    }

    @Test fun footerEmitsActualLibraryDestinationWithoutLoadedDetail() = runComposeUiTest {
        val routes = mutableListOf<WebRoute>()
        val entry = WebDetailOwnerTest.entry()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebDetailContent(entry, WebDetailReadState(SectionState.Empty), LibraryState.Unknown, WebSessionState.Checking, false, null,
                {}, {}, {}, { routes += it }, {}, { _, _ -> })
        }
        onNodeWithTag("web-detail").performScrollToIndex(2)
        onNodeWithText("My Library").performClick()
        assertEquals(listOf(WebRoute.Library()), routes)
    }

    @Test fun tvPhoneLayoutUsesRealSeasonAndCreatorIdsWithoutFakePlayerOrTotals() = runComposeUiTest {
        val detail = TvDetailDto(81, name = "Fixture series", networks = listOf(NetworkDto(9, "Fixture network")),
            createdBy = listOf(CreatorDto(42, "Fixture creator")), seasons = listOf(SeasonSummaryDto(901, 2, "Second season"))).toDetail("US")
        val routes = mutableListOf<WebRoute>()
        val entry = WebDetailOwnerTest.entry(WebRoute.Detail("tv", 81))
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Box(Modifier.width(390.dp)) {
                WebDetailContent(entry, WebDetailReadState(SectionState.Content(detail)), LibraryState.Unknown, WebSessionState.Checking, false, null,
                    {}, {}, {}, { routes += it }, {}, { _, _ -> error("No trailer exists") })
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
                {}, { retries++ }, {}, {}, {}, { _, _ -> })
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

    @Test fun settingsFailureAndRecoveredEqualContentNeverClearUnverifiedNoticeOrEnableActions() = runComposeUiTest {
        val detail = movie()
        val entry = WebDetailOwnerTest.entry()
        var failed by mutableStateOf(true)
        var recoveries = 0
        setThemedContent(ShellStyle.Web) {
            WebDetailContent(entry, WebDetailReadState(SectionState.Content(detail), retained = detail, settingsFailed = failed, freshnessUnverified = true),
                known, WebSessionState.Ready(null), true, null, { recoveries++ }, {}, {}, {}, {}, { _, _ -> })
        }
        onNodeWithText("Saved preferences could not be read. Last real detail is frozen and unverified.").assertExists()
        onNodeWithText("Retry").performClick()
        assertEquals(1, recoveries)
        runOnIdle { failed = false }
        onNodeWithText("Read recovery requested. Detail freshness is still unverified; last real detail remains frozen.").assertExists()
        onNodeWithTag("web-detail").performScrollToNode(hasText("Watchlist · unknown"))
        onNodeWithText("Watchlist · unknown").assertIsNotEnabled()
    }

    @Test fun reviewsAndFactsUseOnlyAvailableMappedDataAndStorageDisablesKnownActions() = runComposeUiTest {
        val detail = MovieDetailDto(7, title = "Reviewed fixture", reviews = PageDto(results = listOf(ReviewDto(id = "review-1", author = "Fixture reviewer", content = "<p>Actual **review**</p>"))), status = "Released").toDetail("US")
        val entry = WebDetailOwnerTest.entry()
        setThemedContent(ShellStyle.Web) {
            WebDetailContent(entry, WebDetailReadState(SectionState.Content(detail)), known, WebSessionState.Ready(null), true, BrowserStorageFailure.Blocked,
                {}, {}, {}, {}, {}, { _, _ -> })
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
