package com.techgv.cineverse.ui.person

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.techgv.cineverse.data.tmdb.CombinedCreditsDto
import com.techgv.cineverse.data.tmdb.PersonCreditDto
import com.techgv.cineverse.data.tmdb.PersonDetailDto
import com.techgv.cineverse.data.tmdb.toPersonDetail
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.ui.nav.Route
import com.techgv.cineverse.ui.components.focusedScrollableText
import com.techgv.cineverse.ui.components.longText
import com.techgv.cineverse.ui.components.readFocusedScrollableTextByDpad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** §7.5 with real D-pad keys: biography expand button, Known for, Credits, OK opens the title. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PersonScreenDpadTest {
    @get:Rule
    val compose = createComposeRule()

    private val person = PersonDetailDto(
        1, name = "Jane Doe", biography = "A long biography.", knownForDepartment = "Acting",
        combinedCredits = CombinedCreditsDto(
            cast = listOf(
                PersonCreditDto(10, "movie", title = "Famous Film", character = "Hero", releaseDate = "2020-01-01", voteCount = 900),
                PersonCreditDto(20, "tv", name = "Old Show", character = "Guest", firstAirDate = "2001-01-01", voteCount = 5),
            ),
        ),
    ).toPersonDetail()

    @Test
    fun bioButtonFirst_okExpands_downThroughKnownForToCredits_okOpensDetail() {
        var expanded by mutableStateOf(false)
        val navigated = mutableListOf<Route>()
        compose.setContent {
            PersonContent(
                SectionState.Content(person), expanded, FocusMemory(SavedStateHandle()), imageBase = "",
                onToggleBio = { expanded = !expanded }, onRetry = {}, onNavigate = { navigated += it },
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("Read full biography", substring = true).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("Show less", substring = true).assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        // Known for: vote_count desc.
        compose.onNodeWithContentDescription("Famous Film").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        // Acting credits: date desc → the 2020 film first.
        compose.onNodeWithText("Acting credits").assertExists()
        compose.onNodeWithText("as Hero", substring = true).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(Route.Detail(MediaType.Movie, 10), navigated.single())
    }

    @Test
    fun expandedLongBiography_readFullyByDpad_thenBackToShowLess() {
        val longBio = person.copy(biography = longText("Bio"))
        compose.setContent {
            PersonContent(
                SectionState.Content(longBio), bioExpanded = true, FocusMemory(SavedStateHandle()), imageBase = "",
                onToggleBio = {}, onRetry = {}, onNavigate = {},
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("Show less", substring = true).assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        compose.onNode(focusedScrollableText).assertExists()
        assertTrue("several pages", compose.readFocusedScrollableTextByDpad() >= 2)
        compose.onNodeWithText("Show less", substring = true).assertIsFocused()
    }
}
