package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

    @Test fun heroUsesRealCallbacksAndHonestNavigationOnlyLibraryAction() = runComposeUiTest {
        val routes = mutableListOf<WebRoute>()
        val plays = mutableListOf<Title>()
        val history = saver()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebHomeContent(state(), history, WebSessionState.Ready(null), {}, {}, {}, { plays += it }, { routes += it })
        }
        onNodeWithText("Public catalog overview").assertExists()
        onNodeWithText("Find a trailer").performClick()
        onNodeWithText("Open library").performClick()
        onNodeWithText("More info").performClick()
        assertEquals(listOf(title), plays)
        assertEquals(listOf(WebRoute.Library(), WebRoute.Detail("movie", 7)), routes)
        onNodeWithText("Add to watchlist").assertDoesNotExist()
    }

    @Test fun trendingSelectionRetryAndCardNavigationReachTheirCallbacks() = runComposeUiTest {
        var trend: TrendingWindow? = null
        var retry: HomeRow? = null
        var route: WebRoute? = null
        val history = saver()
        history.putInt("page.index", 1)
        val initial = state().copy(rows = state().rows + (HomeRow.Trending to SectionState.Error(SectionErrorKind.Offline)))
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebHomeContent(initial, history, WebSessionState.Checking, { trend = it }, {}, { retry = it }, {}, { route = it })
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
            WebHomeContent(initial, history, WebSessionState.Ready(null), {}, {}, {}, {}, {})
        }
        onNodeWithText("No trending titles available.").assertExists()
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
        onNodeWithText("Menu ▾").assertExists()
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
        onNodeWithText("Browser storage is full. Saved data is retained; writes are not replayed.").assertExists()
        onNodeWithText("Last good catalog").assertExists()
        onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
        onNodeWithText("Last good catalog").assertExists()
        onNodeWithText("Browser storage is full", substring = true).assertDoesNotExist()
    }

    @Test fun internalPlayerAndInvalidRoutesCannotPretendToPlayOrMutate() = runComposeUiTest {
        var route by mutableStateOf<WebRoute>(WebRoute.Trailer("aB_09-xYz12", "Fixture film"))
        setThemedContent(ShellStyle.Web) { WebPlaceholder(route) { next, _ -> route = next } }
        onNodeWithText("Playback is not implemented in this preview. No video is playing.").assertExists()
        runOnIdle { route = WebRoute.NotFound }
        onNodeWithText("This URL is unknown or invalid. No account operation was performed.").assertExists()
    }
}
