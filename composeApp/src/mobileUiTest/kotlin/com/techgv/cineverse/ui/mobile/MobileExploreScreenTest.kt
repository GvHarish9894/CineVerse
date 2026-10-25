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
        onNodeWithText("Internal placeholder · movie 101").assertIsDisplayed()
        onNodeWithText("Back").performClick()
        onNodeWithText("Movies · Upcoming").assertIsDisplayed()
        onNodeWithText("TV shows").performClick()
        onNodeWithText("On TV").performClick()
        onNodeWithText("TV shows · On TV").assertIsDisplayed()
        onNodeWithText("People").performClick()
        onNodeWithText("Popular people").assertIsDisplayed()
        onNodeWithText("people-en-US 1").performClick()
        onNodeWithText("Internal placeholder · Person 101").assertIsDisplayed()
        onNodeWithText("Back").performClick()
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

    @Test fun typeSwitchRestoresDeepScrollAndKeepsTheSameLoadedHolder() = runComposeUiTest {
        lateinit var model: MobileExploreViewModel
        setThemedContent {
            val scope = rememberCoroutineScope()
            val fixture = remember { MobileFixture(scope) }
            model = remember { MobileExploreViewModel(fixture.graph, androidx.lifecycle.SavedStateHandle(), scope = scope) }
            DisposableEffect(Unit) { onDispose { fixture.graph.close() } }
            MobileExploreScreen(fixture.graph, model, 0, MobileNavigator {})
        }
        onNodeWithTag("mobile-explore").performScrollToNode(hasText("popular-en-US-IN 10"))
        val movies = model.currentPage.value
        runOnIdle { model.selectType(MobileExploreType.People) }
        onNodeWithText("Popular people").assertIsDisplayed()
        runOnIdle { model.selectType(MobileExploreType.Movies) }
        onAllNodesWithText("popular-en-US-IN 10")[0].assertIsDisplayed()
        runOnIdle { assertSame(movies, model.currentPage.value) }
    }

    @Test fun mixedTrendingKeepsExactWindowAndDoesNotClaimAMediaType() = runComposeUiTest {
        var selected: BrowseSelection? = null
        setThemedContent {
            MobileExploreContent(ProductBrowseUiState(BrowseSelection.Trending(TrendingWindow.Day), SectionState.Empty, settingsKnown = true), "",
                MobileNavigator {}, {}, { selected = it }, {}, {}, {}, {})
        }
        onNodeWithText("Trending · Today · Movies and TV").assertIsDisplayed()
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
