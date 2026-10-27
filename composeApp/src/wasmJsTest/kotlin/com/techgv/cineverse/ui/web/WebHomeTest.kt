package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WebHomeTest {
    private val title = Title(MediaRef(MediaType.Movie, 7), "Fixture film", "Public catalog overview", null, null, "2026-01-01", 8.1, 15, emptyList(), MediaType.Movie)
    private fun state() = HomeUiState(hero = Hero.OfTitle(title), rows = HomeRow.entries.associateWith { SectionState.Content(PagedItems(listOf(Hero.OfTitle(title)), 1, false)) })
    private fun saver() = WebNavigation(FakeHistory()).entry.value

    @Test fun heroUsesRealCallbacksKitOrderAndNoUnboundLibraryAction() = runComposeUiTest {
        val routes = mutableListOf<WebRoute>()
        val plays = mutableListOf<Title>()
        var signIns = 0
        val history = saver()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebHomeContent(state(), history, WebSessionState.Ready(null), {}, {}, {}, { plays += it }, { routes += it }, {}, null, { signIns++ })
        }
        onNodeWithText("Public catalog overview").assertExists()
        onNodeWithText("MOVIE · #1 TRENDING THIS WEEK").assertExists()
        // Year only (list items carry no certification or runtime); never the raw ISO date.
        onNodeWithText("2026").assertExists()
        onNodeWithText("2026-01-01", substring = true).assertDoesNotExist()
        val play = onNodeWithText("Play trailer").getBoundsInRoot()
        val info = onNodeWithText("More info").getBoundsInRoot()
        assertTrue(play.left < info.left, "Kit order: Play trailer before More info")
        onNodeWithText("Play trailer").performClick()
        onNodeWithText("More info").performClick()
        assertEquals(listOf(title), plays)
        assertEquals(listOf<WebRoute>(WebRoute.Detail("movie", 7)), routes)
        // Home has no library binding, so neither a watchlist CTA nor a stand-in library shortcut is shown.
        onNodeWithText("Add to watchlist").assertDoesNotExist()
        onNodeWithText("Open library").assertDoesNotExist()
        onNodeWithTag("web-home").performScrollToNode(hasText("Sign in with TMDB"))
        onNodeWithText("Sign in with TMDB").performClick()
        assertEquals(1, signIns)
    }

    @Test fun heroPicksShowTheSelectionRingWithoutAnAutoAdvanceBarAndStillAdvanceAfterEightSeconds() = runComposeUiTest {
        val second = title.copy(ref = MediaRef(MediaType.Movie, 8), title = "Second film")
        val picks = PagedItems(listOf(Hero.OfTitle(title), Hero.OfTitle(second)), 1, false)
        val selected = mutableListOf<Hero>()
        val history = saver()
        mainClock.autoAdvance = false
        setThemedContent(ShellStyle.Web) {
            Box(Modifier.requiredSize(1440.dp, 900.dp)) {
                WebHomeContent(state().copy(rows = state().rows + (HomeRow.Trending to SectionState.Content(picks))), history, WebSessionState.Ready(null),
                    {}, { selected += it }, {}, {}, {}, {}, null, {})
            }
        }
        val pick = onNode(isSelected() and hasText("Second film").not() and hasClickAction()).getBoundsInRoot()
        val name = onNode(hasText("Fixture film") and hasAnyAncestor(isSelected()), useUnmergedTree = true).getBoundsInRoot()
        // The shown pick ends at its title: no 3dp progress bar below it.
        assertTrue(pick.bottom - name.bottom < 0.5.dp, "pick ${pick.bottom} vs title ${name.bottom}")
        assertTrue(selected.isEmpty())
        mainClock.advanceTimeBy(8_100)
        assertEquals(listOf<Hero>(Hero.OfTitle(second)), selected)
    }

    @Test fun titleKnownToHaveNoTrailerHidesPlayTrailerAndKeepsMoreInfo() = runComposeUiTest {
        val history = saver()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebHomeContent(state(), history, WebSessionState.Ready(null), {}, {}, {}, {}, {}, {}, null, {}, noTrailer = setOf(title.ref))
        }
        onNodeWithText("Play trailer").assertDoesNotExist()
        onNodeWithText("More info").assertExists()
    }

    @Test fun trailerSearchInProgressDisablesPlayTrailer() = runComposeUiTest {
        val history = saver()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebHomeContent(state().copy(trailerLoading = title.ref), history, WebSessionState.Ready(null), {}, {}, {}, {}, {}, {}, null, {})
        }
        onNodeWithText("Finding trailer…").assertIsNotEnabled()
    }

    @Test fun trendingSelectionRetryAndCardNavigationReachTheirCallbacks() = runComposeUiTest {
        var trend: TrendingWindow? = null
        var retry: HomeRow? = null
        var route: WebRoute? = null
        val history = saver()
        history.putInt("page.index", 1)
        val initial = state().copy(rows = state().rows + (HomeRow.Trending to SectionState.Error(SectionErrorKind.Offline)))
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebHomeContent(initial, history, WebSessionState.Checking, { trend = it }, {}, { retry = it }, {}, { route = it }, {}, null, {})
        }
        onNodeWithText("Today").assertIsDisplayed().performClick()
        onNodeWithText("Retry").assertIsDisplayed().performClick()
        assertEquals(TrendingWindow.Day, trend)
        assertEquals(HomeRow.Trending, retry)
        onNodeWithTag("web-home").performScrollToIndex(2)
        onNodeWithTag("home-popularMovies-movie:7").assertIsDisplayed().performClick()
        assertEquals(WebRoute.Detail("movie", 7), route)
    }

    @Test fun emptyRowsAndLoadingAreNotNetworkErrors() = runComposeUiTest {
        val history = saver()
        val initial = state().copy(hero = null, rows = state().rows + (HomeRow.Trending to SectionState.Empty))
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebHomeContent(initial, history, WebSessionState.Ready(null), {}, {}, {}, {}, {}, {}, null, {})
        }
        onNodeWithText("No trending titles available.").assertExists()
        onNodeWithTag("web-home").assert(hasContentDescription("Catalog status: trending=empty; popularMovies=content; popularTv=content; nowPlaying=content; upcoming=content; popularPeople=content; topRatedMovies=content"))
        onNodeWithTag("web-home").performScrollToNode(hasText("No titles yet"))
        onNodeWithText("There are no results for this section.").assertExists()
        onNodeWithText("Offline", substring = true).assertDoesNotExist()
    }

    @Test fun failedAndCheckingSessionNeverRenderGuestSignInAndExplicitRetryIsExposed() = runComposeUiTest {
        var session by mutableStateOf<WebSessionState>(WebSessionState.Checking)
        var retries = 0
        setThemedContent(ShellStyle.Web) {
            Column {
                Box(Modifier.width(390.dp)) { WebHeader(WebRoute.Home, session, remember { FocusRequester() }, { _, _ -> }) }
                WebStatus(session, null, { retries++; session = WebSessionState.Ready(null) }, {})
            }
        }
        onNodeWithContentDescription("Menu").assertExists()
        onNodeWithText("▾", substring = true).assertDoesNotExist()
        onNodeWithText("Sign in").assertDoesNotExist()
        runOnIdle { session = WebSessionState.Failed(TmdbError.Network) }
        onNodeWithText("Sign in").assertDoesNotExist()
        onNodeWithText("Session verification failed. Account actions are unavailable.").assertExists()
        onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
        onNodeWithText("Sign in").assertExists()
    }

    @Test fun typedStorageFailureRetryDoesNotRemoveLastGoodPublicData() = runComposeUiTest {
        var failure by mutableStateOf<BrowserStorageFailure?>(BrowserStorageFailure.Quota)
        var retries = 0
        setThemedContent(ShellStyle.Web) {
            Column {
                WebStatus(WebSessionState.Ready(null), failure, {}, { retries++; failure = null })
                androidx.compose.material3.Text("Last good catalog")
            }
        }
        onNodeWithText("Last reported storage error: quota exhausted. Retry reads; writes are not replayed.").assertExists()
        onNodeWithText("Last good catalog").assertExists()
        onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
        onNodeWithText("Last good catalog").assertExists()
        onNodeWithText("Last reported storage error", substring = true).assertDoesNotExist()
    }

    @Test fun internalPlayerAndInvalidRoutesCannotPretendToPlayOrMutate() = runComposeUiTest {
        var route by mutableStateOf<WebRoute>(WebRoute.Trailer("aB_09-xYz12", "Fixture film"))
        setThemedContent(ShellStyle.Web) { WebPlaceholder(route) { next, _ -> route = next } }
        onNodeWithText("Open this trailer from its title page. No video is playing.").assertExists()
        runOnIdle { route = WebRoute.NotFound }
        onNodeWithText("This URL is unknown or invalid. No account operation was performed.").assertExists()
    }

    @Test fun settingsAndGenreDiagnosticsOfferExplicitRecoveryWithoutReplacingContent() = runComposeUiTest {
        val history = saver()
        var recoveries = 0
        val failed = state().copy(settingsError = SectionErrorKind.Generic, genresError = SectionErrorKind.Generic)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebHomeContent(failed, history, WebSessionState.Ready(null), {}, {}, {}, {}, {}, { recoveries++ }, null, {})
        }
        onNodeWithText("Saved preferences could not be observed. Last loaded values are retained.").assertExists()
        onNodeWithText("Genre names could not be read. Loaded content is retained.").assertExists()
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("home-settings-recovery"))).performClick()
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("home-genres-recovery"))).performClick()
        assertEquals(2, recoveries)
        onNodeWithTag("web-home").performScrollToIndex(2)
        onNodeWithText("Public catalog overview").assertExists()
    }

    @Test fun retainedRowErrorsKeepCardsAndRespectTypedRetryability() = runComposeUiTest {
        val history = saver().apply { putInt("page.index", 1) }
        var error by mutableStateOf(SectionErrorKind.Offline)
        var retried: HomeRow? = null
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            val retained = state().copy(rows = state().rows + (HomeRow.Trending to SectionState.Content(
                PagedItems(listOf(Hero.OfTitle(title)), 1, false, moreError = error))))
            WebHomeContent(retained, history, WebSessionState.Ready(null), {}, {}, { retried = it }, {}, {}, {}, null, {})
        }
        onNodeWithTag("home-trending-movie:7").assertExists()
        onNodeWithText("Offline. Check your connection. Loaded titles are retained.").assertExists()
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("home-trending-recovery"))).assertIsDisplayed().performClick()
        assertEquals(HomeRow.Trending, retried)
        runOnIdle { error = SectionErrorKind.AppCredential }
        onNodeWithTag("home-trending-movie:7").assertExists()
        onNodeWithText("Catalog service configuration is unavailable. Loaded titles are retained.").assertExists()
        onNodeWithTag("home-trending-recovery").assertDoesNotExist()
    }

}
