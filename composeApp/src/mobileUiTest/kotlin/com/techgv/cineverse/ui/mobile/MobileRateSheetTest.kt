package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.data.tmdb.MovieDetailDto
import com.techgv.cineverse.presentation.RatePrompt
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.theme.CineVerseTheme
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MobileRateSheetTest {
    private val prompt = RatePrompt(MovieDetailDto(42, "Rate fixture").toDetail("US").toTitle(), 7.0, null)

    @Test fun halfStepAccessibilityButtonsSubmitExactValue() = runComposeUiTest {
        var submitted: Double? = null
        setContent { RateFixture(prompt, onSubmit = { submitted = it }) }
        onNodeWithContentDescription("Increase rating by 0.5").performClick()
        onNodeWithText("7.5").assertExists()
        onNodeWithText("Submit rating").performClick()
        runOnIdle { assertEquals(7.5, submitted) }
    }

    @Test fun keyboardArrowsChangeRatingByHalfAndClampToRange() = runComposeUiTest {
        var submitted: Double? = null
        setContent { RateFixture(prompt.copy(initial = 10.0), onSubmit = { submitted = it }) }
        onNodeWithTag("mobile-rating-slider").performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight); keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft) }
        onNodeWithText("Submit rating").performClick()
        runOnIdle { assertEquals(9.5, submitted) }
    }

    @Test fun removeAndDismissAreDistinctRequiredCallbacks() = runComposeUiTest {
        var removed = 0; var dismissed = 0
        setContent { RateFixture(prompt, onRemove = { removed++ }, onDismiss = { dismissed++ }) }
        onNodeWithText("Remove rating").performClick()
        onNodeWithText("Cancel").performClick()
        runOnIdle { assertEquals(1, removed); assertEquals(1, dismissed) }
    }

    @Test fun pendingAndErrorAreHonestAndDoNotReplaySubmit() = runComposeUiTest {
        var pending by mutableStateOf(true)
        var submits = 0
        setContent { RateFixture(prompt, pending, "Rating could not be saved.", onSubmit = { submits++ }) }
        onNodeWithText("Saving…").assertIsNotEnabled()
        onNodeWithText("Remove rating").assertIsNotEnabled()
        onNodeWithText("Rating could not be saved.").assertExists()
        runOnIdle { pending = false }
        onNodeWithText("Submit rating").assertIsEnabled()
        runOnIdle { assertEquals(0, submits) }
    }

    @Test fun newPromptResetsSelectionAndUnratedPromptHasNoRemove() = runComposeUiTest {
        var current by mutableStateOf(prompt)
        setContent { RateFixture(current) }
        onNodeWithContentDescription("Decrease rating by 0.5").performClick()
        onNodeWithText("6.5").assertExists()
        runOnIdle { current = prompt.copy(title = prompt.title.copy(title = "Another title"), initial = null) }
        onNodeWithText("Another title").assertExists()
        onNodeWithText("Remove rating").assertDoesNotExist()
        onNodeWithText("5.0").assertExists()
    }
}

@Composable
private fun RateFixture(prompt: RatePrompt, pending: Boolean = false, error: String? = null,
                        onSubmit: (Double) -> Unit = {}, onRemove: () -> Unit = {}, onDismiss: () -> Unit = {}) {
    CineVerseTheme(ShellStyle.Mobile, AppearanceSettings(haptics = false, reduceMotion = true)) {
        MobileRateSheet(prompt, pending, error, onSubmit, onRemove, onDismiss)
    }
}
