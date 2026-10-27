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

@OptIn(ExperimentalTestApi::class)
class MobileSeasonScreenTest {
    @Test fun actualHeaderNetworkSeasonAndSparseOptionsNavigateWithoutRetargeting() = runComposeUiTest {
        val destinations = mutableListOf<MobileDestination>()
        setContent { SeasonFixture(onNavigate = destinations::add) }
        onNodeWithText("Actual show en-US").assertExists()
        onNodeWithText("Actual network").assertExists()
        onNodeWithTag("season-title").assertTextEquals("Season 3")
        onNodeWithTag("season-option-0").performClick()
        runOnIdle { assertEquals(MobileDestination.Season(42, 0), destinations.last()) }
        onNodeWithTag("season-option-7").performClick()
        runOnIdle { assertEquals(MobileDestination.Season(42, 7), destinations.last()) }
        onNodeWithTag("season-show").performClick()
        runOnIdle { assertEquals(MobileDestination.Detail(MediaRef(MediaType.Tv, 42)), destinations.last()) }
        onNodeWithTag("season-title").assertTextEquals("Season 3")
    }

    @Test fun episodeMetadataIsActualAndExpansionIsAccessibleMetadataNotPlayback() = runComposeUiTest {
        setContent { SeasonFixture() }
        onNodeWithTag("mobile-season").performScrollToNode(hasTestTag("season-episode-1"))
        onNodeWithText("Actual episode 1").assertExists()
        onNodeWithText("2020-01-02 · 42m · ★ 8.2").assertExists()
        onNodeWithTag("season-episode-1").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        onNodeWithContentDescription("Expand episode 1 details").performClick()
        onNodeWithTag("season-episode-1").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        onNodeWithContentDescription("Collapse episode 1 details").performClick()
        onNodeWithText("Play").assertDoesNotExist()
    }

    @Test fun unairedAndUnknownMetadataDoNotInventRuntimeRatingOrDate() = runComposeUiTest {
        setContent { SeasonFixture() }
        onNodeWithTag("mobile-season").performScrollToNode(hasTestTag("season-episode-2"))
        onNodeWithText("Airs 2099-01-02").assertExists()
        onNodeWithTag("mobile-season").performScrollToNode(hasTestTag("season-episode-3"))
        onNodeWithTag("season-meta-3").assertDoesNotExist()
    }

    @Test fun specialsAndUnavailableRouteRemainRealAndNoAlternativeIsSelected() = runComposeUiTest {
        lateinit var f: MobileSeasonComponentFixture
        setContent { SeasonFixture(number = 9, onReady = { _, fixture -> f = fixture }) }
        onNodeWithText("This season is not available.").assertExists()
        onNodeWithTag("season-option-3").assertIsNotSelected()
        onNodeWithTag("season-option-0").assertIsNotSelected()
        runOnIdle { assertEquals(listOf("tv/42/en-US"), f.api.calls) }
    }

