package com.techgv.cineverse.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.techgv.cineverse.data.tmdb.CastDto
import com.techgv.cineverse.data.tmdb.CreditsDto
import com.techgv.cineverse.data.tmdb.MovieDetailDto
import com.techgv.cineverse.data.tmdb.VideoDto
import com.techgv.cineverse.data.tmdb.VideosDto
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.ui.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Detail with real D-pad keys: initial focus, row moves, Play trailer → Player route, focus restored on return. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DetailScreenDpadTest {
    @get:Rule
    val compose = createComposeRule()

    private val detail = MovieDetailDto(
        550, title = "Fight Club", overview = "Overview",
        credits = CreditsDto(cast = listOf(CastDto(1, "Actor One", "Narrator", order = 0), CastDto(2, "Actor Two", "Tyler", order = 1))),
        videos = VideosDto(listOf(VideoDto(key = "yt1", name = "Official Trailer", site = "YouTube", type = "Trailer", official = true))),
    ).toDetail("IN")

    @Test
    fun playTrailerFocusedFirst_downToCast_okOpensPlayer_returnRestoresFocus() {
        val navigated = mutableListOf<Route>()
        val memory = FocusMemory(SavedStateHandle())
        var onScreen by mutableStateOf(true)
        compose.setContent {
            if (onScreen) {
                DetailContent(
                    DetailUiState(SectionState.Content(detail)), LibraryState.Unknown, memory, imageBase = "",
                    DetailActions(onRetry = {}, library = null, onNavigate = { navigated += it }),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Play trailer").assertIsFocused()
        // LocalLibraryActions absent → library buttons disabled.
        compose.onNodeWithText("Watchlist").assertIsNotEnabled()

        compose.onNodeWithText("Play trailer").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(Route.Player("yt1", "Official Trailer", "Fight Club · Trailer"), navigated.single())

        compose.onNodeWithText("Play trailer").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Actor One", substring = true).assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithText("Actor Two", substring = true).assertIsFocused()

        // Push (Player / Person) and pop: the destination leaves and re-enters composition with the same memory.
        onScreen = false
        compose.waitForIdle()
        onScreen = true
        compose.waitForIdle()
        compose.onNodeWithText("Actor Two", substring = true).assertIsFocused()
    }
}
