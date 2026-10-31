package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.setThemedContent
import kotlin.test.*

/** Prepared for Android/iOS UI compilation; no native runtime authority is implied. */
@OptIn(ExperimentalTestApi::class)
class MobileLibraryScreenTest {
    @Test fun confirmedGuestHasRequiredSignInAndEmptyBrowseCallbacksAndSelectedTab() = runComposeUiTest {
        var signIns = 0
        var explores = 0
        var selected: LibraryTab? = null
        setThemedContent {
            LibraryContentFixture(LibraryUiState(tab = LibraryTab.Favourites, movies = LibraryGroup(SectionState.Empty),
                tv = LibraryGroup(SectionState.Empty), sourceIdentityKnown = true),
                onSignIn = { signIns++ }, onExplore = { explores++ }, onTab = { selected = it })
        }
        onNodeWithText("Favourites").assertIsSelected()
        onNodeWithText("Saved on this device · Guest").assertExists()
        onNodeWithText("Sign in with TMDB").performClick()
        onNodeWithTag("mobile-library").performScrollToNode(hasText("Explore movies"))
        onNodeWithText("Explore movies").performClick()
        assertEquals(1, signIns)
        assertEquals(1, explores)
        onNodeWithTag("mobile-library").performScrollToNode(hasText("Rated"))
        onNodeWithText("Rated").performClick()
        assertEquals(LibraryTab.Rated, selected)
    }

