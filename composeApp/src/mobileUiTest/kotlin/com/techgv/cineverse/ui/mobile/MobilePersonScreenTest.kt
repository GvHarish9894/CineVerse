package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.ui.adaptive.ProvideWindowClass
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.theme.CineVerseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.*

/** Original eight cases previously compiled only; scroll revisions/additions are source-only. */
@OptIn(ExperimentalTestApi::class)
class MobilePersonScreenTest {
    @Test fun actualMetadataAndFourLineBiographyExpandAndCollapse() = runComposeUiTest {
        lateinit var model: MobilePersonViewModel
        setContent { MobilePersonUiFixture(onReady = { m, _ -> model = m }) }
        onNodeWithText("Person en-US").assertExists()
        onNodeWithText("Acting").assertExists()
        onNodeWithText("Born 1970-01-02").assertExists()
        onNodeWithText("Died 2020-03-04").assertExists()
        onNodeWithText("Actual birthplace").assertExists()
        onNodeWithTag("mobile-person").performScrollToNode(hasText("Read more"))
        onNodeWithText("Read more").performClick()
        runOnIdle { assertTrue(model.state.value.expanded) }
        onNodeWithTag("mobile-person").performScrollToNode(hasText("Read less"))
        onNodeWithText("Read less").performClick()
        runOnIdle { assertFalse(model.state.value.expanded) }
    }

    @Test fun knownForAndCreditRowsNavigateActualMediaAndIdAndShowActualRoles() = runComposeUiTest {
        val destinations = mutableListOf<MobileDestination>()
        setContent { MobilePersonUiFixture(onNavigate = destinations::add) }
        onNodeWithTag("mobile-person").performScrollToNode(hasText("Known for"))
        onNodeWithTag("person-known-tv:701").performClick()
        runOnIdle { assertEquals(MobileDestination.Detail(MediaRef(MediaType.Tv, 701)), destinations.last()) }
        onNodeWithTag("mobile-person").performScrollToNode(hasTestTag("person-credit-movie:702"))
        onNodeWithTag("person-credit-movie:702").assertTextContains("Actual credit 2").assertTextContains("as Actual role 2").assertTextContains("2025").performClick()
        runOnIdle { assertEquals(MobileDestination.Detail(MediaRef(MediaType.Movie, 702)), destinations.last()) }
    }

    @Test fun englishFallbackMarkerAndEmptyCreditsAreHonest() = runComposeUiTest {
        setContent { MobilePersonUiFixture(configure = {
            it.api.blankLocalized = true; it.api.empty = true
            it.raw.value = Result.success(Settings(language = "fr-FR"))
        }) }
        onNodeWithTag("mobile-person").performScrollToNode(hasText("(English)"))
        onNodeWithText("(English)").assertExists()
        onNodeWithTag("mobile-person").performScrollToNode(hasText("No credits available."))
        onNodeWithText("No credits available.").assertIsDisplayed()
        onNodeWithText("Known for").assertDoesNotExist()
    }

    @Test fun absentMetadataDoesNotInventDepartmentOrLifeDates() = runComposeUiTest {
        setContent { MobilePersonUiFixture(configure = { it.api.omitMetadata = true }) }
        onNodeWithText("Person en-US").assertExists()
        onNodeWithText("Acting").assertDoesNotExist()
        onNodeWithText("Born", substring = true).assertDoesNotExist()
        onNodeWithText("Died", substring = true).assertDoesNotExist()
        onNodeWithText("Actual birthplace").assertDoesNotExist()
        onNodeWithTag("mobile-person").performScrollToNode(hasText("Credits", substring = false))
        onNodeWithText("Credits", substring = false).assertExists()
    }

    @Test fun invalidSavedScalarKeepsBackReachableAndDoesNotFetchOrRepair() = runComposeUiTest {
        val saved = SavedStateHandle(mapOf("bioExpanded" to "true"))
        lateinit var fixture: MobilePersonComponentFixture
        var backs = 0
        setContent { MobilePersonUiFixture(saved = saved, onReady = { _, f -> fixture = f }, onBack = { backs++ }) }
        onNodeWithText("Invalid person or saved state. Go back to choose a person.").assertIsDisplayed()
        onNodeWithContentDescription("Back").performClick()
        runOnIdle { assertEquals(1, backs); assertTrue(fixture.api.calls.isEmpty()); assertEquals("true", saved.get<Any>("bioExpanded")) }
    }

