package com.techgv.cineverse.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.ArtPalette
import com.techgv.cineverse.ui.components.PosterCard
import com.techgv.cineverse.ui.components.PosterCardSkeleton
import com.techgv.cineverse.ui.components.QuickAction
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PosterCardTest {
    private val palette = ArtPalette(Color(0xFFFF3D7F), Color(0xFF6B21FF))

    @Test
    fun contentShowsTitleSubtitleAndScoreRingOnWeb() = runComposeUiTest {
        var clicks = 0
        setThemedContent(ShellStyle.Web) { PosterCard("Neon Tide", "Mar 13, 2026", palette, { clicks++ }, voteAverage = 8.1, voteCount = 120) }
        onNodeWithText("Mar 13, 2026").assertIsDisplayed()
        onNodeWithContentDescription("User score 81 percent", useUnmergedTree = true).assertExists()
        onNodeWithText("Mar 13, 2026").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun noRingWithoutVotesOrOnMobileByDefault() = runComposeUiTest {
        setThemedContent(ShellStyle.Web) { PosterCard("Upcoming", "Coming Jun 5", palette, {}, voteAverage = 0.0, voteCount = 0) }
        onNodeWithContentDescription("User score", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun mobileShellHidesTheRingByDefault() = runComposeUiTest {
        setThemedContent(ShellStyle.Mobile) { PosterCard("Neon Tide", "2026 · ★ 8.1", palette, {}, voteAverage = 8.1, voteCount = 120) }
        onNodeWithText("2026 · ★ 8.1").assertIsDisplayed()
        onNodeWithContentDescription("User score", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun loadingStateIsAnnounced() = runComposeUiTest {
        setThemedContent { PosterCardSkeleton() }
        onNodeWithContentDescription("Loading").assertExists()
    }

    @Test
    fun hoverRevealsQuickActionWhichToggles() = runComposeUiTest {
        var toggles = 0
        setThemedContent(ShellStyle.Web) {
            PosterCard("Neon Tide", "Mar 13, 2026", palette, {}, quickAction = QuickAction(false, "Add to watchlist") { toggles++ })
        }
        onNodeWithContentDescription("Add to watchlist").assertDoesNotExist()
        onNodeWithText("Mar 13, 2026").performMouseInput { moveTo(center) }
        waitForIdle()
        onNodeWithContentDescription("Add to watchlist").assertExists().performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun savedQuickActionStaysVisible() = runComposeUiTest {
        setThemedContent(ShellStyle.Web) {
            PosterCard("Neon Tide", null, palette, {}, quickAction = QuickAction(true, "Remove from watchlist") {})
        }
        onNodeWithContentDescription("Remove from watchlist").assertExists()
    }
}
