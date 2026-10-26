@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.presentation.LibraryWrite
import com.techgv.cineverse.ui.adaptive.*
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.web.WebRateFixture.Companion.B
import com.techgv.cineverse.ui.web.WebRateFixture.Companion.MOVIE
import com.techgv.cineverse.ui.web.WebRateFixture.Companion.OTHER
import kotlin.test.*

class WebRateDialogTest {
    private val reduced = AppearanceSettings(reduceMotion = true)
    private val movieRating = "rate-account-a:rating/${MOVIE.ref.key}"

    private fun ComposeUiTest.show(f: WebRateFixture) = setThemedContent(ShellStyle.Web, reduced) {
        WebRateDialog(f.actions, remember { FocusRequester() })
    }

    /** Trigger + neighbours, keyboard input mode: the dialog is opened from a focused control. */
    private fun ComposeUiTest.showWithTrigger(f: WebRateFixture) = setThemedContent(ShellStyle.Web, reduced) {
        val trigger = remember { FocusRequester() }
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
        Column {
            PillButton("Before rate", {})
            PillButton("Rate trigger", { f.actions.openRate(MOVIE) }, Modifier.focusRequester(trigger))
            PillButton("After rate", {})
        }
        WebRateDialog(f.actions, trigger)
    }

    /** Pointer x at the centre of half-star segment [k] (1..20) of the star row. */
    private fun MouseInjectionScope.segment(k: Int) = Offset(width * (k - 0.5f) / 20f, centerY)

    private fun ComposeUiTest.assertValue(value: String, word: String) {
        onNodeWithTag("web-rate-value").assertTextEquals("$value / 10")
        onNodeWithTag("web-rate-word").assertTextEquals(word)
    }

    @Test fun opensOnlyForAHolderPromptAndShowsTitleButNoIdentity() = runComposeUiTest {
        val f = WebRateFixture()
        try {
            show(f)
            onNodeWithTag("web-rate-dialog").assertDoesNotExist()
            runOnIdle { f.actions.openRate(MOVIE) }
            onNodeWithTag("web-rate-dialog").assertExists()
            onNodeWithText("Neon Tide").assertIsDisplayed()
            onNodeWithText("RATE ON TMDB").assertExists()
            assertValue("–", "Choose a rating")
            onNodeWithText("Submit rating").assertIsNotEnabled()
            onNodeWithText("Remove rating").assertDoesNotExist()
            onNodeWithText("Viewer A", substring = true).assertDoesNotExist()
            onNodeWithText("rate-account-a", substring = true).assertDoesNotExist()
            assertTrue(f.writes.isEmpty())
        } finally { f.dispose() }
    }

    @Test fun hoverPreviewsHalfStepsAndWordsWithoutChoosingUntilClick() = runComposeUiTest {
        val f = WebRateFixture()
        try {
            show(f)
            runOnIdle { f.actions.openRate(MOVIE) }
            val stars = onNodeWithTag("web-rate-stars")
            stars.performMouseInput { moveTo(segment(15)) }
            assertValue("7.5", "Great")
            stars.performMouseInput { moveTo(segment(1)) }
            assertValue("0.5", "Poor")
            stars.performMouseInput { moveTo(segment(10)) }
            assertValue("5.0", "Meh")
            stars.performMouseInput { moveTo(segment(20)) }
            assertValue("10.0", "Masterpiece")
            stars.performMouseInput { exit() }
            assertValue("–", "Choose a rating")
            onNodeWithText("Submit rating").assertIsNotEnabled()
            stars.performMouseInput { click(segment(14)) }
            stars.performMouseInput { exit() }
            assertValue("7.0", "Good")
            onNodeWithText("Submit rating").assertIsEnabled()
            assertTrue(f.writes.isEmpty())
        } finally { f.dispose() }
    }

