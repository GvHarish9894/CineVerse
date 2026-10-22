package com.techgv.cineverse.ui.season

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.techgv.cineverse.data.tmdb.EpisodeDto
import com.techgv.cineverse.data.tmdb.SeasonDto
import com.techgv.cineverse.data.tmdb.SeasonSummaryDto
import com.techgv.cineverse.data.tmdb.TvDetailDto
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.data.tmdb.toSeason
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.presentation.SectionState
import com.techgv.cineverse.ui.components.longText
import com.techgv.cineverse.ui.components.readFocusedScrollableTextByDpad
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** §7.6 with real D-pad keys: season → episodes, inline panel open / BACK closes to its episode, LEFT to seasons. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SeasonScreenDpadTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val show = TvDetailDto(7, name = "Show", seasons = listOf(SeasonSummaryDto(11, 1, episodeCount = 2), SeasonSummaryDto(12, 2))).toDetail("IN")
    private val season1 = SeasonDto(
        11, 1,
        episodes = listOf(EpisodeDto(1, episodeNumber = 1, name = "Pilot"), EpisodeDto(2, episodeNumber = 2, name = "Second", overview = "Full overview. " + longText("Episode"))),
    ).toSeason(7)

    @Test
    fun okOpensPanel_backClosesItToItsEpisode_leftGoesToSeasons() {
        var state by mutableStateOf(SeasonUiState(show = SectionState.Content(show), selected = 1, episodes = SectionState.Content(season1)))
        val actions = SeasonActions(
            onRetry = {}, onRetryEpisodes = {}, onSelectSeason = {},
            onOpenEpisode = { state = state.copy(openEpisode = it) },
            onCloseEpisode = { state = state.copy(openEpisode = null) },
            onNoticeShown = {}, onNavigate = {},
        )
        val memory = FocusMemory(SavedStateHandle())
        // Mirrors AppNav's content container: focusRestorer() owning one focusGroup must not hijack focus entering the panel.
        compose.setContent { Box(Modifier.focusRestorer().focusGroup()) { SeasonContent(state, memory, imageBase = "", actions) } }
        compose.waitForIdle()

        compose.onNodeWithText("Season 1").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithText("E1 · Pilot", substring = true).assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("E2 · Second", substring = true).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        // Panel: full overview, focus inside on Close; LEFT cannot leave it.
        compose.onAllNodesWithText("Full overview", substring = true).assertCountEquals(2) // card excerpt + panel
        compose.onNodeWithText("Close").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        compose.onNodeWithText("Close").assertIsFocused()

        // The long overview is read by D-pad alone: UP into it, DOWN page by page, then back on Close.
        compose.onNodeWithText("Close").performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        assertTrue("several pages", compose.readFocusedScrollableTextByDpad() >= 2)
        compose.onNodeWithText("Close").assertIsFocused()

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNodeWithText("E2 · Second", substring = true).assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        compose.onNodeWithText("Season 1").assertIsFocused()
    }
}
