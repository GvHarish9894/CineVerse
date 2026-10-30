package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WebPersonUiTest {
    @Composable
    private fun Screen(f: WebPersonFixture, revision: Long = 0) {
        WebPerson(f.entry, f.catalog, f.generation, f.settings, f.storage, revision,
            { f.retries++; f.retryHook() }, { f.reports += it }, { f.destinations += it }, { f.backs++ })
    }

    @Test fun desktopRendersActualPersonalInfoAndEnglishBiographyMarker() = runComposeUiTest {
        val f = WebPersonFixture().apply { captured = Settings(language = "ta-IN") }
        f.api.respond = { id, language -> webPersonDto(id, if (language == "en-US") "English fixture biography" else "") }
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Box(Modifier.requiredWidth(1100.dp).height(720.dp)) { Screen(f) }
        }
        onNodeWithTag("person-desktop").assertExists()
        onNodeWithText("Fixture person").assertExists()
        onNodeWithText("Biography (English)").assertExists()
        onNodeWithText("English fixture biography").assertExists()
        onNodeWithText("Jan 2, 1970").assertExists()
        onNodeWithText("Died").assertExists()
        onNodeWithText("Mar 4, 2020").assertExists()
        onAllNodes(hasText("1970-01-02", substring = true) or hasText("2020-03-04", substring = true)).assertCountEquals(0)
        onNodeWithText("Fixture town").assertExists()
        onNodeWithText("Credits").assertExists()
        onNodeWithText("2").assertExists()
        // Known for and the credits table sit in the right column, beside the portrait column, not below it.
        val bio = onNodeWithText("Biography (English)").fetchSemanticsNode().boundsInRoot
        val heading = onNodeWithText("Acting credits").fetchSemanticsNode().boundsInRoot
        val portrait = onNodeWithContentDescription("Portrait of Fixture person").fetchSemanticsNode().boundsInRoot
        assertEquals(bio.left, heading.left)
        assertTrue(heading.left > portrait.right)
        assertEquals(listOf("ta-IN", "en-US"), f.api.requests.map { it.language })
    }

    @Test fun narrowLayoutKeepsMissingDataHonestWithoutInventedControlsOrTotals() = runComposeUiTest {
        val f = WebPersonFixture()
        f.api.respond = { id, _ -> PersonDetailDto(id) }
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Box(Modifier.width(390.dp).height(720.dp)) { Screen(f) }
        }
        onNodeWithTag("person-narrow").assertExists()
        onNodeWithTag("person-desktop").assertDoesNotExist()
        onNodeWithText("Unknown person").assertExists()
        onNodeWithText("No biography available.").assertExists()
        // Missing facts are omitted, never shown as invented or placeholder values.
        onAllNodesWithText("Unknown").assertCountEquals(0)
        listOf("Known for", "Credits", "Birthday", "Died", "Place of birth").forEach { onNodeWithText(it).assertDoesNotExist() }
        onNodeWithText("No personal details were returned.").assertExists()
        onNodeWithTag("web-person").performScrollToNode(hasText("No known-for titles"))
        onNodeWithText("No known-for titles").assertExists()
        onNodeWithTag("web-person").performScrollToNode(hasText("No credits available."))
        onNodeWithText("No credits available.").assertExists()
        onNodeWithText("Play").assertDoesNotExist()
        onNodeWithText("Add to watchlist").assertDoesNotExist()
        onNodeWithText("Read more").assertDoesNotExist()
    }

    @Test fun knownForUsesRealMediaIdsAndNrUnlessThereArePositiveVotes() = runComposeUiTest {
        val f = WebPersonFixture()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Box(Modifier.requiredWidth(1100.dp).height(720.dp)) { Screen(f) }
        }
        onNodeWithTag("web-person").performScrollToNode(hasTestTag("person-known-movie:7"))
        onNodeWithContentDescription("User score 81 percent").assertExists()
        onNodeWithText("NR").assertExists()
        onNodeWithContentDescription("User score 99 percent").assertDoesNotExist()
        onNode(hasClickAction() and hasAnyAncestor(hasTestTag("person-known-movie:7"))).performClick()
        assertEquals(listOf(MediaRef(MediaType.Movie, 7)), f.destinations)
        assertFalse(f.destinations.any { it.id == 42 })
    }

    @Test fun creditsUseActualYearGroupedRoleAndKeyboardTvNavigation() = runComposeUiTest {
        val f = WebPersonFixture()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Box(Modifier.width(390.dp).height(720.dp)) { Screen(f) }
        }
        onNodeWithTag("web-person").performScrollToNode(hasTestTag("person-credit-movie:7"))
        onNodeWithText("2024").assertExists()
        onNodeWithText("as Lead / Other role").assertExists()
        onNodeWithTag("web-person").performScrollToNode(hasTestTag("person-credit-tv:9"))
        onNodeWithText("2023").assertExists()
        onNodeWithText("as Guest").assertExists()
        onNodeWithTag("person-credit-tv:9").requestFocus()
        onNodeWithTag("person-credit-tv:9").assertIsFocused()
        onNodeWithTag("person-credit-tv:9").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf(MediaRef(MediaType.Tv, 9)), f.destinations)
    }

    @Test fun biographyKeyboardEnterAndSpacePersistTheExistingCommonScalar() = runComposeUiTest {
        val f = WebPersonFixture()
        f.entry.putBoolean("bioExpanded", false)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Box(Modifier.requiredWidth(1100.dp).height(720.dp)) { Screen(f) }
        }
        onNodeWithTag("person-biography-toggle").requestFocus()
        onNodeWithTag("person-biography-toggle").assertIsFocused()
        onNodeWithTag("person-biography-toggle").performKeyInput { pressKey(Key.Enter) }
        onNodeWithText("Read less").assertExists()
        assertEquals("true", f.entry.getString("bioExpanded"))
        onNodeWithTag("person-biography-toggle").performKeyInput { pressKey(Key.Spacebar) }
        onNodeWithText("Read more").assertExists()
        assertEquals("false", f.entry.getString("bioExpanded"))
    }

    @Test fun tabAndShiftTabReachBackAndBiographyAndBackUsesRequiredCallback() = runComposeUiTest {
        val f = WebPersonFixture()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Box(Modifier.requiredWidth(1100.dp).height(720.dp)) { Screen(f) }
        }
        onNodeWithText("Back").requestFocus()
        onNodeWithText("Back").performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("person-biography-toggle").assertIsFocused()
        onNodeWithTag("person-biography-toggle").performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        onNodeWithText("Back").assertIsFocused()
        onNodeWithText("Back").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, f.backs)
        assertTrue(f.destinations.isEmpty())
    }

    @Test fun invalidRawBiographyIsRejectedBeforeReaderConstructionAndNeverRepaired() = runComposeUiTest {
        val f = WebPersonFixture()
        f.entry.putString("bioExpanded", "TRUE")
        val before = f.entry.encoded()
        setThemedContent(ShellStyle.Web) { Screen(f) }
        onNodeWithText("Invalid person route or saved state. Saved state was not changed.").assertExists()
        onNodeWithTag("web-person").assertDoesNotExist()
        assertEquals(0, f.subscriptions)
        assertEquals(0, f.api.requests.size)
        assertEquals(before, f.entry.encoded())
    }

    @Test fun settingsFailureShowsStaleRealContentAndExplicitColdRecoveryOnly() = runComposeUiTest {
        val f = WebPersonFixture()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Box(Modifier.requiredWidth(1100.dp).height(720.dp)) { Screen(f) }
        }
        onNodeWithText("Fixture person").assertExists()
        runOnIdle { f.fail() }
        onNodeWithText("Previously loaded person · stale / unverified until replacement content completes.").assertExists()
        onNodeWithTag("person-biography-toggle").assertIsNotEnabled()
        onNodeWithText("Fixture person").assertExists()
        val gate = CompletableDeferred<Unit>()
        runOnIdle {
            f.failure = null
            f.evict()
            f.api.respond = { id, _ -> gate.await(); webPersonDto(id) }
        }
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("person-settings-recovery"))).performClick()
        runOnIdle {
            assertEquals(1, f.retries)
            assertEquals(2, f.subscriptions)
            assertEquals(2, f.api.requests.size)
        }
        onNodeWithText("Previously loaded person · stale / unverified until replacement content completes.").assertExists()
        runOnIdle { gate.complete(Unit) }
        onNodeWithText("Previously loaded person · stale / unverified until replacement content completes.").assertDoesNotExist()
        onNodeWithTag("person-biography-toggle").assertIsEnabled()
    }

    private fun longPerson(id: Int) = webPersonDto(id).copy(combinedCredits = CombinedCreditsDto(cast =
        (100..139).map { PersonCreditDto(it, mediaType = "movie", title = "Credit $it", character = "Role $it", releaseDate = "2024-01-01") }))

    @Test fun delayedContentRestoresActualScrollOffsetThenPersistsWithoutRetryReapplication() = runComposeUiTest {
        val f = WebPersonFixture()
        f.entry.putInt("person.index", 8)
        f.entry.putInt("person.offset", 37)
        val gate = CompletableDeferred<Unit>()
        f.api.respond = { id, _ -> gate.await(); longPerson(id) }
        var revision by mutableStateOf(0L)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Box(Modifier.width(390.dp).height(720.dp)) { Screen(f, revision) }
        }
        onNodeWithContentDescription("Loading person…").assertExists()
        runOnIdle {
            assertEquals(8, f.entry.getInt("person.index"))
            assertEquals(37, f.entry.getInt("person.offset"))
            gate.complete(Unit)
        }
        waitUntil { onAllNodesWithTag("person-credit-movie:104").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("person-credit-movie:104").assertIsDisplayed()
        onNodeWithTag("web-person").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -37f) }
        waitUntil { f.entry.getInt("person.index") == 8 && f.entry.getInt("person.offset") == 0 }
        onNodeWithTag("web-person").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 43f) }
        waitUntil { f.entry.getInt("person.index") == 8 && f.entry.getInt("person.offset") == 43 }
        runOnIdle { revision++ } // Actual root event, not a fake holder completion counter.
        waitUntil { f.subscriptions == 2 }
        onNodeWithTag("person-credit-movie:104").assertIsDisplayed()
        onNodeWithTag("web-person").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -43f) }
        waitUntil { f.entry.getInt("person.index") == 8 && f.entry.getInt("person.offset") == 0 }
        assertEquals(1, f.api.requests.size) // Equal cache hit must not reapply saved offset 37.
    }

    @Test fun initialErrorDoesNotConsumeScrollTargetBeforeExplicitRetryRealLayout() = runComposeUiTest {
        val f = WebPersonFixture()
        f.entry.putInt("person.index", 8)
        f.entry.putInt("person.offset", 37)
        f.api.respond = { _, _ -> throw TmdbException(TmdbError.Network) }
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Box(Modifier.width(390.dp).height(720.dp)) { Screen(f) }
        }
        onNodeWithText("Offline. Check your connection.").assertExists()
        runOnIdle {
            assertEquals(8, f.entry.getInt("person.index"))
            assertEquals(37, f.entry.getInt("person.offset"))
            f.api.respond = { id, _ -> longPerson(id) }
        }
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("person-catalog-recovery"))).performClick()
        waitUntil { onAllNodesWithTag("person-credit-movie:104").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("person-credit-movie:104").assertIsDisplayed()
        onNodeWithTag("web-person").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -37f) }
        waitUntil { f.entry.getInt("person.index") == 8 && f.entry.getInt("person.offset") == 0 }
        assertEquals(2, f.api.requests.size)
        assertEquals(0, f.retries)
    }
}
