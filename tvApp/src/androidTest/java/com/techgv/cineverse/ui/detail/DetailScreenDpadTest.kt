package com.techgv.cineverse.ui.detail

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.techgv.cineverse.data.tmdb.CastDto
import com.techgv.cineverse.data.tmdb.CreditsDto
import com.techgv.cineverse.data.tmdb.MovieDetailDto
import com.techgv.cineverse.data.tmdb.PageDto
import com.techgv.cineverse.data.tmdb.ReviewDto
import com.techgv.cineverse.data.tmdb.VideoDto
import com.techgv.cineverse.data.tmdb.VideosDto
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.SavedFocus
import com.techgv.cineverse.ui.components.longText
import com.techgv.cineverse.ui.components.readFocusedScrollableTextByDpad
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.ui.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals(Route.Player("yt1", title = "Fight Club", kicker = "Official Trailer"), navigated.single())

        // Entering a row without memory lands on its first item (prototype rowMem), not the nearest one.
        compose.onNodeWithText("Play trailer").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Actor One", substring = true).assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Actor Two", substring = true).assertIsFocused()

        // Push (Player / Person) and pop: the destination leaves and re-enters composition with the same memory.
        onScreen = false
        compose.waitForIdle()
        onScreen = true
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Actor Two", substring = true).assertIsFocused()
    }

    @Test
    fun reviewOkOpensReadingPanel_readByDpad_closeAndBackRestoreTheCard() {
        val withReview = MovieDetailDto(
            550, title = "Fight Club",
            reviews = PageDto(results = listOf(ReviewDto(id = "r1", author = "Critic", content = longText("Review")))),
        ).toDetail("IN")
        val memory = FocusMemory(SavedStateHandle()).apply { save(SavedFocus("main", "reviews", "r1", 0)) }
        compose.setContent {
            DetailContent(
                DetailUiState(SectionState.Content(withReview)), LibraryState.Unknown, memory, imageBase = "",
                DetailActions(onRetry = {}, library = null, onNavigate = {}),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("Critic").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        // Panel: Close focused, the full text above it is read by D-pad alone, focus cannot leave the panel.
        compose.onNodeWithText("Close").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        assertTrue("several pages", compose.readFocusedScrollableTextByDpad() >= 2)
        compose.onNodeWithText("Close").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Close").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNodeWithText("Critic").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        // BACK closes it too, back on the invoking card.
        compose.onNodeWithText("Close").assertIsFocused()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNodeWithText("Critic").assertIsFocused()
    }
}