    @Test fun catalogAndRawErrorsOfferExplicitRecoveryWithoutInventedContent() = runComposeUiTest {
        lateinit var fixture: MobilePersonComponentFixture
        setContent { MobilePersonUiFixture(configure = { it.api.failure = true }, onReady = { _, f -> fixture = f }) }
        onNodeWithText("You're offline. Check your connection.").assertIsDisplayed()
        runOnIdle { fixture.api.failure = false }
        onNodeWithText("Retry").performClick()
        onNodeWithText("Person en-US").assertExists()
        runOnIdle { fixture.failRaw() }
        onNodeWithText("Saved preferences unavailable. Retry reads.").assertIsDisplayed()
        onNodeWithText("Person en-US").assertDoesNotExist()
        runOnIdle { fixture.raw.value = Result.success(Settings()) }
        onNodeWithText("Retry").performClick()
        onNodeWithText("Person en-US").assertExists()
    }

    @Test fun pendingLoadingAndErrorNeverOverwriteSavedScrollAndRealTopologyRestoresOffscreen() = runComposeUiTest {
        val saved = SavedStateHandle(mapOf("person.index" to 20, "person.offset" to 17))
        val gate = CompletableDeferred<Unit>()
        lateinit var model: MobilePersonViewModel
        lateinit var fixture: MobilePersonComponentFixture
        setContent { MobilePersonUiFixture(saved = saved, configure = { it.api.gate = gate }, onReady = { m, f -> model = m; fixture = f }) }
        onNodeWithText("Loading person…").assertIsDisplayed()
        runOnIdle {
            assertEquals(20, saved.get<Int>("person.index")); assertEquals(17, saved.get<Int>("person.offset"))
            gate.complete(Unit)
        }
        val list = onNodeWithTag("mobile-person")
        // Compose encodes index * 500 + pixel offset; assert restoration before driving the list.
        val position = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(10017f, position)
        runOnIdle { assertEquals(20, model.state.value.index); assertEquals(17, model.state.value.offset) }
        runOnIdle { model.contextChanged() }
        onNodeWithTag("mobile-person").performScrollToIndex(21)
        waitUntil { saved.get<Int>("person.index") == 21 }
        runOnIdle {
            fixture.api.gate = null; fixture.api.failure = true
            fixture.raw.value = Result.success(Settings(language = "fr-FR"))
        }
        onNodeWithText("You're offline. Check your connection.").assertIsDisplayed()
        runOnIdle { assertEquals(21, saved.get<Int>("person.index")); fixture.api.failure = false }
        onNodeWithText("Retry").performClick()
        val recovered = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(10500f, recovered)
        list.performScrollToIndex(22)
        waitUntil { saved.get<Int>("person.index") == 22 }
    }

