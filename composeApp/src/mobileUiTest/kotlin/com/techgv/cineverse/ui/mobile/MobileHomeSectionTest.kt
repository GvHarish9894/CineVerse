package com.techgv.cineverse.ui.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.setThemedContent
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MobileHomeSectionTest {
    private val title = Title(MediaRef(MediaType.Movie, 5), "Fixture title", "", null, null, null, 0.0, 0, emptyList(), MediaType.Movie)

    @Test fun loadingAndEmptyAreNotErrors() = runComposeUiTest {
        setThemedContent { HomeSection(HomeRow.Trending, SectionState.Loading, "https://image.tmdb.org/t/p/", MobileNavigator {}, {}, {}) }
        onAllNodesWithContentDescription("Loading").assertCountEquals(4)
        onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun emptyIsExplicit() = runComposeUiTest {
        setThemedContent { HomeSection(HomeRow.Trending, SectionState.Empty, "", MobileNavigator {}, {}, {}) }
        onNodeWithText("Nothing here yet.").assertIsDisplayed()
    }

    @Test fun offlineRetryAndCredentialFailureAreDifferent() = runComposeUiTest {
        var retries = 0
        setThemedContent { HomeSection(HomeRow.Trending, SectionState.Error(SectionErrorKind.Offline), "", MobileNavigator {}, { retries++ }, {}) }
        onNodeWithText("You're offline. Check your connection.").assertIsDisplayed()
        onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test fun pagingErrorKeepsCardsAndRetriesOnlyOnExplicitPress() = runComposeUiTest {
        var more = 0
        var destination: MobileDestination? = null
        val content = PagedItems(listOf(Hero.OfTitle(title)), 1, true, moreError = SectionErrorKind.Offline)
        setThemedContent { HomeSection(HomeRow.Trending, SectionState.Content(content), "", MobileNavigator { destination = it }, {}, { more++ }) }
        onAllNodesWithText("Fixture title")[0].performClick()
        assertEquals(MobileDestination.Detail(title.ref, title.posterPath, title.title), destination)
        assertEquals(0, more)
        onNodeWithText("Retry").performClick()
        assertEquals(1, more)
    }

    @Test fun appCredentialFailureDoesNotOfferBlindRetry() = runComposeUiTest {
        setThemedContent { HomeSection(HomeRow.Trending, SectionState.Error(SectionErrorKind.AppCredential), "", MobileNavigator {}, {}, {}) }
        onNodeWithText("Catalog configuration unavailable.").assertIsDisplayed()
        onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun seeAllCarriesCurrentTrendingWindow() = runComposeUiTest {
        val trend = androidx.compose.runtime.mutableStateOf(com.techgv.cineverse.data.tmdb.TrendingWindow.Day)
        var destination: MobileDestination? = null
        setThemedContent { HomeSection(HomeRow.Trending, SectionState.Content(PagedItems(listOf(Hero.OfTitle(title)), 1, false)), "",
            MobileNavigator { destination = it }, {}, {}, trend.value) }
        onNodeWithText("See all").performClick()
        assertEquals(MobileDestination.Browse(HomeRow.Trending, com.techgv.cineverse.data.tmdb.TrendingWindow.Day), destination)
        runOnIdle { trend.value = com.techgv.cineverse.data.tmdb.TrendingWindow.Week }
        onNodeWithText("See all").performClick()
        assertEquals(MobileDestination.Browse(HomeRow.Trending, com.techgv.cineverse.data.tmdb.TrendingWindow.Week), destination)
    }

    @Test fun personCardHandsOffAnIdNotSampleNavigation() = runComposeUiTest {
        var destination: MobileDestination? = null
        val person = Hero.OfPerson(Person(9, "Fixture person", null, "Actor", emptyList()))
        setThemedContent { HomeSection(HomeRow.PopularPeople, SectionState.Content(PagedItems(listOf(person), 1, false)), "",
            MobileNavigator { destination = it }, {}, {}) }
        onNodeWithText("Fixture person").performClick()
        assertEquals(MobileDestination.Person(9), destination)
    }
}
