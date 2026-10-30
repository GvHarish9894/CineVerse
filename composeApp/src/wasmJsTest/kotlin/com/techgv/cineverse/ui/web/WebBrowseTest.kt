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
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WebBrowseTest {
    private val title = Title(MediaRef(MediaType.Movie, 7), "Loaded film", "Overview", null, null, "2026-01-01", 8.1, 15, emptyList(), MediaType.Movie)
    private fun entry(url: String) = WebNavigation(FakeHistory(url)).entry.value
    private fun state(selection: BrowseSelection = BrowseSelection.Movies(MovieList.Upcoming)) = ProductBrowseUiState(selection,
        SectionState.Content(PagedItems(listOf(Hero.OfTitle(title)), 1, true)), settingsKnown = true, genres = mapOf(28 to "Action", 18 to "Drama"))

    @Test fun curatedGridShowsLoadedCountRealIdentifierAndNoPretendAction() = runComposeUiTest {
        var destination: WebRoute? = null
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebBrowseContent(entry("/movies?list=upcoming"), state(), { destination = it }, {}, {}, {}, {}, null)
        }
        onNodeWithText("Movies").assertExists()
        onNodeWithText("What people are watching on TMDB this week").assertExists()
        onNodeWithText("Upcoming").assertIsSelected()
        // One page read and more exist: the loaded count is never presented as a total.
        onNodeWithTag("browse-count").assert(hasText("1+ titles"))
        onNodeWithText("loaded", substring = true).assertDoesNotExist()
        onNodeWithText("Curated TMDB list", substring = true).assertDoesNotExist()
        onNodeWithTag("browse-movie:7").performClick()
        assertEquals(WebRoute.Detail("movie", 7), destination)
        onNodeWithText("Add to watchlist").assertDoesNotExist()
    }

    @Test fun narrowFiltersSwitchToDiscoverAndCuratedChipClearsFilters() = runComposeUiTest {
        var next: WebRoute? = null
        var selection by mutableStateOf<BrowseSelection>(BrowseSelection.Movies(MovieList.Upcoming))
        var current by mutableStateOf(entry("/movies?list=upcoming"))
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Box(Modifier.width(390.dp)) {
                WebBrowseContent(current, state(selection), { next = it; current = entry(it.url()); selection = it.browseSelection() }, {}, {}, {}, {}, null)
            }
        }
        onNodeWithText("Action").performClick()
        assertEquals(WebRoute.Browse("movie", "discover", genres = listOf(28)), next)
        onNodeWithText("Action").assertIsSelected()
        onNodeWithText("Upcoming").assertIsNotSelected()
        onNodeWithTag("browse-count").assert(hasText("1+ titles · filtered by Action"))
        onNodeWithText("Clear genres").performClick()
        assertEquals(WebRoute.Browse("movie", "discover"), next)
        onNodeWithText("Upcoming").performClick()
        assertEquals(WebRoute.Browse("movie", "upcoming"), next)
        onNodeWithText("Upcoming").assertIsSelected()
        onNodeWithText("Clear genres").assertDoesNotExist()
    }

    @Test fun sortPopupUsesMediaSpecificDiscoverMappingEvenForPopularity() = runComposeUiTest {
        var next: WebRoute? = null
        val current = entry("/tv?list=on_the_air")
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebBrowseContent(current, state(BrowseSelection.Tv(TvList.OnTheAir)), { next = it }, {}, {}, {}, {}, null)
        }
        onNodeWithText("SORT").assertExists()
        onNodeWithTag("browse-sort").assert(hasText("Popularity"))
        onNodeWithText("▾", substring = true).assertDoesNotExist()
        onNodeWithTag("browse-sort").performClick()
        onNode(hasText("Release date") and hasAnyAncestor(isPopup())).performClick()
        assertEquals(WebRoute.Browse("tv", "discover", "first_air_date.desc"), next)
        onNodeWithTag("browse-sort").performClick()
        onNode(hasText("Popularity") and hasAnyAncestor(isPopup())).performClick()
        assertEquals(WebRoute.Browse("tv", "discover"), next)
    }

    @Test fun peopleUsePortraitKnownForAndActualPersonIdWithoutFilters() = runComposeUiTest {
        val person = Hero.OfPerson(Person(42, "Fixture person", null, "Acting", listOf(title)))
        val people = ProductBrowseUiState(BrowseSelection.People, SectionState.Content(PagedItems(listOf(person), 1, false)))
        var next: WebRoute? = null
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebBrowseContent(entry("/people"), people, { next = it }, {}, {}, {}, {}, null)
        }
        onNodeWithText("Popular people").assertExists()
        onNodeWithText("Loaded film").assertExists()
        onNodeWithText("SORT").assertDoesNotExist()
        onNodeWithText("GENRES").assertDoesNotExist()
        onNodeWithTag("browse-count").assert(hasText("1 person"))
        onNodeWithTag("browse-person:42").performClick()
        assertEquals(WebRoute.Person(42), next)
    }

    @Test fun everyCardHasARingNrWithoutVotesAndLastPageCountIsExact() = runComposeUiTest {
        val unrated = Hero.OfTitle(title.copy(ref = MediaRef(MediaType.Movie, 8), voteAverage = 7.0, voteCount = 0))
        val read = state().copy(grid = SectionState.Content(PagedItems(listOf(Hero.OfTitle(title), unrated), 1, false)))
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebBrowseContent(entry("/movies?list=upcoming"), read, {}, {}, {}, {}, {}, null)
        }
        onNodeWithTag("browse-count").assert(hasText("2 titles"))
        onNodeWithContentDescription("User score 81 percent", useUnmergedTree = true).assertExists()
        onNodeWithContentDescription("Not rated", useUnmergedTree = true).assertExists()
        onNodeWithContentDescription("User score 70 percent", useUnmergedTree = true).assertDoesNotExist()
        onAllNodesWithText("Jan 1, 2026").assertCountEquals(2)
        onNodeWithText("2026-01-01").assertDoesNotExist()
    }

    @Test fun filtersThatRemoveEverythingShowTheEmptyStateWithoutACount() = runComposeUiTest {
        val empty = state(BrowseSelection.Discover(MediaType.Movie, listOf(28))).copy(grid = SectionState.Empty)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebBrowseContent(entry("/movies?list=discover&genres=28"), empty, {}, {}, {}, {}, {}, null)
        }
        onNodeWithText("No titles match").assertExists()
        onNodeWithTag("browse-count").assertDoesNotExist()
        onNodeWithText("Clear genres").assertExists()
    }

    @Test fun retainedPageErrorHasOnlyExplicitRetryAndDoesNotRequestMore() = runComposeUiTest {
        var retries = 0
        var more = 0
        val failed = state().copy(grid = SectionState.Content(PagedItems(listOf(Hero.OfTitle(title)), 1, true, moreError = SectionErrorKind.Offline)))
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebBrowseContent(entry("/movies?list=upcoming"), failed, {}, { more++ }, { retries++ }, {}, {}, null)
        }
        onNodeWithTag("browse-movie:7").assertExists()
        onNodeWithTag("web-browse").performScrollToNode(hasText("Retry"))
        onNodeWithText("Offline. Check your connection. Loaded results are retained.").assertExists()
        onNodeWithText("Load more").assertDoesNotExist()
        assertEquals(0, more)
        onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
        assertEquals(0, more)
    }

    @Test fun storageSettingsAndGenreRecoveryStaySeparateAndRetainContent() = runComposeUiTest {
        var storageRetries = 0
        var genreRetries = 0
        val failed = state().copy(settingsError = SectionErrorKind.Generic, genresError = SectionErrorKind.Offline)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Box(Modifier.width(390.dp)) {
                WebBrowseContent(entry("/movies?list=upcoming"), failed, {}, {}, {}, { genreRetries++ }, { storageRetries++ }, BrowserStorageFailure.Quota)
            }
        }
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("browse-storage-recovery"))).performClick()
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("browse-settings-recovery"))).performClick()
        assertEquals(2, storageRetries)
        onNodeWithText("Offline. Check your connection.").assertExists()
        onNodeWithTag("web-browse").performScrollToNode(hasTestTag("browse-movie:7"))
        onNodeWithTag("browse-movie:7").assertExists()
        onNodeWithTag("web-browse").performScrollToIndex(0)
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("browse-genres-recovery"))).performClick()
        assertEquals(1, genreRetries)
    }

    @Test fun restoredPageScrollReloadsOnlySavedPagesAndStopsAtExplicitError() = runComposeUiTest {
        val current = entry("/movies").apply { putInt("browse.page", 2); putInt("browse.index", 5); putInt("browse.offset", 20) }
        var more = 0
        var loaded by mutableStateOf(state(BrowseSelection.Movies(MovieList.Popular)))
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebBrowseContent(current, loaded, {}, { more++ }, {}, {}, {}, null)
        }
        runOnIdle { assertEquals(1, more) }
        runOnIdle { loaded = loaded.copy(grid = SectionState.Content(PagedItems(listOf(Hero.OfTitle(title)), 1, true, moreError = SectionErrorKind.Offline))) }
        runOnIdle { assertEquals(1, more) }
        runOnIdle {
            loaded = loaded.copy(grid = SectionState.Content(PagedItems((1..30).map { Hero.OfTitle(title.copy(ref = MediaRef(MediaType.Movie, it))) }, 2, true)))
        }
        waitUntil { current.getInt("browse.page") == 2 && current.getInt("browse.index") == 5 }
        assertEquals(1, more)
    }
}
