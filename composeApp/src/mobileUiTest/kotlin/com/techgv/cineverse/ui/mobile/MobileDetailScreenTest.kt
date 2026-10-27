package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import androidx.compose.ui.test.*
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.domain.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MobileDetailScreenTest {
    @Test fun movieOverviewShowsRealMetadataReadMoreAndActions() = runComposeUiTest {
        lateinit var model: MobileDetailViewModel
        lateinit var f: MobileDetailComponentFixture
        setContent { MobileDetailUiFixture(onReady = { m, fixture -> model = m; f = fixture }) }
        onNodeWithText("Detail movie en-US").assertExists()
        onNodeWithContentDescription("User score 81 percent").assertExists()
        onNodeWithText("2026 · PG-13 · 2h 8m").assertExists()
        onNodeWithTag("mobile-detail").performScrollToNode(hasText("Read more"))
        onNodeWithText("Read more").performClick()
        runOnIdle { assertTrue(model.state.value.expanded) }
        onNodeWithText("Read less").performClick()
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-watchlist"))
        onNodeWithTag("detail-watchlist").performClick()
        waitForIdle()
        runOnIdle { assertEquals(MediaRef(MediaType.Movie, 42), f.guest.rows.value.single().ref) }
    }

    @Test fun castUsesActualPersonIdAndVideosUseActualYoutubeKey() = runComposeUiTest {
        val destinations = mutableListOf<MobileDestination>()
        setContent { MobileDetailUiFixture(onNavigate = destinations::add) }
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-segments"))
        onNodeWithText("Cast", substring = false).performClick()
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-person-431"))
        onNodeWithTag("detail-person-431").performClick()
        runOnIdle { assertEquals(MobileDestination.Person(431), destinations.last()) }
        onNodeWithText("Videos", substring = false).performClick()
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-video-aB3_dE5-gH7"))
        onNodeWithTag("detail-video-aB3_dE5-gH7").performClick()
        runOnIdle { assertEquals(MobileDestination.Trailer("aB3_dE5-gH7", "Detail movie en-US", "Detail trailer"), destinations.last()) }
    }

    @Test fun overviewRecommendationsNavigateActualRefAndReviewsAreActualContent() = runComposeUiTest {
        val destinations = mutableListOf<MobileDestination>()
        setContent { MobileDetailUiFixture(onNavigate = destinations::add) }
        onNodeWithTag("mobile-detail").performScrollToNode(hasText("More like this"))
        onNodeWithText("Detail recommendation").performClick()
        runOnIdle { assertEquals(MobileDestination.Detail(MediaRef(MediaType.Movie, 765)), destinations.single()) }
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-segments"))
        onNodeWithText("Reviews", substring = false).performClick()
        onNodeWithTag("mobile-detail").performScrollToNode(hasText("Actual review body", substring = true))
        onNodeWithText("Actual review body", substring = true).assertIsDisplayed()
    }

    @Test fun tvSeasonsUseActualNumberIncludingSpecialsAndNoInvalidTrailer() = runComposeUiTest {
        val destinations = mutableListOf<MobileDestination>()
        setContent { MobileDetailUiFixture(media = "tv", onNavigate = destinations::add) }
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-segments"))
        onNodeWithText("Seasons", substring = false).performClick()
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-season-3"))
        onNodeWithTag("detail-season-3").performClick()
        runOnIdle { assertEquals(MobileDestination.Season(42, 3), destinations.last()) }
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-season-0"))
        onNodeWithTag("detail-season-0").performClick()
        runOnIdle { assertEquals(MobileDestination.Season(42, 0), destinations.last()) }
        onNodeWithTag("detail-trailer").assertDoesNotExist()
        onNodeWithText("Videos", substring = false).performClick()
        onNodeWithTag("mobile-detail").performScrollToNode(hasText("No videos available."))
        onNodeWithText("No videos available.").assertIsDisplayed()
    }

    @Test fun catalogErrorHasExplicitRetryAndColdStorageFailureHasReadRecovery() = runComposeUiTest {
        lateinit var f: MobileDetailComponentFixture
        setContent { MobileDetailUiFixture(configure = { it.api.failure = true }, onReady = { _, fixture -> f = fixture }) }
        onNodeWithText("You're offline. Check your connection.").assertIsDisplayed()
        runOnIdle { f.api.failure = false }
        onNodeWithText("Retry").performClick()
        onNodeWithText("Detail movie en-US").assertExists()
        runOnIdle { f.failSettings() }
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-read-recovery"))
        onNodeWithText("Previously loaded title — unverified. Actions disabled.").assertIsDisplayed()
        runOnIdle { f.raw.value = Result.success(com.techgv.cineverse.data.settings.Settings()) }
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("detail-read-recovery"))).performClick()
        waitForIdle()
        onNodeWithTag("detail-read-recovery").assertDoesNotExist()
    }

    @Test fun rateActionOpensExistingPromptAndShareAndBackAreBoundCallbacks() = runComposeUiTest {
        var fixture by mutableStateOf<MobileDetailComponentFixture?>(null)
        var prompt: com.techgv.cineverse.presentation.RatePrompt? = null
        var shared: MediaRef? = null
        var backs = 0
        setContent {
            MobileDetailUiFixture(onReady = { _, f -> fixture = f }, onShare = { shared = it }, onBack = { backs++ })
            LaunchedEffect(fixture) { fixture?.graph?.actions?.ratePrompt?.collect { prompt = it } }
        }
        onNodeWithContentDescription("Share title").performClick()
        runOnIdle { assertEquals(MediaRef(MediaType.Movie, 42), shared) }
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-rate"))
        onNodeWithTag("detail-rate").performClick()
        onNodeWithContentDescription("Back").performClick()
        runOnIdle {
            assertEquals(1, backs)
            assertEquals(MediaRef(MediaType.Movie, 42), assertNotNull(prompt).title.ref)
            assertTrue(assertNotNull(fixture).guest.rows.value.isEmpty())
        }
    }

    @Test fun tvCastAndEmptyReviewsUseActualSections() = runComposeUiTest {
        val destinations = mutableListOf<MobileDestination>()
        setContent { MobileDetailUiFixture(media = "tv", onNavigate = destinations::add) }
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-segments"))
        onNodeWithText("Cast", substring = false).performClick()
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-person-531"))
        onNodeWithTag("detail-person-531").performClick()
        runOnIdle { assertEquals(MobileDestination.Person(531), destinations.single()) }
        onNodeWithText("Reviews", substring = false).performClick()
        onNodeWithTag("mobile-detail").performScrollToNode(hasText("No reviews available."))
        onNodeWithText("No reviews available.").assertIsDisplayed()
    }

    @Test fun favouriteAndPrimaryTrailerActionsUseTheActualTitle() = runComposeUiTest {
        lateinit var f: MobileDetailComponentFixture
        val destinations = mutableListOf<MobileDestination>()
        setContent { MobileDetailUiFixture(onReady = { _, fixture -> f = fixture }, onNavigate = destinations::add) }
        onNodeWithContentDescription("Favourite title")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not favourite"))
            .assertHasClickAction().performClick()
        waitForIdle()
        runOnIdle {
            assertEquals(LibraryKind.FAVOURITE, f.guest.rows.value.single().kind)
            assertEquals(MediaRef(MediaType.Movie, 42), f.guest.rows.value.single().ref)
        }
        onNodeWithContentDescription("Favourite title")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Favourite"))
            .assertHasClickAction().performClick()
        waitForIdle()
        onNodeWithContentDescription("Favourite title")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not favourite"))
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-trailer"))
        onNodeWithTag("detail-trailer").performClick()
        runOnIdle { assertEquals(MobileDestination.Trailer("aB3_dE5-gH7", "Detail movie en-US", "Detail trailer"), destinations.single()) }
    }

    private fun savedOverview() = SavedStateHandle(mapOf(
        "mobileDetail.Overview.index" to 1, "mobileDetail.Overview.offset" to 37, "mobileDetail.expanded" to true))

    private fun assertTarget(saved: SavedStateHandle) {
        assertEquals(1, saved.get<Int>("mobileDetail.Overview.index"))
        assertEquals(37, saved.get<Int>("mobileDetail.Overview.offset"))
    }

    @Test fun delayedCatalogRestoresActualExpandedOverviewAndDoesNotReapplyAfterUserScrollAndRetry() = runComposeUiTest {
        val saved = savedOverview()
        val gate = CompletableDeferred<Unit>()
        lateinit var model: MobileDetailViewModel
        lateinit var f: MobileDetailComponentFixture
        setContent {
            Box(Modifier.requiredSize(360.dp, 640.dp)) {
                MobileDetailUiFixture(savedState = saved, configure = { it.api.gate = gate }, onReady = { m, fixture -> model = m; f = fixture })
            }
        }
        onNodeWithText("Loading title…").assertIsDisplayed()
        runOnIdle { assertTarget(saved); assertFalse(gate.isCompleted) }
        runOnIdle { gate.complete(Unit) }
        onNodeWithText("Read less").assertExists()
        val list = onNodeWithTag("mobile-detail")
        val restored = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        runOnIdle { assertTarget(saved); assertTrue(model.state.value.expanded) }
        list.performScrollToIndex(1)
        val indexOne = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(37f, restored - indexOne)
        waitUntil { saved.get<Int>("mobileDetail.Overview.offset") == 0 }
        list.performScrollToIndex(0)
        waitUntil { saved.get<Int>("mobileDetail.Overview.index") == 0 }
        val userPosition = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        runOnIdle { f.catalog.invalidateTitle(MediaType.Movie, 42); model.retryDetail() }
        waitUntil { f.api.calls.size == 2 && model.state.value.section is com.techgv.cineverse.presentation.SectionState.Content }
        assertEquals(userPosition, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
        runOnIdle {
            assertEquals(0, saved.get<Int>("mobileDetail.Overview.index"))
            assertEquals(0, saved.get<Int>("mobileDetail.Overview.offset"))
        }
    }

    @Test fun navigationDisposalBeforeCatalogCompletionPreservesSavedTarget() = runComposeUiTest {
        val saved = savedOverview()
        val gate = CompletableDeferred<Unit>()
        var shown by mutableStateOf(true)
        setContent {
            if (shown) MobileDetailUiFixture(savedState = saved, configure = { it.api.gate = gate }, onBack = { shown = false })
        }
        onNodeWithText("Loading title…").assertIsDisplayed()
        runOnIdle { assertTarget(saved) }
        onNodeWithContentDescription("Back").performClick()
        onNodeWithTag("mobile-detail").assertDoesNotExist()
        runOnIdle { assertTarget(saved); gate.complete(Unit) }
        runOnIdle { assertTarget(saved) }
    }

    @Test fun initialErrorAndDelayedRetryPreserveTargetUntilActualContentRestores() = runComposeUiTest {
        val saved = savedOverview()
        lateinit var f: MobileDetailComponentFixture
        setContent {
            Box(Modifier.requiredSize(360.dp, 640.dp)) {
                MobileDetailUiFixture(savedState = saved, configure = { it.api.failure = true }, onReady = { _, fixture -> f = fixture })
            }
        }
        onNodeWithText("You're offline. Check your connection.").assertIsDisplayed()
        runOnIdle { assertTarget(saved) }
        val gate = CompletableDeferred<Unit>()
        runOnIdle { f.api.failure = false; f.api.gate = gate }
        onNodeWithText("Retry").performClick()
        onNodeWithText("Loading title…").assertIsDisplayed()
        runOnIdle { assertTarget(saved); gate.complete(Unit) }
        onNodeWithText("Read less").assertExists()
        val list = onNodeWithTag("mobile-detail")
        val restored = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        runOnIdle { assertTarget(saved) }
        list.performScrollToIndex(1)
        assertEquals(37f, restored - list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
    }

    @Test fun retainedUnverifiedLayoutCannotConsumePendingSegmentTargetEvenOnDisposal() = runComposeUiTest {
        val saved = savedOverview()
        saved["mobileDetail.Cast.index"] = 1
        saved["mobileDetail.Cast.offset"] = 37
        lateinit var model: MobileDetailViewModel
        lateinit var f: MobileDetailComponentFixture
        var shown by mutableStateOf(true)
        setContent {
            if (shown) MobileDetailUiFixture(savedState = saved, onReady = { m, fixture -> model = m; f = fixture })
        }
        onNodeWithText("Detail movie en-US").assertExists()
        runOnIdle { f.failSettings() }
        waitUntil { model.state.value.stale }
        runOnIdle { model.selectSegment(MobileDetailSegment.Cast) }
        onNodeWithTag("mobile-detail").assertExists()
        runOnIdle {
            assertEquals(MobileDetailScroll(1, 37), model.scroll(MobileDetailSegment.Cast))
            shown = false
        }
        onNodeWithTag("mobile-detail").assertDoesNotExist()
        runOnIdle {
            assertEquals(1, saved.get<Int>("mobileDetail.Cast.index"))
            assertEquals(37, saved.get<Int>("mobileDetail.Cast.offset"))
        }
    }

    @Test fun invalidRouteOffersBackWithoutAnyTitleFetch() = runComposeUiTest {
        lateinit var f: MobileDetailComponentFixture
        var backs = 0
        setContent { MobileDetailUiFixture(media = "person", onReady = { _, fixture -> f = fixture }, onBack = { backs++ }) }
        onNodeWithText("Invalid title. Go back and choose a movie or TV show.").assertIsDisplayed()
        onNodeWithContentDescription("Back").performClick()
        runOnIdle { assertEquals(1, backs); assertTrue(f.api.calls.isEmpty()) }
    }

    @Test fun unknownAccountDisablesPrivateActionsAndShareIsAbsentWithoutBinding() = runComposeUiTest {
        setContent { MobileDetailUiFixture(configure = {
            // A known private favourite from an unproven origin must not leak through semantics.
            it.snapshots.value = com.techgv.cineverse.data.library.LibraryStateSnapshot(LibraryState(true, false, true, null, emptySet()))
        }) }
        onNodeWithContentDescription("Share title").assertDoesNotExist()
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-watchlist"))
        onNodeWithTag("detail-watchlist").assertIsNotEnabled()
        onNodeWithTag("detail-rate").assertIsNotEnabled()
        onNodeWithContentDescription("Favourite title").assertIsNotEnabled().assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Favourite unknown"))
    }
}
