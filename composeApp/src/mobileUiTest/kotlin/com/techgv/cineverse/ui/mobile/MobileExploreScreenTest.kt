package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.data.tmdb.MovieList
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.setThemedContent
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MobileExploreScreenTest {
    private val title = Title(MediaRef(MediaType.Movie, 5), "Fixture title", "", null, null, null, 0.0, 0, emptyList(), MediaType.Movie)

    @Test fun switchesRealTypesListsAndNavigatesRealTitleAndPersonIds() = runComposeUiTest {
        setContent { MobileTestRoot() }
        onNode(hasText("Explore") and hasClickAction()).performClick()
        onNodeWithText("Movies · Popular").assertIsDisplayed()
        onNodeWithText("Upcoming").performClick()
        onNodeWithText("Movies · Upcoming").assertIsDisplayed()
        onAllNodesWithText("upcoming-en-US-IN 1")[0].performClick()
        onNodeWithTag("mobile-detail").assertExists()
        onNodeWithText("Detail title 101").assertIsDisplayed()
        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("Movies · Upcoming").assertIsDisplayed()
        onNodeWithText("TV shows").performClick()
        onNodeWithText("On TV").performClick()
        onNodeWithText("TV shows · On TV").assertIsDisplayed()
        onNodeWithText("People").performClick()
        onNodeWithText("Popular people").assertIsDisplayed()
        // Viewport paging loads page 2, whose fixture person 201 reuses the label; the first node is person 101.
        onAllNodesWithText("people-en-US 1")[0].performClick()
        onNodeWithTag("mobile-person").assertExists()
        onNodeWithText("Person 101 en-US").assertIsDisplayed()
        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("Movies").performClick()
        onNodeWithText("Movies · Upcoming").assertIsDisplayed()
    }

    @Test fun typeAndBottomTabSwitchKeepScrollAndReselectReturnsToTop() = runComposeUiTest {
        setContent { MobileTestRoot() }
        onNode(hasText("Explore") and hasClickAction()).performClick()
        onNodeWithTag("mobile-explore").performScrollToNode(hasText("popular-en-US-IN 10"))
        onNodeWithText("Search", useUnmergedTree = true).performClick()
        onNode(hasText("Explore") and hasClickAction()).performClick()
        onAllNodesWithText("popular-en-US-IN 10")[0].assertIsDisplayed()
        onNode(hasText("Explore") and hasClickAction()).performClick()
        onNodeWithText("Movies · Popular").assertIsDisplayed()
        onNodeWithText("TV shows").performClick()
        onNodeWithText("People").performClick()
        onNodeWithText("Movies").performClick()
        onNodeWithText("Movies · Popular").assertIsDisplayed()
    }

    @Test fun reselectResetsOnlyActiveTvWhileMoviesKeepsDeepScrollAndLoadedHolder() = runComposeUiTest {
        lateinit var model: MobileExploreViewModel
        val reselectExplore = mutableIntStateOf(0)
        setThemedContent {
            val scope = rememberCoroutineScope()
            val fixture = remember { MobileFixture(scope) }
            model = remember { MobileExploreViewModel(fixture.graph, androidx.lifecycle.SavedStateHandle(), scope = scope) }
            DisposableEffect(Unit) { onDispose { fixture.graph.close() } }
            MobileExploreScreen(fixture.graph, model, reselectExplore.intValue, MobileNavigator {}, onInvalidBack = {})
        }
        onNodeWithTag("mobile-explore").performScrollToNode(hasText("popular-en-US-IN 10"))
        val movies = model.currentPage.value
        runOnIdle { model.selectType(MobileExploreType.Tv) }
        onNodeWithTag("mobile-explore").performScrollToNode(hasText("tv-en-US 10"))
        onAllNodesWithText("tv-en-US 10")[0].assertIsDisplayed()
        runOnIdle { reselectExplore.intValue++ }
        onNodeWithText("TV shows · Popular").assertIsDisplayed()
        runOnIdle { model.selectType(MobileExploreType.Movies) }
        onAllNodesWithText("popular-en-US-IN 10")[0].assertIsDisplayed()
        runOnIdle { assertSame(movies, model.currentPage.value) }
    }

    @Test fun malformedRouteAndSavedStateShowRejectionAndBackWithoutDefaultContent() = runComposeUiTest {
        val case = mutableIntStateOf(0)
        var back = 0
        lateinit var model: MobileExploreViewModel
        lateinit var fixture: MobileFixture
        lateinit var saved: androidx.lifecycle.SavedStateHandle
        val cases = listOf(
            MobileBrowseRoute("invalid") to emptyMap<String, Any>(),
            MobileBrowseRoute("Trending", "invalid") to emptyMap(),
            MobileBrowseRoute("PopularMovies") to mapOf("mobileExplore.type" to "invalid"),
            MobileBrowseRoute("PopularMovies") to mapOf("mobileExplore.Movies.productBrowse.source" to "Movies",
                "mobileExplore.Movies.productBrowse.value" to "invalid")
        )
        setThemedContent {
            key(case.intValue) {
                val scope = rememberCoroutineScope()
                val currentFixture = remember { MobileFixture(scope) }
                val currentSaved = remember { androidx.lifecycle.SavedStateHandle(cases[case.intValue].second) }
                val currentModel = remember { MobileExploreViewModel(currentFixture.graph, currentSaved, cases[case.intValue].first.selection(), scope) }
                SideEffect { fixture = currentFixture; saved = currentSaved; model = currentModel }
                DisposableEffect(Unit) { onDispose { currentFixture.graph.close() } }
                MobileExploreScreen(currentFixture.graph, currentModel, 0, MobileNavigator {}, onInvalidBack = { back++ })
            }
        }
        cases.indices.forEach { index ->
            runOnIdle { case.intValue = index }
            onNodeWithText("Invalid Explore state. Go back to leave it unchanged.").assertIsDisplayed()
            onNodeWithTag("mobile-explore").assertDoesNotExist()
            onNodeWithText("Back").performClick()
            runOnIdle {
                assertNull(model.currentPage.value)
                assertTrue(fixture.api.calls.isEmpty())
                assertEquals(cases[index].second, saved.keys().associateWith { saved.get<Any?>(it) })
                assertEquals(index + 1, back)
            }
        }
    }

    @Test fun mixedTrendingKeepsExactWindowAndDoesNotClaimAMediaType() = runComposeUiTest {
        var selected: BrowseSelection? = null
        setThemedContent {
            MobileExploreContent(ProductBrowseUiState(BrowseSelection.Trending(TrendingWindow.Day), SectionState.Empty, settingsKnown = true), "",
                MobileNavigator {}, {}, { selected = it }, {}, {}, {}, {})
        }
        onNodeWithText("Trending · Today · Movies, TV and People").assertIsDisplayed()
        onNodeWithText("Movies").assertIsNotSelected()
        onNodeWithText("TV shows").assertIsNotSelected()
        onNodeWithText("This week").performClick()
        assertEquals(BrowseSelection.Trending(TrendingWindow.Week), selected)
    }

    @Test fun paginationErrorRetainsContentAndOnlyExplicitRetryCanLoad() = runComposeUiTest {
        var more = 0
        var retries = 0
        var destination: MobileDestination? = null
        setThemedContent {
            MobileExploreContent(ProductBrowseUiState(BrowseSelection.Movies(MovieList.Popular),
                SectionState.Content(PagedItems(listOf(Hero.OfTitle(title)), 1, true, moreError = SectionErrorKind.Offline)), settingsKnown = true), "",
                MobileNavigator { destination = it }, {}, {}, { retries++ }, {}, {}, { more++ })
        }
        onAllNodesWithText("Fixture title")[0].performClick()
        assertEquals(MobileDestination.Detail(title.ref), destination)
        onNodeWithTag("mobile-explore").performScrollToNode(hasText("Retry"))
        assertEquals(0, more)
        onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
        assertEquals(0, more)
    }

    @Test fun unknownSettingsAndGenresHaveIndependentExplicitRecovery() = runComposeUiTest {
        var settings = 0
        var genres = 0
        setThemedContent {
            MobileExploreContent(ProductBrowseUiState(BrowseSelection.Movies(MovieList.Popular), SectionState.Empty,
                genresError = SectionErrorKind.Generic), "", MobileNavigator {}, {}, {}, {}, { settings++ }, { genres++ }, {}, settingsFailed = true)
        }
        onNodeWithText("Saved preferences unavailable; content preferences are unknown.").assertIsDisplayed()
        onNodeWithTag("explore-settings-recovery").onChildren().filter(hasText("Retry")).onFirst().performClick()
        onNodeWithTag("mobile-explore").performScrollToNode(hasText("Genre names unavailable; keeping loaded content."))
        onNodeWithTag("explore-genres-recovery").onChildren().filter(hasText("Retry")).onFirst().performClick()
        assertEquals(1, settings)
        assertEquals(1, genres)
    }
}
