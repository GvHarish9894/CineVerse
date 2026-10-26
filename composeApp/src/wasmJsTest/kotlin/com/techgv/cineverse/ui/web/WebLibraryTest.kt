package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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

    @Test fun ratingsShowGoldBadgesTwoSectionsOneFooterAndOnlyKnownActiveCount() = runComposeUiTest {
        var next: WebRoute? = null
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebLibraryContent(webLibraryEntry(LibraryTab.Rated), state(), pages, null, { next = it }, {}, {}, {}, {})
        }
        onNodeWithText("Saved in this browser · sign in to sync with TMDB").assertExists()
        onNodeWithText("Ratings · 2").assertExists()
        onNodeWithText("Watchlist").assertExists()
        onNodeWithText("Favourites").assertExists()
        onNodeWithText("loaded", substring = true).assertDoesNotExist()
        onNodeWithText("unknown", substring = true).assertDoesNotExist()
        onNodeWithText("Refresh Library").assertDoesNotExist()
        val grid = onNodeWithTag("library-grid")
        grid.performScrollToNode(hasTestTag("library-movie-71"))
        onNodeWithText("Movies").assertExists()
        onNodeWithText("★ 8.5").assertExists()
        onNodeWithTag("library-movie-71").performClick()
        assertEquals(WebRoute.Detail("movie", 71), next)
        grid.performScrollToNode(hasTestTag("library-tv-82"))
        onNodeWithText("TV Shows").assertExists()
        onNodeWithText("★ 9.5").assertExists()
        onNodeWithTag("library-tv-82").performClick()
        assertEquals(WebRoute.Detail("tv", 82), next)
        // One page column: a single grid and exactly one site Footer.
        grid.performScrollToNode(hasText("CineVerse"))
        onAllNodesWithText("CineVerse").assertCountEquals(1)
        onNodeWithTag("library-Movie-grid").assertDoesNotExist()
        onNodeWithTag("library-Tv-grid").assertDoesNotExist()
        onNodeWithText("Add to watchlist").assertDoesNotExist()
        onNodeWithText("Rate").assertDoesNotExist()
        grid.performScrollToIndex(0)
        onNodeWithText("Favourites").performClick()
        assertEquals(WebRoute.Library("favourites"), next)
    }

    @Test fun bothEmptyGroupsShowOneTabEmptyStateAndSignedInSubtitle() = runComposeUiTest {
        var next: WebRoute? = null
        val empty = LibraryUiState(signedIn = true, movies = LibraryGroup(SectionState.Empty), tv = LibraryGroup(SectionState.Empty))
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebLibraryContent(webLibraryEntry(), empty, mapOf(MediaType.Movie to 1, MediaType.Tv to 1), null, { next = it }, {}, {}, {}, {})
        }
        onNodeWithText("Synced with your TMDB account").assertExists()
        onNodeWithText("Watchlist · 0").assertExists()
        onNodeWithText("Your watchlist is empty", substring = true).assertExists()
        onNodeWithText("Movies").assertDoesNotExist()
        onNodeWithText("TV Shows").assertDoesNotExist()
        onAllNodesWithText("Browse popular movies").assertCountEquals(1)
        onNodeWithText("Browse popular movies").performClick()
        assertEquals(WebRoute.Browse("movie"), next)
    }

    @Test fun unknownGateHidesPrivateRowsActionsCountsAndGuestClaim() = runComposeUiTest {
        var verifies = 0
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebLibraryContent(webLibraryEntry(), null, pages, null, {}, {}, {}, {}, { verifies++ })
        }
        onNodeWithText("Library source is not yet verified. No private entries are shown.").assertExists()
        onNodeWithTag("library-movie-71").assertDoesNotExist()
        onNodeWithText("Saved in this browser", substring = true).assertDoesNotExist()
        onNodeWithText("Synced with your TMDB account").assertDoesNotExist()
        onNodeWithText("Watchlist").assertExists()
        onNodeWithText("Watchlist ·", substring = true).assertDoesNotExist()
        onNodeWithText("Refresh Library").assertDoesNotExist()
        onNodeWithText("Verify session").performClick()
        assertEquals(1, verifies)
    }

    @Test fun narrowIndependentGroupErrorDoesNotBecomeEmptyAndTvEmptyCtaNavigates() = runComposeUiTest {
        var retryMedia: MediaType? = null
        var next: WebRoute? = null
        val independent = LibraryUiState(movies = LibraryGroup(SectionState.Error(SectionErrorKind.Offline)), tv = LibraryGroup(SectionState.Empty))
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Box(Modifier.width(390.dp)) {
                WebLibraryContent(webLibraryEntry(), independent, pages, null, { next = it }, {}, { retryMedia = it }, {}, {})
            }
        }
        onNodeWithText("Watchlist").assertExists()
        onNodeWithText("Watchlist ·", substring = true).assertDoesNotExist()
        onNodeWithText("Offline. Check your connection.").assertExists()
        onNodeWithText("Your watchlist is empty", substring = true).assertDoesNotExist()
        onNodeWithText("Retry").performClick()
        assertEquals(MediaType.Movie, retryMedia)
        onNodeWithTag("library-grid").performScrollToNode(hasText("Browse popular TV shows"))
        onNodeWithText("No TV shows in your watchlist yet.").assertExists()
        onNodeWithText("Browse popular movies").assertDoesNotExist()
        onNodeWithText("Browse popular TV shows").assertIsDisplayed().performClick()
        assertEquals(WebRoute.Browse("tv"), next)
    }

    @Test fun retainedPaginationErrorAndStorageRecoveryAreSeparateExplicitReadActions() = runComposeUiTest {
        var more = 0
        var retryMedia: MediaType? = null
        var storageRetries = 0
        val loaded = state().let { it.copy(movies = it.movies.copy(hasMore = true, moreError = SectionErrorKind.Offline)) }
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebLibraryContent(webLibraryEntry(LibraryTab.Rated), loaded, mapOf(MediaType.Movie to 1, MediaType.Tv to 1), BrowserStorageFailure.Blocked,
                {}, { more++ }, { retryMedia = it }, { storageRetries++ }, {})
        }
        onNodeWithTag("library-grid").performScrollToNode(hasTestTag("library-movie-71"))
        onNodeWithTag("library-movie-71").assertExists()
        onNodeWithTag("library-grid").performScrollToNode(hasText("Offline. Check your connection. Loaded entries are retained."))
        onNodeWithText("Load more Movies").assertDoesNotExist()
        assertEquals(0, more)
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("library-Movie-paging"))).performClick()
        assertEquals(MediaType.Movie, retryMedia)
        onNodeWithTag("library-grid").performScrollToIndex(0)
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("library-storage-recovery"))).performClick()
        assertEquals(1, storageRetries)
        assertEquals(0, more)
    }

    @Test fun contentFooterFirstVisibleIndexAndOffsetSurviveEntryRecreation() = runComposeUiTest {
        val loaded = state().copy(movies = LibraryGroup(SectionState.Content((1..4).map {
            LibraryEntry(webLibraryTitle(MediaType.Movie, it), null)
        })))
        // title, tabs, Movies header, 4 movies, movie paging, TV Shows header, 1 show, TV paging, Footer.
        assertFooterPositionRoundTrip(loaded, footerIndex = 11)
    }

    @Test fun emptyFooterFirstVisibleIndexAndOffsetSurviveEntryRecreation() = runComposeUiTest {
        val empty = state().copy(movies = LibraryGroup(SectionState.Empty), tv = LibraryGroup(SectionState.Empty))
        // title, tabs, the one tab empty state, Footer.
        assertFooterPositionRoundTrip(empty, footerIndex = 3)
    }

    private fun ComposeUiTest.assertFooterPositionRoundTrip(loaded: LibraryUiState, footerIndex: Int) {
        var current by mutableStateOf(webLibraryEntry(LibraryTab.Rated))
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            // The page grid is shorter than its real Footer, so the Footer can be first visible.
            Box(Modifier.width(390.dp).height(200.dp)) {
                key(current) {
                    WebLibraryContent(current, loaded, pages, null, {}, {}, {}, {}, {})
                }
            }
        }
        val grid = onNodeWithTag("library-grid")
        grid.performScrollToIndex(footerIndex)
        grid.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 9f) }
        waitForIdle()
        val savedIndex = assertNotNull(current.getInt("library.movie.index"))
        val savedOffset = assertNotNull(current.getInt("library.movie.offset"))
        assertEquals(footerIndex, savedIndex)
        assertTrue(savedOffset > 0, "The real Footer must support a nonzero first-visible offset")
        val originalScroll = grid.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        runOnIdle {
            current = webLibraryEntry(LibraryTab.Rated, mutableMapOf(
                "library.movie.index" to savedIndex.toString(),
                "library.movie.offset" to savedOffset.toString(),
            ))
        }
        waitForIdle()
        // Inspect actual grid scroll too: saved input alone would pass before restoration ran.
        assertEquals(originalScroll, grid.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
        assertEquals(footerIndex, current.getInt("library.movie.index"))
        assertEquals(savedOffset, current.getInt("library.movie.offset"))
    }

    @Test fun restorationLoadsOnlyKnownSavedPagesThenRestoresThePageScroll() = runComposeUiTest {
        val entry = webLibraryEntry(LibraryTab.Rated, mutableMapOf("library.movie.page" to "2", "library.movie.index" to "5", "library.movie.offset" to "12"))
        var moviePage by mutableStateOf(1)
        var current by mutableStateOf(state().let { it.copy(signedIn = true, movies = it.movies.copy(hasMore = true)) })
        var more = 0
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            // Phone width = two columns, so saved item 5 (title, tabs, header, then movies) starts a grid line.
            Box(Modifier.width(390.dp).height(480.dp)) {
                WebLibraryContent(entry, current, mapOf(MediaType.Movie to moviePage, MediaType.Tv to 1), null, {}, { media -> if (media == MediaType.Movie) more++ }, {}, {}, {})
            }
        }
        runOnIdle { assertEquals(1, more) }
        runOnIdle { current = current.copy(movies = current.movies.copy(moreError = SectionErrorKind.Offline)) }
        runOnIdle { assertEquals(1, more) }
        runOnIdle {
            moviePage = 2
            current = current.copy(movies = LibraryGroup(SectionState.Content((1..40).map { LibraryEntry(webLibraryTitle(MediaType.Movie, it), null) }), hasMore = true))
        }
        // Pages and position are written only after the restore, so wait for the TV page (absent from the saved entry).
        waitUntil { entry.getInt("library.tv.page") == 1 }
        waitForIdle()
        assertEquals(5, entry.getInt("library.movie.index"))
        assertEquals(12, entry.getInt("library.movie.offset"))
        assertEquals(2, entry.getInt("library.movie.page"))
        assertEquals(1, more)
        // One page scroll: the TV position keys are never written.
        assertNull(entry.getString("library.tv.index"))
        assertNull(entry.getString("library.tv.offset"))
    }
}
