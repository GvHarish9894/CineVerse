package com.techgv.cineverse.ui.mobile

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import com.techgv.cineverse.data.library.GuestLibraryRow
import com.techgv.cineverse.data.tmdb.MovieDetailDto
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import kotlin.test.*

/** Prepared integration cases only; source-only handoff does not claim native execution. */
@OptIn(ExperimentalTestApi::class)
class MobileDetailRootTest {
    private val title = MovieDetailDto(101, "Root rating title").toDetail("US").toTitle()

    @Test fun actualHomeTapMountsDetailAndOutboundIdsUseExistingNavigator() = runComposeUiTest {
        setContent { MobileDetailRootUiFixture { _, _ -> } }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        onNodeWithText("Root title 101 en-US").assertExists()
        onNodeWithTag("mobile-detail").assertExists()
        onNodeWithContentDescription("Share title").assertDoesNotExist()
        onNodeWithTag("mobile-detail").performScrollToNode(hasText("Cast"))
        onNodeWithText("Cast").performClick()
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-person-431"))
        onNodeWithTag("detail-person-431").performClick()
        onNodeWithText("Internal placeholder · Person 431").assertIsDisplayed()
        onNodeWithText("Back").performClick()
        onNodeWithTag("mobile-detail").performScrollToNode(hasText("Overview"))
        onNodeWithText("Overview").performClick()
        onNodeWithTag("mobile-detail").performScrollToNode(hasText("Root recommendation"))
        onNodeWithText("Root recommendation").performClick()
        onNodeWithText("Root title 765 en-US").assertExists()
        onNodeWithContentDescription("Back").performClick()
        onNodeWithTag("mobile-detail").performScrollToNode(hasText("Root title 101 en-US"))
        onNodeWithText("Root title 101 en-US").assertExists()
    }

    @Test fun tabBackgroundAndRemovedEntrySynchronouslyRevokeCapturedDetailActions() = runComposeUiTest {
        lateinit var f: MobileDetailRootFixture
        lateinit var owner: MobileDetailRootUiOwner
        setContent { MobileDetailRootUiFixture { fixture, lifecycle -> f = fixture; owner = lifecycle } }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        val beforeBackground = onNodeWithContentDescription("Favourite title").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        runOnIdle {
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            beforeBackground()
            assertTrue(f.guest.rows.value.isEmpty())
        }
        runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        onNodeWithContentDescription("Favourite title").assertIsEnabled()
        runOnIdle { beforeBackground(); assertTrue(f.guest.rows.value.isEmpty()) }
        val beforeTab = onNodeWithContentDescription("Favourite title").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        onNodeWithText("Explore", useUnmergedTree = true).onParent().performSemanticsAction(SemanticsActions.OnClick) {
            it(); beforeTab()
        }
        runOnIdle { assertTrue(f.guest.rows.value.isEmpty()) }
        onNodeWithText("Home", useUnmergedTree = true).performClick()
        onNodeWithText("Root title 101 en-US").assertExists()
        val beforeBack = onNodeWithContentDescription("Favourite title").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        onNodeWithContentDescription("Back").performSemanticsAction(SemanticsActions.OnClick) { it(); beforeBack() }
        runOnIdle { assertTrue(f.guest.rows.value.isEmpty()) }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        onNodeWithContentDescription("Favourite title").assertIsEnabled()
        runOnIdle { beforeBack(); assertTrue(f.guest.rows.value.isEmpty()) }
    }

    @Test fun oneRateHostUsesRealProducerAndClosesBeforeHonestSubmitOutcome() = runComposeUiTest {
        lateinit var f: MobileDetailRootFixture
        setContent { MobileDetailRootUiFixture { fixture, _ -> f = fixture } }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        onNodeWithTag("mobile-detail").performScrollToNode(hasText("Rate"))
        onNodeWithText("Rate", substring = false).performClick()
        onAllNodesWithTag("mobile-rate-sheet").assertCountEquals(1)
        onNodeWithText("Saving rating…").assertDoesNotExist()
        onNodeWithText("Submit rating").performScrollTo().performClick()
        onNodeWithTag("mobile-rate-sheet").assertDoesNotExist()
        runOnIdle { assertEquals(5.0, f.guest.rows.value.single { it.kind == LibraryKind.RATING }.value) }
    }