    @Test fun shorterLanguageContextAndDisposalPreserveDeepTargetBeforeLongContentRestoresAndUserScrollPersists() = runComposeUiTest {
        val saved = SavedStateHandle(mapOf("person.index" to 20, "person.offset" to 17))
        var shown by mutableStateOf(true)
        lateinit var model: MobilePersonViewModel
        lateinit var fixture: MobilePersonComponentFixture
        setContent {
            Box(Modifier.requiredSize(400.dp, 600.dp)) {
                MobilePersonUiFixture(saved = saved, shown = shown, onReady = { m, f -> model = m; fixture = f })
            }
        }
        val list = onNodeWithTag("mobile-person")
        val original = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(10017f, original)
        runOnIdle {
            fixture.api.empty = true
            fixture.raw.value = Result.success(Settings(language = "fr-FR"))
        }
        onNodeWithText("No credits available.").assertExists()
        val clamped = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(clamped < 10000f)
        runOnIdle {
            assertEquals(20, saved.get<Int>("person.index")); assertEquals(17, saved.get<Int>("person.offset"))
            shown = false
        }
        onNodeWithTag("mobile-person").assertDoesNotExist()
        runOnIdle {
            assertEquals(20, saved.get<Int>("person.index")); assertEquals(17, saved.get<Int>("person.offset"))
            fixture.api.empty = false
            fixture.raw.value = Result.success(Settings(language = "de-DE"))
            shown = true
        }
        assertEquals(original, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
        runOnIdle {
            assertEquals(listOf("42/en-US", "42/fr-FR", "42/de-DE"), fixture.api.calls)
            assertEquals(20, saved.get<Int>("person.index")); assertEquals(17, saved.get<Int>("person.offset"))
        }
        list.performTouchInput { swipeUp() }
        runOnIdle {
            assertTrue(saved.get<Int>("person.index")!! > 20)
            assertEquals(saved.get<Int>("person.index"), model.state.value.index)
            assertEquals(saved.get<Int>("person.offset"), model.state.value.offset)
        }
    }

    @Test fun retainedOwnerRemountRestoresLatestSavedPositionWithoutRecreatingOwner() = runComposeUiTest {
        val saved = SavedStateHandle(mapOf("person.index" to 20, "person.offset" to 17))
        var shown by mutableStateOf(true)
        lateinit var model: MobilePersonViewModel
        setContent {
            Box(Modifier.requiredSize(400.dp, 600.dp)) {
                MobilePersonUiFixture(saved = saved, shown = shown, onReady = { m, _ -> model = m })
            }
        }
        val list = onNodeWithTag("mobile-person")
        val initial = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(10017f, initial)
        val retained = model
        list.performScrollToIndex(23)
        list.performTouchInput { swipeUp() }
        val latest = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(latest > 11500f)
        var latestIndex = 0
        var latestOffset = 0
        runOnIdle {
            latestIndex = saved.get<Int>("person.index")!!
            latestOffset = saved.get<Int>("person.offset")!!
            assertTrue(latestIndex >= 23)
            assertEquals(latest, (latestIndex * 500 + latestOffset).toFloat())
            shown = false
        }
        onNodeWithTag("mobile-person").assertDoesNotExist()
        runOnIdle { shown = true }
        assertEquals(latest, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
        runOnIdle {
            assertSame(retained, model)
            assertEquals(latestIndex, saved.get<Int>("person.index")); assertEquals(latestOffset, saved.get<Int>("person.offset"))
        }
    }

    @Test fun deliberateMovementFromClampedBaselineBecomesTheSavedTarget() = runComposeUiTest {
        val saved = SavedStateHandle(mapOf("bioExpanded" to true, "person.index" to 20, "person.offset" to 17))
        lateinit var fixture: MobilePersonComponentFixture
        setContent {
            Box(Modifier.requiredSize(400.dp, 600.dp)) {
                MobilePersonUiFixture(saved = saved, onReady = { _, f -> fixture = f })
            }
        }
        val list = onNodeWithTag("mobile-person")
        val original = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(10017f, original)
        runOnIdle {
            fixture.api.empty = true
            fixture.raw.value = Result.success(Settings(language = "fr-FR"))
        }
        val baseline = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(baseline < 10000f)
        runOnIdle { assertEquals(20, saved.get<Int>("person.index")); assertEquals(17, saved.get<Int>("person.offset")) }
        list.performTouchInput { swipeDown() }
        val moved = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(moved < baseline)
        var movedIndex = 0
        var movedOffset = 0
        runOnIdle {
            movedIndex = saved.get<Int>("person.index")!!
            movedOffset = saved.get<Int>("person.offset")!!
            assertTrue(movedIndex < 20)
            assertEquals(moved, (movedIndex * 500 + movedOffset).toFloat())
            fixture.api.empty = false
            fixture.raw.value = Result.success(Settings(language = "de-DE"))
        }
        assertEquals(moved, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
        runOnIdle { assertEquals(movedIndex, saved.get<Int>("person.index")); assertEquals(movedOffset, saved.get<Int>("person.offset")) }
    }

    @Test fun wrongPrimaryNeverDisplaysMetadataAndDisposedCallbacksHaveNoAuthority() = runComposeUiTest {
        var shown by mutableStateOf(true)
        lateinit var model: MobilePersonViewModel
        var backs = 0
        setContent { if (shown) MobilePersonUiFixture(configure = { it.api.wrongId = true }, onReady = { m, _ -> model = m }, onBack = { backs++ }) }
        onNodeWithText("Person en-US").assertDoesNotExist()
        onNodeWithText("Not available.").assertExists()
        var token = 0L
        runOnIdle { token = model.state.value.context; shown = false }
        runOnIdle { model.back(token) { backs++ }; model.toggleBiography(token); assertEquals(0, backs) }
    }
}

@Composable
private fun MobilePersonUiFixture(
    saved: SavedStateHandle = SavedStateHandle(),
    shown: Boolean = true,
    configure: (MobilePersonComponentFixture) -> Unit = {},
    onReady: (MobilePersonViewModel, MobilePersonComponentFixture) -> Unit = { _, _ -> },
    onNavigate: (MobileDestination) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val fixture = remember { MobilePersonComponentFixture(scope).also(configure) }
    val model = remember { fixture.model(saved = saved) }
    DisposableEffect(model) { onDispose { model.close(); fixture.graph.close() } }
    SideEffect { model.setActive(shown); onReady(model, fixture) }
    CineVerseTheme(ShellStyle.Mobile, AppearanceSettings(haptics = false, reduceMotion = true)) {
        ProvideWindowClass { if (shown) MobilePersonScreen(model, MobileNavigator(onNavigate), onBack) }
    }
}
