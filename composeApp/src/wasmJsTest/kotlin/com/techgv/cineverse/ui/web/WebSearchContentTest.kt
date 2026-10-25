package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.data.tmdb.SearchFacet
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WebSearchContentTest {
    private val title = Title(MediaRef(MediaType.Movie, 7), "Fixture film", "Public overview", null, null, "2026-01-01", 8.1, 20, emptyList(), MediaType.Movie)
    private fun items() = (1..30).map { Hero.OfTitle(title.copy(ref = MediaRef(MediaType.Movie, it))) }

    @Composable
    private fun Content(entry: WebEntry, state: ProductSearchUiState, more: () -> Unit = {}, retry: () -> Unit = {}, navigate: (WebRoute) -> Unit = {}) {
        WebSearchContent(entry, state, state.query, false, {}, {}, {}, {}, {}, more, retry, {}, null, navigate)
    }

    @Test fun narrowRowsHaveLoadedOnlyActiveCountsAndUnknownIsNotEmptySuccess() = runComposeUiTest {
        val nav = WebNavigation(FakeHistory("/search?q=film&facet=movie"))
        var state by mutableStateOf(ProductSearchUiState("film", SearchFacet.Movie))
        var destination: WebRoute? = null
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Box(Modifier.width(390.dp)) { Content(nav.entry.value, state, navigate = { destination = it }) }
        }
        onNodeWithText("Movies · unknown").assertExists()
        onNodeWithText("Waiting for saved preferences…").assertExists()
        onNodeWithText("No results").assertDoesNotExist()
        runOnIdle { state = state.copy(settingsKnown = true, results = SectionState.Content(PagedItems(listOf(Hero.OfTitle(title)), 1, false))) }
        onNodeWithText("Movies · 1 loaded").assertExists()
        onNodeWithText("All · unknown").assertExists()
        onNodeWithTag("search-movie:7").performClick()
        assertEquals(WebRoute.Detail("movie", 7), destination)
        nav.close()
    }

    @Test fun savedPagesAndScrollRestoreBoundedlyAndWhitespaceKeepsPositionButFacetResets() = runComposeUiTest {
        val history = FakeHistory("/search?q=film&facet=movie")
        val nav = WebNavigation(history)
        val entry = nav.entry.value
        entry.putInt("search.page", 2); entry.putInt("search.index", 5); entry.putInt("search.offset", 20)
        var state by mutableStateOf(ProductSearchUiState("film", SearchFacet.Movie, SectionState.Content(PagedItems(items(), 1, true))))
        var more = 0
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) { Content(entry, state, more = { more++ }) }
        runOnIdle { assertEquals(1, more) }
        runOnIdle { state = state.copy(results = SectionState.Content(PagedItems(items(), 1, true, moreError = SectionErrorKind.Offline))) }
        runOnIdle { assertEquals(1, more) }
        runOnIdle { state = state.copy(results = SectionState.Content(PagedItems(items(), 2, true))) }
        waitUntil { entry.getInt("search.index") == 5 && entry.getInt("search.offset") == 20 }
        runOnIdle {
            nav.navigate(WebRoute.Search(" film ", "movie"), true)
            state = state.copy(query = " film ")
        }
        runOnIdle { assertEquals(5, entry.getInt("search.index")); assertEquals(20, entry.getInt("search.offset")); assertEquals(1, more) }
        runOnIdle {
            nav.navigate(WebRoute.Search(" film ", "tv"), true)
            state = state.copy(facet = SearchFacet.Tv, results = SectionState.Content(PagedItems(items(), 1, true)))
        }
        waitUntil { entry.getInt("search.index") == 0 && entry.getInt("search.page") == 1 }
        assertEquals(1, more)
        nav.close()
    }

    @Test fun initialReadErrorAndEmptyRemainDistinctAndOnlyExplicitRetryReloads() = runComposeUiTest {
        val nav = WebNavigation(FakeHistory("/search?q=film"))
        var state by mutableStateOf(ProductSearchUiState("film", SearchFacet.Multi, SectionState.Error(SectionErrorKind.Offline)))
        var retries = 0
        var more = 0
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) { Content(nav.entry.value, state, { more++ }, { retries++ }) }
        onNodeWithText("Offline. Check your connection.").assertExists()
        onNodeWithText("Retry").performClick()
        assertEquals(1, retries); assertEquals(0, more)
        runOnIdle { state = state.copy(results = SectionState.Empty) }
        onNodeWithText("All · 0 loaded").assertExists()
        onNodeWithText("Movies · unknown").assertExists()
        onNodeWithText("No results").assertExists()
        onNodeWithText("Refresh results").performClick()
        assertEquals(2, retries); assertEquals(0, more)
        nav.close()
    }
}
