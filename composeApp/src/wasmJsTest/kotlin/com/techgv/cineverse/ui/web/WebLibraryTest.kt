package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.data.library.LibraryEntry
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WebLibraryTest {
    private fun state(tab: LibraryTab = LibraryTab.Rated) = LibraryUiState(tab = tab,
        movies = LibraryGroup(SectionState.Content(listOf(LibraryEntry(webLibraryTitle(MediaType.Movie, 71), 8.5)))),
        tv = LibraryGroup(SectionState.Content(listOf(LibraryEntry(webLibraryTitle(MediaType.Tv, 82), 9.5)))))
    private val pages = mapOf(MediaType.Movie to null, MediaType.Tv to null)

    @Test fun ratingsShowActualBadgesAndMovieTvIdsWithOnlyLoadedActiveCount() = runComposeUiTest {
        var next: WebRoute? = null
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebLibraryContent(webLibraryEntry(LibraryTab.Rated), state(), pages, null, { next = it }, {}, {}, {}, {}, {})
        }
        onNodeWithText("Ratings · 2 loaded").assertExists()
        onNodeWithText("Watchlist · count unknown").assertExists()
        onNodeWithText("Favourites · count unknown").assertExists()
        onNodeWithText("Your rating · 8.5 / 10").assertExists()
        onNodeWithText("Your rating · 9.5 / 10").assertExists()
        onNodeWithTag("library-Movie-grid").performScrollToNode(hasTestTag("library-movie-71"))
        onNodeWithTag("library-movie-71").performClick()
        assertEquals(WebRoute.Detail("movie", 71), next)
        onNodeWithTag("library-Tv-grid").performScrollToNode(hasTestTag("library-tv-82"))
        onNodeWithTag("library-tv-82").performClick()
        assertEquals(WebRoute.Detail("tv", 82), next)
        onNodeWithText("Add to watchlist").assertDoesNotExist()
        onNodeWithText("Rate").assertDoesNotExist()
        onNodeWithText("Favourites · count unknown").performClick()
        assertEquals(WebRoute.Library("favourites"), next)
    }

    @Test fun unknownGateHidesPrivateRowsActionsCountsAndGuestClaim() = runComposeUiTest {
        var verifies = 0
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebLibraryContent(webLibraryEntry(), null, pages, null, {}, {}, {}, {}, {}, { verifies++ })
        }
        onNodeWithText("Library source is not yet verified. No private entries are shown.").assertExists()
        onNodeWithTag("library-movie-71").assertDoesNotExist()
        onNodeWithText("Guest library", substring = true).assertDoesNotExist()
        onNodeWithText("Watchlist · count unknown").assertExists()
        onNodeWithText("Refresh Library").assertDoesNotExist()
        onNodeWithText("Verify session").performClick()
        assertEquals(1, verifies)
    }

    @Test fun narrowIndependentGroupErrorDoesNotBecomeEmptyAndEmptyCtaNavigates() = runComposeUiTest {
        var retryMedia: MediaType? = null
        var next: WebRoute? = null
        val independent = LibraryUiState(movies = LibraryGroup(SectionState.Error(SectionErrorKind.Offline)), tv = LibraryGroup(SectionState.Empty))
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Box(Modifier.width(390.dp)) {
                WebLibraryContent(webLibraryEntry(), independent, pages, null, { next = it }, {}, { retryMedia = it }, {}, {}, {})
            }
        }
        onNodeWithText("Watchlist · count unknown").assertExists()
        onNodeWithText("Offline. Check your connection.").assertExists()
        onNodeWithText("No Movies in Watchlist").assertDoesNotExist()
        onNodeWithText("Retry").performClick()
        assertEquals(MediaType.Movie, retryMedia)
        onNodeWithTag("library-Tv-grid").performScrollToNode(hasText("Browse popular movies"))
        onNodeWithText("Browse popular movies").assertIsDisplayed().performClick()
        assertEquals(WebRoute.Browse("movie"), next)
    }

    @Test fun retainedPaginationErrorAndStorageRecoveryAreSeparateExplicitReadActions() = runComposeUiTest {
        var more = 0
        var retryMedia: MediaType? = null
        var storageRetries = 0
        val loaded = state().let { it.copy(movies = it.movies.copy(hasMore = true, moreError = SectionErrorKind.Offline)) }
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebLibraryContent(webLibraryEntry(LibraryTab.Rated), loaded, mapOf(MediaType.Movie to 1, MediaType.Tv to 1), BrowserStorageFailure.Blocked,
                {}, { more++ }, { retryMedia = it }, {}, { storageRetries++ }, {})
        }
        onNodeWithTag("library-Movie-grid").performScrollToNode(hasTestTag("library-movie-71"))
        onNodeWithTag("library-movie-71").assertExists()
        onNodeWithTag("library-Movie-grid").performScrollToNode(hasText("Offline. Check your connection. Loaded entries are retained."))
        onNodeWithText("Load more Movies").assertDoesNotExist()
        assertEquals(0, more)
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("library-Movie-paging"))).performClick()
        assertEquals(MediaType.Movie, retryMedia)
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("library-storage-recovery"))).performClick()
        assertEquals(1, storageRetries)
        assertEquals(0, more)
    }

    @Test fun restorationLoadsOnlyKnownSavedPagesThenRetainsIndependentScroll() = runComposeUiTest {
        val entry = webLibraryEntry(LibraryTab.Rated, mutableMapOf("library.movie.page" to "2", "library.movie.index" to "5", "library.movie.offset" to "12"))
        var moviePage by mutableStateOf(1)
        var current by mutableStateOf(state().let { it.copy(signedIn = true, movies = it.movies.copy(hasMore = true)) })
        var more = 0
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebLibraryContent(entry, current, mapOf(MediaType.Movie to moviePage, MediaType.Tv to 1), null, {}, { media -> if (media == MediaType.Movie) more++ }, {}, {}, {}, {})
        }
        runOnIdle { assertEquals(1, more) }
        runOnIdle { current = current.copy(movies = current.movies.copy(moreError = SectionErrorKind.Offline)) }
        runOnIdle { assertEquals(1, more) }
        runOnIdle {
            moviePage = 2
            current = current.copy(movies = LibraryGroup(SectionState.Content((1..40).map { LibraryEntry(webLibraryTitle(MediaType.Movie, it), null) }), hasMore = true))
        }
        waitUntil { entry.getInt("library.movie.index") == 5 && entry.getInt("library.movie.page") == 2 }
        assertEquals(1, more)
        assertEquals(1, entry.getInt("library.tv.page"))
    }
}