    @Test fun episodeErrorPreservesShowAndHasIndependentRetry() = runComposeUiTest {
        lateinit var f: MobileSeasonComponentFixture
        setContent { SeasonFixture(configure = { it.api.episodeFailure = true }, onReady = { _, fixture -> f = fixture }) }
        onNodeWithText("Actual show en-US").assertExists()
        onNodeWithTag("season-episodes-error").assertExists()
        runOnIdle { f.api.episodeFailure = false }
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("season-episodes-error"))).performClick()
        onNodeWithTag("mobile-season").performScrollToNode(hasTestTag("season-episode-1"))
        onNodeWithText("Actual episode 1").assertExists()
        runOnIdle { assertEquals(1, f.api.calls.count { it.startsWith("tv/") }) }
    }

    @Test fun showErrorRetriesAndColdStorageFailureIsExplicit() = runComposeUiTest {
        lateinit var f: MobileSeasonComponentFixture
        setContent { SeasonFixture(configure = { it.api.showFailure = true }, onReady = { _, fixture -> f = fixture }) }
        onNodeWithTag("season-show-error").assertExists()
        runOnIdle { f.api.showFailure = false }
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("season-show-error"))).performClick()
        onNodeWithText("Actual show en-US").assertExists()
        runOnIdle { f.failSettings() }
        onNodeWithTag("mobile-season").performScrollToNode(hasTestTag("season-read-recovery"))
        onNodeWithText("Previously loaded metadata — unverified. Actions disabled.").assertExists()
        runOnIdle { f.raw.value = Result.success(Settings()) }
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("season-read-recovery"))).performClick()
        onNodeWithTag("season-read-recovery").assertDoesNotExist()
    }

    @Test fun showOnlyCompletionCannotClampSavedEpisodeScrollAndDisposalPreservesIt() = runComposeUiTest {
        val saved = target(); val gate = CompletableDeferred<Unit>(); var shown by mutableStateOf(true)
        setContent { if (shown) SeasonFixture(saved = saved, configure = { it.api.episodeGate = gate }, onBack = { shown = false }) }
        onNodeWithText("Loading episodes…").assertExists()
        runOnIdle { assertTarget(saved) }
        onNodeWithContentDescription("Back").performClick()
        onNodeWithTag("mobile-season").assertDoesNotExist()
        runOnIdle { assertTarget(saved); gate.complete(Unit) }
        runOnIdle { assertTarget(saved) }
    }

    @Test fun completedEpisodesRestoreSavedPositionThenPersistUserScroll() = runComposeUiTest {
        val saved = target(); val gate = CompletableDeferred<Unit>()
        setContent { Box(Modifier.requiredSize(360.dp, 640.dp)) { SeasonFixture(saved = saved, configure = { it.api.episodeGate = gate }) } }
        runOnIdle { assertTarget(saved); gate.complete(Unit) }
        waitUntil { onAllNodesWithTag("season-episode-3").fetchSemanticsNodes().isNotEmpty() }
        val list = onNodeWithTag("mobile-season")
        val restored = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        list.performScrollToIndex(5)
        val indexFive = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(37f, restored - indexFive)
        waitUntil { saved.get<Int>("mobileSeason.offset") == 0 }
        list.performScrollToIndex(0)
        waitUntil { saved.get<Int>("mobileSeason.index") == 0 }
    }

    @Test fun retainedOffscreenRecoveryDoesNotWaitForHeaderToBecomeVisible() = runComposeUiTest {
        val saved = SavedStateHandle(); lateinit var f: MobileSeasonComponentFixture
        setContent { Box(Modifier.requiredSize(360.dp, 640.dp)) { SeasonFixture(saved = saved, onReady = { _, fixture -> f = fixture }) } }
        onNodeWithTag("mobile-season").performScrollToIndex(8)
        waitUntil { saved.get<Int>("mobileSeason.index") == 8 }
        runOnIdle { f.failSettings() }
        onNodeWithTag("mobile-season").performScrollToIndex(9)
        runOnIdle { f.raw.value = Result.success(Settings()) }
        // Retry via the owner while the real header remains offscreen.
        runOnIdle { activeModel!!.retryReads() }
        waitForIdle()
        onNodeWithTag("mobile-season").performScrollToIndex(10)
        waitUntil { saved.get<Int>("mobileSeason.index") == 10 }
    }

    @Test fun outOfRangeSavedTargetIsNotOverwrittenByAutomaticClamp() = runComposeUiTest {
        val saved = SavedStateHandle(mapOf("mobileSeason.index" to 500, "mobileSeason.offset" to 37))
        setContent { SeasonFixture(saved = saved) }
        waitForIdle()
        runOnIdle { assertEquals(500, saved.get<Int>("mobileSeason.index")); assertEquals(37, saved.get<Int>("mobileSeason.offset")) }
        onNodeWithTag("mobile-season").performScrollToIndex(0)
        waitUntil { saved.get<Int>("mobileSeason.index") == 0 }
    }

    @Test fun invalidEntryStillOffersBackWithoutLoading() = runComposeUiTest {
        var backs = 0
        setContent { SeasonFixture(number = -1, onBack = { backs++ }) }
        onNodeWithText("Invalid season. Go back and choose a season.").assertExists()
        onNodeWithContentDescription("Back").performClick()
        runOnIdle { assertEquals(1, backs) }
    }

    private var activeModel: MobileSeasonViewModel? = null
    private fun target() = SavedStateHandle(mapOf("mobileSeason.index" to 5, "mobileSeason.offset" to 37))
    private fun assertTarget(saved: SavedStateHandle) { assertEquals(5, saved.get<Int>("mobileSeason.index")); assertEquals(37, saved.get<Int>("mobileSeason.offset")) }

    @Composable
    private fun SeasonFixture(number: Int = 3, saved: SavedStateHandle = SavedStateHandle(),
        configure: (MobileSeasonComponentFixture) -> Unit = {},
        onReady: (MobileSeasonViewModel, MobileSeasonComponentFixture) -> Unit = { _, _ -> },
        onNavigate: (MobileDestination) -> Unit = {}, onBack: () -> Unit = {}) {
        val scope = rememberCoroutineScope()
        val f = remember { MobileSeasonComponentFixture(scope).also(configure) }
        val model = remember { MobileSeasonViewModel(f.graph, 42, number, saved, f.rawSettings, scope) }
        DisposableEffect(model) { model.setActive(true); onDispose { model.close(); f.graph.close() } }
        SideEffect { activeModel = model; onReady(model, f) }
        CineVerseTheme(ShellStyle.Mobile, AppearanceSettings(haptics = false, reduceMotion = true)) {
            ProvideWindowClass { MobileSeasonScreen(model, MobileNavigator(onNavigate), onBack) }
        }
    }
}