    @Test fun oldAndValueEqualReplacementPromptCallbacksCannotSubmitRemoveOrDismissNewPrompt() = runComposeUiTest {
        lateinit var f: MobileDetailRootFixture
        setContent { MobileDetailRootUiFixture { fixture, _ -> f = fixture } }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        runOnIdle { f.graph.actions.openRate(title, null) }
        val unratedSubmit = onNodeWithText("Submit rating").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val unratedDismiss = onNodeWithText("Cancel").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        runOnIdle { f.graph.actions.closeRate(); f.graph.actions.openRate(title, null) }
        waitForIdle()
        runOnIdle { unratedSubmit(); unratedDismiss() }
        onAllNodesWithTag("mobile-rate-sheet").assertCountEquals(1)
        runOnIdle { assertTrue(f.guest.rows.value.isEmpty()) }
        onNodeWithText("Submit rating").performScrollTo().performClick()
        onNodeWithTag("mobile-rate-sheet").assertDoesNotExist()
        runOnIdle { f.graph.actions.openRate(title, null) }
        val oldRemove = onNodeWithText("Remove rating").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val oldSubmit = onNodeWithText("Submit rating").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val oldDismiss = onNodeWithText("Cancel").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        runOnIdle { f.graph.actions.closeRate(); f.graph.actions.openRate(title, null) }
        waitForIdle()
        runOnIdle { oldRemove(); oldSubmit(); oldDismiss() }
        onAllNodesWithTag("mobile-rate-sheet").assertCountEquals(1)
        runOnIdle { assertEquals(5.0, f.guest.rows.value.single { it.kind == LibraryKind.RATING }.value) }
        onNodeWithText("Remove rating").performScrollTo().performClick()
        onNodeWithTag("mobile-rate-sheet").assertDoesNotExist()
        runOnIdle { assertTrue(f.guest.rows.value.isEmpty()) }
    }

    @Test fun backgroundRejectsQueuedSheetCallbacksBeforeNextCompositionAndNeverReplays() = runComposeUiTest {
        lateinit var f: MobileDetailRootFixture
        lateinit var owner: MobileDetailRootUiOwner
        setContent { MobileDetailRootUiFixture { fixture, lifecycle -> f = fixture; owner = lifecycle } }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        runOnIdle { f.graph.actions.openRate(title, null) }
        val submit = onNodeWithText("Submit rating").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val dismiss = onNodeWithText("Cancel").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        runOnIdle {
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            submit(); dismiss()
            assertTrue(f.guest.rows.value.isEmpty())
        }
        onNodeWithTag("mobile-rate-sheet").assertDoesNotExist()
        runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        onNodeWithContentDescription("Favourite title").assertIsEnabled()
        onNodeWithTag("mobile-rate-sheet").assertDoesNotExist()
        runOnIdle { assertTrue(f.guest.rows.value.isEmpty()) }
    }

    @Test fun tabTransitionClosesRateBeforeQueuedSubmitOrDismissAndDoesNotReopenOnReturn() = runComposeUiTest {
        lateinit var f: MobileDetailRootFixture
        setContent { MobileDetailRootUiFixture { fixture, _ -> f = fixture } }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        val selectExplore = onNodeWithText("Explore", useUnmergedTree = true).onParent().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        runOnIdle { f.graph.actions.openRate(title, null) }
        val submit = onNodeWithText("Submit rating").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val dismiss = onNodeWithText("Cancel").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        runOnIdle { selectExplore(); submit(); dismiss() }
        onNodeWithTag("mobile-rate-sheet").assertDoesNotExist()
        onNodeWithText("Movies · Popular").assertIsDisplayed()
        onNodeWithText("Home", useUnmergedTree = true).performClick()
        onNodeWithText("Root title 101 en-US").assertExists()
        onNodeWithTag("mobile-rate-sheet").assertDoesNotExist()
        runOnIdle { assertTrue(f.guest.rows.value.isEmpty()) }
    }

