package com.techgv.cineverse.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.techgv.cineverse.data.library.LibraryEntry
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.SectionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** §7.8 with real D-pad keys: focus enters the grid, tabs switch on focus, OK on a tab enters the CTA. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun entry(id: Int) = LibraryEntry(
        Title(MediaRef(MediaType.Movie, id), "Movie $id", "", null, null, "2020-01-01", 7.0, 3, emptyList(), MediaType.Movie),
        rating = null,
    )

    private val watchlist = LibraryUiState(
        tab = LibraryTab.Watchlist,
        movies = LibraryGroup(SectionState.Content(listOf(entry(1), entry(2), entry(3)))),
        tv = LibraryGroup(SectionState.Empty),
        imageBaseUrl = "https://image.tmdb.org/t/p/",
    )

    @Test
    fun tabsSwitchOnFocusAndOkEntersTheCta() {
        var state by mutableStateOf(watchlist)
        var opened: MediaRef? = null
        compose.setContent {
            LibraryContent(
                state = state,
                memory = FocusMemory(SavedStateHandle()),
                onTab = { tab ->
                    // Stands in for the ViewModel: Favourites is empty.
                    state = if (tab == LibraryTab.Watchlist) watchlist else
                        watchlist.copy(tab = tab, movies = LibraryGroup(SectionState.Empty))
                },
                onLoadMore = {},
                onRetry = {},
                onOpenTitle = { opened = it },
                onExplore = {},
                onSignIn = {},
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("movie:1").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("movie:2").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(MediaRef(MediaType.Movie, 2), opened)

        compose.onNodeWithTag("movie:2").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("tab:Watchlist").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        assertEquals(LibraryTab.Favourites, state.tab)
        compose.onNodeWithTag("tab:Favourites").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithTag(EXPLORE_TAG).assertIsFocused()
    }

    /** The CTA focused last is restored on re-entry (it used to fall back to the tab chip); UP from it reaches the selected tab. */
    @Test
    fun focusedCtaSurvivesReEntryAndUpGoesToTheSelectedTab() {
        val rated = watchlist.copy(tab = LibraryTab.Rated, movies = LibraryGroup(SectionState.Empty))
        val memory = FocusMemory(SavedStateHandle())
        var shown by mutableStateOf(true)
        compose.setContent {
            if (shown) {
                LibraryContent(rated, memory, onTab = {}, onLoadMore = {}, onRetry = {}, onOpenTitle = {}, onExplore = {}, onSignIn = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(EXPLORE_TAG).assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag(SIGN_IN_TAG).assertIsFocused()

        shown = false
        compose.waitForIdle()
        shown = true
        compose.waitForIdle()
        compose.onNodeWithTag(SIGN_IN_TAG).assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("tab:Rated").assertIsFocused()
    }
}