    @Test fun bothGroupsEmptyShowOnlyTheEmptyHero() = runComposeUiTest {
        setThemedContent {
            LibraryContentFixture(LibraryUiState(tab = LibraryTab.Favourites, movies = LibraryGroup(SectionState.Empty),
                tv = LibraryGroup(SectionState.Empty), sourceIdentityKnown = true))
        }
        onNodeWithTag("mobile-library").performScrollToNode(hasText("Explore movies"))
        onAllNodesWithText("Your favourites is empty").assertCountEquals(1)
        listOf("Favourites · 0 loaded", "Movies · 0 loaded", "TV shows · 0 loaded", "Movies", "TV shows",
            "No movies in favourites.", "No tv shows in favourites.").forEach {
            onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test fun longAccountNameStaysOnOneEllipsizedHeaderLine() = runComposeUiTest {
        setThemedContent {
            LibraryContentFixture(LibraryUiState(accountName = LONG_ACCOUNT_NAME, signedIn = true,
                movies = LibraryGroup(SectionState.Empty), tv = LibraryGroup(SectionState.Empty), sourceIdentityKnown = true))
        }
        val layout = onNodeWithText("TMDB account · $LONG_ACCOUNT_NAME").assertExists().textLayout()
        assertEquals(1, layout.lineCount)
        assertTrue(layout.hasVisualOverflow, "The long name is ellipsized, not wrapped")
    }

    @Test fun movieTvCardsUseActualIdentifiersRatedBadgeAndHonestLoadedCounts() = runComposeUiTest {
        var opened: MediaRef? = null
        setThemedContent {
            LibraryContentFixture(LibraryUiState(tab = LibraryTab.Rated,
                movies = LibraryGroup(SectionState.Content(listOf(libraryFixtureEntry(MediaType.Movie)))),
                tv = LibraryGroup(SectionState.Content(listOf(libraryFixtureEntry(MediaType.Tv)))), sourceIdentityKnown = true),
                onOpen = { opened = it })
        }
        onNodeWithText("Movies · 1 loaded").assertExists()
        onNodeWithText("Library fixture Movie 1").performClick()
        assertEquals(MediaRef(MediaType.Movie, 1), opened)
        onNodeWithTag("mobile-library").performScrollToNode(hasText("Library fixture Tv 1"))
        onNodeWithText("TV shows · 1 loaded").assertExists()
        onNodeWithText("Library fixture Tv 1").performClick()
        assertEquals(MediaRef(MediaType.Tv, 1), opened)
        onAllNodesWithText("★ 8.0").onFirst().assertExists()
    }

    @Test fun movieNextPageErrorAndTvPagingAreIndependentAndNeverAutoRetry() = runComposeUiTest {
        val more = mutableListOf<MediaType>()
        val retries = mutableListOf<MediaType>()
        setThemedContent {
            LibraryContentFixture(LibraryUiState(sourceIdentityKnown = true,
                movies = LibraryGroup(SectionState.Content(listOf(libraryFixtureEntry(MediaType.Movie))), hasMore = true,
                    moreError = SectionErrorKind.Offline, loadedPage = 1),
                tv = LibraryGroup(SectionState.Content(listOf(libraryFixtureEntry(MediaType.Tv))), hasMore = true, loadedPage = 4)),
                onMore = { more += it }, onRetry = { retries += it })
        }
        onNodeWithTag("mobile-library").performScrollToNode(hasTestTag("library-Movie-retry"))
        assertTrue(more.isEmpty())
        assertTrue(retries.isEmpty())
        onNodeWithTag("library-Movie-retry").onChildren().filter(hasText("Retry")).onFirst().performClick()
        assertEquals(listOf(MediaType.Movie), retries)
        onNodeWithTag("mobile-library").performScrollToNode(hasText("Load more TV shows"))
        onNodeWithText("Load more TV shows").performClick()
        assertEquals(listOf(MediaType.Tv), more)
    }

    @Test fun unknownFailureAndInvalidStatesNeverInventGuestOrEmptySuccess() = runComposeUiTest {
        val state = mutableStateOf(MobileLibraryState())
        var retries = 0
        setThemedContent {
            MobileLibraryContent(state.value, "", {}, {}, {}, { retries++ }, {}, {}, {}, {})
        }
        onNodeWithText("Loading Library…").assertExists()
        onNodeWithText("Saved on this device · Guest").assertDoesNotExist()
        onNodeWithText("Explore movies").assertDoesNotExist()
        runOnIdle { state.value = state.value.copy(failed = true) }
        onNodeWithTag("library-storage-retry").onChildren().filter(hasText("Retry")).onFirst().performClick()
        assertEquals(1, retries)
        onNodeWithText("Sign in with TMDB").assertDoesNotExist()
        runOnIdle { state.value = state.value.copy(invalid = true) }
        onNodeWithText("Invalid saved Library state. Leave it unchanged.").assertExists()
        onNodeWithText("Explore movies").assertDoesNotExist()
    }

    @Test fun refreshFailureKeepsLoadedCardAndOffersOnlyExplicitGroupRetry() = runComposeUiTest {
        var retries = 0
        setThemedContent {
            LibraryContentFixture(LibraryUiState(sourceIdentityKnown = true,
                movies = LibraryGroup(SectionState.Content(listOf(libraryFixtureEntry(MediaType.Movie))), refreshError = SectionErrorKind.Offline),
                tv = LibraryGroup(SectionState.Empty)), onRetry = { retries++ })
        }
        onNodeWithText("Library fixture Movie 1").assertExists()
        onNodeWithText("Watchlist · 1 loaded").assertExists()
        onNodeWithText("Movies · 1 loaded").assertExists()
        onNodeWithTag("mobile-library").performScrollToNode(hasText("No tv shows in watchlist."))
        onNodeWithText("TV shows · 0 loaded").assertExists()
        onNodeWithText("Your watchlist is empty").assertDoesNotExist()
        onNodeWithTag("mobile-library").performScrollToNode(hasTestTag("library-Movie-retry"))
        assertEquals(0, retries)
        onNodeWithTag("library-Movie-retry").onChildren().filter(hasText("Retry")).onFirst().performClick()
        assertEquals(1, retries)
    }
}

internal const val LONG_ACCOUNT_NAME =
    "a.very.long.tmdb.account.display.name.that.keeps.going.and.going@an-equally-long-example-mail-provider.example.com"

internal fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
    val layouts = mutableListOf<TextLayoutResult>()
    fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
    return layouts.single()
}

@Composable
private fun LibraryContentFixture(
    library: LibraryUiState,
    onSignIn: () -> Unit = {},
    onExplore: () -> Unit = {},
    onTab: (LibraryTab) -> Unit = {},
    onOpen: (MediaRef) -> Unit = {},
    onMore: (MediaType) -> Unit = {},
    onRetry: (MediaType) -> Unit = {},
) = MobileLibraryContent(MobileLibraryState(library = library, scrollReady = true), "", onTab, onMore,
    onRetry, {}, onOpen, onSignIn, onExplore, {})