    @Test fun actualOriginReadFailureClosesSheetAndExplicitReadRecoveryNeverReplaysRating() = runComposeUiTest {
        lateinit var f: MobileDetailRootFixture
        setContent { MobileDetailRootUiFixture { fixture, _ -> f = fixture } }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        runOnIdle { f.graph.actions.openRate(title, null) }
        val submit = onNodeWithText("Submit rating").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        runOnIdle {
            f.guest.fail = true
            f.guest.rows.value = listOf(GuestLibraryRow(MediaRef(MediaType.Movie, 765), LibraryKind.WATCHLIST, null, 0, "{}"))
        }
        onNodeWithTag("mobile-rate-sheet").assertDoesNotExist()
        onNodeWithContentDescription("Favourite title").assertIsNotEnabled()
        runOnIdle { submit(); f.guest.fail = false }
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-account-recovery"))
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("detail-account-recovery"))).performClick()
        onNodeWithContentDescription("Favourite title").assertIsEnabled()
        onNodeWithTag("mobile-rate-sheet").assertDoesNotExist()
        runOnIdle { assertTrue(f.guest.rows.value.none { it.kind == LibraryKind.RATING }) }
    }

    @Test fun unknownAndFailedIdentityHideRateAndReadRecoveryDoesNotReopenIt() = runComposeUiTest {
        lateinit var f: MobileDetailRootFixture
        setContent { MobileDetailRootUiFixture(knownGuest = false) { fixture, _ -> f = fixture } }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        onNodeWithContentDescription("Favourite title").assertIsNotEnabled()
        runOnIdle { f.graph.actions.openRate(title, null) }
        onNodeWithTag("mobile-rate-sheet").assertDoesNotExist()
        runOnIdle { f.auth.emissions.tryEmit(null) }
        onNodeWithContentDescription("Favourite title").assertIsEnabled()
        onNodeWithTag("mobile-rate-sheet").assertDoesNotExist()
        runOnIdle { f.graph.actions.openRate(title, null) }
        val submit = onNodeWithText("Submit rating").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        runOnIdle { f.auth.fail = true; f.auth.emissions.tryEmit(null) }
        onNodeWithTag("mobile-rate-sheet").assertDoesNotExist()
        runOnIdle { submit(); f.auth.fail = false; f.graph.retryIdentity() }
        onNodeWithTag("mobile-rate-sheet").assertDoesNotExist()
        runOnIdle { assertTrue(f.guest.rows.value.isEmpty()) }
    }

    @Test fun includeAdultOnlyGenerationAndRawSettingsFailureRecoverTheSameEntryWithoutReplayingWrites() = runComposeUiTest {
        lateinit var f: MobileDetailRootFixture
        setContent { MobileDetailRootUiFixture { fixture, _ -> f = fixture } }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        onNodeWithContentDescription("Favourite title").assertIsEnabled()
        var generation = 0L
        var calls = 0
        runOnIdle {
            generation = f.generation.current; calls = f.api.detailCalls.size
            f.store.values.value = mapOf("include_adult" to true)
        }
        onNodeWithContentDescription("Favourite title").assertIsEnabled()
        runOnIdle { assertTrue(f.generation.current != generation); assertTrue(f.api.detailCalls.size > calls) }
        runOnIdle { f.graph.actions.openRate(title, null) }
        onNodeWithTag("mobile-rate-sheet").assertExists()
        runOnIdle { f.store.fail = true; f.store.values.value = mapOf("include_adult" to true, "language" to "hi-IN") }
        onNodeWithTag("mobile-rate-sheet").assertDoesNotExist()
        onNodeWithContentDescription("Favourite title").assertIsNotEnabled()
        runOnIdle { f.store.fail = false }
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-read-recovery"))
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("detail-read-recovery"))).performClick()
        onNodeWithText("Root title 101 hi-IN").assertExists()
        onNodeWithContentDescription("Favourite title").assertIsEnabled()
        runOnIdle { assertTrue(f.guest.rows.value.isEmpty()) }
    }

    @Test fun trailerRouteClosesRootRateAndLeavesPlaybackImplementationPending() = runComposeUiTest {
        lateinit var f: MobileDetailRootFixture
        setContent { MobileDetailRootUiFixture { fixture, _ -> f = fixture } }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-trailer"))
        val trailer = onNodeWithTag("detail-trailer").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        runOnIdle { f.graph.actions.openRate(title, null) }
        onNodeWithTag("mobile-rate-sheet").assertExists()
        runOnIdle { trailer() }
        onNodeWithTag("mobile-rate-sheet").assertDoesNotExist()
        onNodeWithText("Internal placeholder · Root trailer. Trailer playback is not implemented.").assertExists()
    }
}