    @Test fun keyboardAdjustsByHalfStepsWithHomeEndAndEnterSubmitsOnceForThePromptIdentity() = runComposeUiTest {
        val f = WebRateFixture()
        try {
            show(f)
            runOnIdle { f.actions.openRate(MOVIE) }
            val stars = onNodeWithTag("web-rate-stars")
            stars.requestFocus()
            stars.performKeyInput { pressKey(Key.Enter) } // Nothing chosen: Enter does not submit.
            onNodeWithTag("web-rate-dialog").assertExists()
            stars.performKeyInput { pressKey(Key.DirectionRight) }
            assertValue("0.5", "Poor")
            stars.performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.DirectionRight) }
            assertValue("1.5", "Poor")
            stars.performKeyInput { pressKey(Key.DirectionLeft) }
            assertValue("1.0", "Poor")
            stars.performKeyInput { pressKey(Key.MoveEnd) }
            assertValue("10.0", "Masterpiece")
            stars.performKeyInput { pressKey(Key.DirectionRight) }
            assertValue("10.0", "Masterpiece")
            stars.performKeyInput { pressKey(Key.MoveHome) }
            assertValue("0.5", "Poor")
            stars.performKeyInput { pressKey(Key.DirectionDown) }
            assertValue("0.5", "Poor")
            stars.performKeyInput { pressKey(Key.MoveEnd); repeat(3) { pressKey(Key.DirectionLeft) } }
            assertValue("8.5", "Great")
            assertTrue(f.writes.isEmpty())
            stars.performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("web-rate-dialog").assertDoesNotExist()
            assertEquals(listOf("$movieRating=8.5"), f.writes)
            assertEquals(LibraryWrite.Rate(8.5), f.outcomes.single().write)
        } finally { f.dispose() }
    }

    @Test fun submitClickWritesExactlyOnceAndShowsNoToastOfItsOwn() = runComposeUiTest {
        val f = WebRateFixture()
        try {
            show(f)
            runOnIdle { f.actions.openRate(MOVIE) }
            onNodeWithTag("web-rate-stars").performMouseInput { click(segment(15)) }
            onNodeWithText("Submit rating").performClick()
            onNodeWithTag("web-rate-dialog").assertDoesNotExist()
            assertEquals(listOf("$movieRating=7.5"), f.writes)
            assertEquals(1, f.outcomes.size)
            onNodeWithText("Rated", substring = true).assertDoesNotExist()
            onNodeWithText("Synced", substring = true).assertDoesNotExist()
        } finally { f.dispose() }
    }

    @Test fun removeAppearsOnlyForAnExistingRatingAndClearsItForThePromptIdentity() = runComposeUiTest {
        val f = WebRateFixture()
        try {
            show(f)
            runOnIdle { f.actions.openRate(MOVIE) }
            onNodeWithText("Remove rating").assertDoesNotExist()
            onNodeWithTag("web-rate-stars").requestFocus()
            onNodeWithTag("web-rate-stars").performKeyInput { repeat(12) { pressKey(Key.DirectionRight) }; pressKey(Key.Enter) }
            onNodeWithTag("web-rate-dialog").assertDoesNotExist()
            runOnIdle { f.actions.openRate(MOVIE) }
            assertValue("6.0", "Good")
            onNodeWithText("Submit rating").assertIsEnabled()
            onNodeWithText("Remove rating").performClick()
            onNodeWithTag("web-rate-dialog").assertDoesNotExist()
            assertEquals(listOf("$movieRating=6.0", "$movieRating=null"), f.writes)
            assertEquals(LibraryWrite.Unrate, f.outcomes.last().write)
        } finally { f.dispose() }
    }

    @Test fun guestPromptSavesInThisBrowserAndRemovesTheGuestRating() = runComposeUiTest {
        val f = WebRateFixture(signedIn = null)
        try {
            f.seedGuestRating(MOVIE.ref, 4.0)
            show(f)
            runOnIdle { f.actions.openRate(MOVIE) }
            onNodeWithText("RATE IN THIS BROWSER").assertExists()
            onNodeWithText("RATE ON TMDB").assertDoesNotExist()
            assertValue("4.0", "Meh")
            onNodeWithText("Remove rating").performClick()
            assertEquals(listOf("guest:rating/${MOVIE.ref.key}=null"), f.writes)
        } finally { f.dispose() }
    }

    @Test fun closeAndEscapeWriteNothingAndReturnFocusToTheTrigger() = runComposeUiTest {
        val f = WebRateFixture()
        try {
            showWithTrigger(f)
            onNodeWithText("Rate trigger").requestFocus()
            onNodeWithText("Rate trigger").performKeyInput { pressKey(Key.Enter) }
            onNodeWithContentDescription("Close").assertIsFocused()
            onNodeWithTag("web-rate-stars").performMouseInput { click(segment(16)) }
            onNodeWithContentDescription("Close").requestFocus()
            onNodeWithContentDescription("Close").performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("web-rate-dialog").assertDoesNotExist()
            onNodeWithText("Rate trigger").assertIsFocused()

            onNodeWithText("Rate trigger").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("web-rate-dialog").assertExists()
            onNodeWithContentDescription("Close").performClick()
            onNodeWithTag("web-rate-dialog").assertDoesNotExist()
            onNodeWithText("Rate trigger").assertIsFocused()
            assertTrue(f.writes.isEmpty())
            assertTrue(f.outcomes.isEmpty())
        } finally { f.dispose() }
    }

    @Test fun tabStaysInsideTheDialogForSixCycles() = runComposeUiTest {
        val f = WebRateFixture()
        try {
            showWithTrigger(f)
            onNodeWithText("Rate trigger").requestFocus()
            onNodeWithText("Rate trigger").performKeyInput { pressKey(Key.Enter) }
            onNodeWithContentDescription("Close").assertIsFocused()
            val focusedInDialog = isFocused() and hasAnyAncestor(isDialog())
            repeat(6) {
                onNode(focusedInDialog).performKeyInput { pressKey(Key.Tab) }
                onNode(focusedInDialog).assertExists()
                onNodeWithText("Before rate").assertIsNotFocused()
                onNodeWithText("After rate").assertIsNotFocused()
            }
            assertTrue(f.writes.isEmpty())
        } finally { f.dispose() }
    }

    @Test fun identityChangeClosesTheDialogWithoutWriting() = runComposeUiTest {
        val f = WebRateFixture()
        try {
            show(f)
            runOnIdle { f.actions.openRate(MOVIE) }
            onNodeWithTag("web-rate-stars").performMouseInput { click(segment(15)) }
            runOnIdle { f.switchTo(B) }
            onNodeWithTag("web-rate-dialog").assertDoesNotExist()
            runOnIdle { f.actions.submitRating(7.5) } // A late submit has no prompt to write for.
            assertTrue(f.writes.isEmpty())
            assertTrue(f.outcomes.isEmpty())
        } finally { f.dispose() }
    }

    @Test fun replacedPromptShowsTheNewTitleWithItsOwnStartingValue() = runComposeUiTest {
        val f = WebRateFixture()
        try {
            show(f)
            runOnIdle { f.actions.openRate(MOVIE) }
            onNodeWithTag("web-rate-stars").performMouseInput { click(segment(15)); exit() }
            assertValue("7.5", "Great")
            runOnIdle { f.actions.openRate(OTHER) }
            onNodeWithText("Quiet Harbour").assertIsDisplayed()
            onNodeWithText("Neon Tide").assertDoesNotExist()
            assertValue("–", "Choose a rating")
            assertTrue(f.writes.isEmpty())
        } finally { f.dispose() }
    }

    @Test fun playerActiveHidesThePromptAndItReturnsWithoutWriting() = runComposeUiTest {
        val f = WebRateFixture()
        try {
            show(f)
            runOnIdle { f.actions.openRate(MOVIE) }
            onNodeWithTag("web-rate-dialog").assertExists()
            runOnIdle { f.actions.setPlayerActive(true) }
            onNodeWithTag("web-rate-dialog").assertDoesNotExist()
            runOnIdle { f.actions.setPlayerActive(false) }
            onNodeWithTag("web-rate-dialog").assertExists()
            onNodeWithText("Neon Tide").assertIsDisplayed()
            assertTrue(f.writes.isEmpty())
        } finally { f.dispose() }
    }

    @Test fun phoneAndDesktopShowStarsValueAndActions() = runComposeUiTest {
        val f = WebRateFixture()
        var width by mutableStateOf(WindowClass.Phone)
        try {
            setThemedContent(ShellStyle.Web) {
                CompositionLocalProvider(LocalWindowClass provides width) {
                    WebRateDialog(f.actions, remember { FocusRequester() })
                }
            }
            runOnIdle { f.actions.openRate(MOVIE) }
            onNodeWithTag("web-rate-stars").assertIsDisplayed()
            onNodeWithTag("web-rate-value").assertIsDisplayed()
            onNodeWithText("Submit rating").assertIsDisplayed()
            runOnIdle { width = WindowClass.Desktop }
            onNodeWithTag("web-rate-stars").assertIsDisplayed()
            onNodeWithText("Submit rating").assertIsDisplayed()
            assertTrue(f.writes.isEmpty())
        } finally { f.dispose() }
    }
}
