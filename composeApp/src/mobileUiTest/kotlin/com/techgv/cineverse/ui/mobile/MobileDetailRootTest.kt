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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.*

private class RenderedDetailAction(
    val model: MobileDetailViewModel,
    val authority: MobileDetailAuthority?,
    val onAct: (LibraryKind) -> Unit,
)

/** Prepared integration cases only; source-only handoff does not claim native execution. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class MobileDetailRootTest {
    private val title = MovieDetailDto(101, "Root rating title").toDetail("US").toTitle()

    @Test fun detachPublicationCannotEraseReentrantReplacement() = reentrantDeactivation("detach")
    @Test fun selectionPublicationCannotOverwriteReentrantReplacement() = reentrantDeactivation("selection")
    @Test fun leavePublicationCannotCloseReentrantReplacementPrompt() = reentrantDeactivation("leave")
    @Test fun backgroundPublicationCannotDeactivateReentrantReplacement() = reentrantDeactivation("background")
    @Test fun attachPublicationCannotOverwriteReentrantReplacement() = reentrantDeactivation("attach")

    private fun reentrantDeactivation(operation: String) = runComposeUiTest {
        val scheduler = TestCoroutineScheduler()
        lateinit var root: MobileDetailRootBindingUiState
        lateinit var replacement: MobileRootRateAuthority
        setContent { MobileDetailRootBindingUiFixture(scheduler) { root = it } }
        waitForIdle()
        waitUntil { root.entryA.lifecycle.currentState == Lifecycle.State.RESUMED &&
            root.entryB.lifecycle.currentState == Lifecycle.State.RESUMED }
        runOnIdle {
            scheduler.runCurrent()
            assertNotNull(root.fixture.graph.settings.value)
            assertTrue(root.fixture.auth.emissions.tryEmit(null))
            scheduler.runCurrent()
            assertEquals(MobileIdentity(null), root.fixture.graph.identity.value)
            assertTrue(root.observedPrompts.isNotEmpty(), "Real prompt collector must be registered before activation")
            assertNull(root.observedPrompts.first())
            assertNull(root.latestPrompt)
            assertTrue(root.fixture.store.subscriptions > 0)
            root.activateA()
            scheduler.runCurrent()
        }
        waitUntil { root.modelA.state.value.authority != null }
        runOnIdle {
            assertTrue(root.modelA.state.value.account.known)
            root.fixture.graph.actions.openRate(title, null)
            scheduler.runCurrent()
        }
        waitUntil { root.latestPrompt != null }
        runOnIdle {
            val f = root.fixture
            val old = assertNotNull(root.binding.rateAuthority(assertNotNull(root.latestPrompt)))
            val aRead = assertNotNull(root.modelA.state.value.authority)
            assertSame(root.entryA, old.entry)
            assertSame(root.modelA, old.detailOwner)
            assertEquals(aRead, old.detailAuthority)
            assertEquals(title.ref, aRead.context.ref)
            assertEquals(f.generation.current, aRead.context.appGeneration)
            assertEquals(f.graph.identity.value, aRead.identity)
            assertTrue(title.ref in f.guest.readStarts)
            assertSame(old.prompt, root.observedPrompts.last())
            val bRef = MediaRef(MediaType.Movie, 765)
            assertTrue(f.guest.readStarts.none { it == bRef }, "B must still be cold")
            assertTrue(f.api.detailCalls.none { it.startsWith("movie/765/") })
            val subscriptionsBeforeB = f.store.subscriptions
            var reentered = false
            var outerReturned = false
            var initialObservation: MobileDetailAuthority? = null
            val observer = root.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                root.modelA.state.collect {
                    if (!reentered && it.authority != null) initialObservation = it.authority
                    if (!reentered && it.authority == null) {
                        reentered = true
                        assertEquals(aRead, initialObservation, "Observer must first see actual A authority")
                        assertFalse(outerReturned, "Replacement must be accepted inside synchronous revocation")
                        root.binding.setResumed(true)
                        root.activateB()
                        scheduler.runCurrent()
                        val bRead = assertNotNull(root.modelB.state.value.authority)
                        val settings = assertNotNull(f.graph.settings.value)
                        assertEquals(bRef, bRead.context.ref)
                        assertEquals(settings.language, bRead.context.language)
                        assertEquals(settings.region, bRead.context.region)
                        assertEquals(f.generation.current, bRead.context.appGeneration)
                        assertEquals(assertNotNull(f.graph.identity.value), bRead.identity)
                        assertEquals(MobileIdentity(null), bRead.identity)
                        assertFalse(f.graph.settingsFailed.value)
                        assertFalse(f.graph.identityFailed.value)
                        assertFalse(f.graph.storageFailed.value)
                        assertTrue(root.modelB.state.value.account.known)
                        assertFalse(root.modelB.state.value.libraryFailed)
                        assertEquals(1, f.guest.readStarts.count { it == bRef }, "Actual B account cold read must start before openRate")
                        assertTrue(f.store.subscriptions > subscriptionsBeforeB, "Actual B settings reader must start")
                        assertTrue("movie/765/${settings.language}" in f.api.detailCalls)
                        f.graph.actions.openRate(MovieDetailDto(765, "Replacement").toDetail("US").toTitle(), null)
                        scheduler.runCurrent()
                        val prompt = assertNotNull(root.latestPrompt)
                        assertEquals(bRef, prompt.title.ref)
                        assertSame(prompt, root.observedPrompts.last(), "Use the real producer emission")
                        assertEquals(2, f.guest.readStarts.count { it == bRef }, "Real producer must read the rating too")
                        replacement = assertNotNull(root.binding.rateAuthority(prompt))
                        assertSame(root.entryB, replacement.entry)
                        assertSame(root.modelB, replacement.detailOwner)
                        assertEquals(bRead, replacement.detailAuthority)
                        assertEquals(bRead, root.modelB.state.value.authority)
                        assertFalse(outerReturned)
                    }
                }
            }
            try {
                assertEquals(aRead, initialObservation, "UNDISPATCHED registration must complete before outer operation")
                when (operation) {
                    "detach" -> root.binding.detach(root.entryA, root.modelA)
                    "selection" -> root.binding.currentChanged(root.entryB)
                    "leave" -> root.binding.leave()
                    "background" -> root.binding.setResumed(false)
                    "attach" -> root.binding.attach(root.entryB, root.modelB)
                    else -> error("Unknown test operation")
                }
                outerReturned = true
                assertTrue(reentered, "Must reenter from actual synchronous A deactivation")
                assertSame(replacement.prompt, root.latestPrompt)
                assertSame(replacement, root.binding.rateAuthority(replacement.prompt))
                assertSame(root.entryB, replacement.entry)
                assertSame(root.modelB, replacement.detailOwner)
                assertEquals(replacement.detailAuthority, root.modelB.state.value.authority)
                root.binding.submit(old, 9.0); root.binding.remove(old); root.binding.dismiss(old)
                assertTrue(f.guest.rows.value.isEmpty())
                assertSame(replacement.prompt, root.latestPrompt, "Old callbacks must not close replacement immediately")
                assertSame(replacement, root.binding.rateAuthority(replacement.prompt))
                scheduler.runCurrent()
                assertTrue(f.guest.rows.value.isEmpty())
                assertSame(replacement.prompt, root.latestPrompt, "Old callbacks must not close replacement after drain")
                assertSame(replacement, root.binding.rateAuthority(replacement.prompt))
                assertEquals(replacement.detailAuthority, root.modelB.state.value.authority)
            } finally {
                observer.cancel()
                scheduler.runCurrent()
            }
        }
        waitUntil { root.modelB.state.value.authority != null && root.latestPrompt?.title?.ref?.id == 765 }
        runOnIdle {
            assertSame(replacement, root.binding.rateAuthority(assertNotNull(root.latestPrompt)))
            val action = assertNotNull(root.modelB.state.value.authority)
            assertEquals(1, root.fixture.guest.activeReads)
            if (operation == "background" || operation == "attach") root.binding.leave()
            else root.binding.setResumed(false)
            assertNull(root.modelB.state.value.authority, "Logical authority is revoked synchronously")
            scheduler.runCurrent()
            assertEquals(0, root.fixture.guest.activeReads, "Physical reader cancellation completes after explicit drain")
            root.modelB.act(LibraryKind.FAVOURITE, action)
            root.binding.submit(replacement, 9.0); root.binding.remove(replacement); root.binding.dismiss(replacement)
            assertTrue(root.fixture.guest.rows.value.isEmpty())
            assertNull(root.binding.rateAuthority(replacement.prompt))
            assertNull(root.latestPrompt)
            scheduler.runCurrent()
            assertTrue(root.fixture.guest.rows.value.isEmpty())
            assertNull(root.modelB.state.value.authority)
            assertNull(root.binding.rateAuthority(replacement.prompt))
            assertNull(root.latestPrompt)
        }
    }

    @Test fun promptClosurePublicationCannotLetObsoleteSelectionDeactivateNewOwner() = runComposeUiTest {
        lateinit var root: MobileDetailRootBindingUiState
        setContent { MobileDetailRootBindingUiFixture { root = it } }
        waitForIdle()
        waitUntil { root.entryA.lifecycle.currentState == Lifecycle.State.RESUMED &&
            root.entryB.lifecycle.currentState == Lifecycle.State.RESUMED }
        runOnIdle { root.activateA(); root.fixture.graph.actions.openRate(title, null) }
        waitUntil { root.latestPrompt != null }
        runOnIdle {
            var reentered = false
            val observer = root.scope.launch {
                root.fixture.graph.actions.ratePrompt.collect {
                    if (!reentered && it == null) {
                        reentered = true
                        root.activateB()
                        root.fixture.graph.actions.openRate(MovieDetailDto(765, "Replacement").toDetail("US").toTitle(), null)
                    }
                }
            }
            root.binding.currentChanged(null)
            assertTrue(reentered)
            observer.cancel()
        }
        waitUntil { root.modelB.state.value.authority != null && root.latestPrompt?.title?.ref?.id == 765 }
        runOnIdle {
            assertNotNull(root.binding.rateAuthority(assertNotNull(root.latestPrompt)))
            root.binding.leave()
            assertNull(root.modelB.state.value.authority)
            assertEquals(0, root.fixture.guest.activeReads)
        }
    }

    @Test fun delayedRootStatusCannotReauthorizeSamePromptAfterRealLocalFailureAndEqualRecovery() = runComposeUiTest {
        lateinit var root: MobileDetailRootBindingUiState
        setContent { MobileDetailRootBindingUiFixture { root = it } }
        waitForIdle()
        waitUntil { root.entryA.lifecycle.currentState == Lifecycle.State.RESUMED }
        runOnIdle {
            root.fixture.guest.rows.value = listOf(GuestLibraryRow(title.ref, LibraryKind.RATING, 5.0, 0, "{}"))
            root.activateA()
            root.fixture.graph.actions.openRate(title, null)
        }
        waitUntil { root.modelA.state.value.authority != null && root.latestPrompt != null }
        runOnIdle {
            val f = root.fixture
            val prompt = assertNotNull(root.latestPrompt)
            val old = assertNotNull(root.binding.rateAuthority(prompt))
            val submit = { root.binding.submit(old, 9.0) }
            val remove = { root.binding.remove(old) }
            val dismiss = { root.binding.dismiss(old) }
            val read = assertNotNull(root.modelA.state.value.authority)
            val context = f.graph.contextChanges.value
            val generation = f.generation.current
            val identity = f.graph.identity.value
            val releaseStatus = CompletableDeferred<Unit>()
            val statuses = mutableListOf<Boolean>()
            val status = root.scope.launch {
                root.modelA.state.map { it.invalid || it.settingsFailed || it.libraryFailed || it.identityFailed }
                    .distinctUntilChanged().collect {
                        statuses += it
                        releaseStatus.await()
                        root.binding.detailReadsChanged(root.entryA, root.modelA)
                    }
            }
            val rating = f.guest.rows.value
            f.guest.fail = true
            f.guest.rows.value = rating + GuestLibraryRow(MediaRef(MediaType.Movie, 765), LibraryKind.WATCHLIST, null, 0, "{}")
            assertTrue(root.modelA.state.value.libraryFailed)
            assertFalse(f.graph.storageFailed.value, "Failure must be local to the actual Detail origin reader")
            f.guest.fail = false
            root.modelA.retryReads()
            assertFalse(root.modelA.state.value.libraryFailed)
            assertNotEquals(read, assertNotNull(root.modelA.state.value.authority))
            assertEquals(context, f.graph.contextChanges.value)
            assertEquals(generation, f.generation.current)
            assertEquals(identity, f.graph.identity.value)
            assertEquals(listOf(false), statuses, "Subscribed root status collector must stay suspended across failure and recovery")
            val recoveredRows = f.guest.rows.value
            repeat(2) {
                root.binding.observePrompt(prompt)
                assertNull(root.binding.rateAuthority(prompt), "Same prompt must never capture recovered read authority")
                submit(); remove(); dismiss()
            }
            assertEquals(recoveredRows, f.guest.rows.value)
            assertSame(prompt, root.latestPrompt, "Rejected callbacks must not dismiss even the obsolete producer prompt")
            f.graph.actions.closeRate()
            f.graph.actions.openRate(title, null)
            val replacement = assertNotNull(root.latestPrompt)
            assertNotSame(prompt, replacement)
            val fresh = assertNotNull(root.binding.rateAuthority(replacement))
            submit(); remove(); dismiss()
            assertSame(replacement, root.latestPrompt, "Old callbacks cannot close or retarget a replacement")
            releaseStatus.complete(Unit)
            assertEquals(listOf(false), statuses, "Transient true is deliberately unobserved")
            root.binding.submit(fresh, 8.0)
            assertEquals(8.0, f.guest.rows.value.single { it.kind == LibraryKind.RATING }.value)
            assertNull(root.latestPrompt)
            status.cancel()
        }
    }

    @Test fun sameOwnerContextRecoveryCannotCloseFreshPromptAcceptedDuringRecovery() = runComposeUiTest {
        lateinit var root: MobileDetailRootBindingUiState
        setContent { MobileDetailRootBindingUiFixture { root = it } }
        waitForIdle()
        waitUntil { root.entryA.lifecycle.currentState == Lifecycle.State.RESUMED }
        runOnIdle {
            root.fixture.guest.rows.value = listOf(GuestLibraryRow(title.ref, LibraryKind.RATING, 5.0, 0, "{}"))
            root.activateA()
            root.fixture.graph.actions.openRate(title, null)
        }
        waitUntil { root.modelA.state.value.authority != null && root.latestPrompt != null }
        runOnIdle {
            val oldPrompt = assertNotNull(root.latestPrompt)
            val old = assertNotNull(root.binding.rateAuthority(oldPrompt))
            val oldRead = assertNotNull(root.modelA.state.value.authority)
            var revoked = false
            var recovered = false
            val observer = root.scope.launch {
                root.modelA.state.collect {
                    if (it.authority == null) revoked = true
                    else if (revoked && !recovered) {
                        recovered = true
                        assertNotEquals(oldRead, it.authority)
                        assertNull(root.binding.observePrompt(oldPrompt), "Same old prompt cannot recapture recovered authority")
                        root.fixture.graph.actions.openRate(title, null)
                    }
                }
            }
            try {
                root.binding.contextChanged()
                assertTrue(revoked, "Actual owner must revoke its read during context refresh")
                assertTrue(recovered, "Fresh prompt must open from the same owner's synchronous recovery observer")
                val replacement = assertNotNull(root.latestPrompt, "Obsolete context-close continuation must not close the fresh prompt")
                assertNotSame(oldPrompt, replacement)
                val fresh = assertNotNull(root.binding.rateAuthority(replacement))
                assertSame(root.entryA, fresh.entry)
                assertSame(root.modelA, fresh.detailOwner)
                assertEquals(root.modelA.state.value.authority, fresh.detailAuthority)
                assertNull(root.binding.rateAuthority(oldPrompt))
                val rows = root.fixture.guest.rows.value
                root.binding.submit(old, 9.0); root.binding.remove(old); root.binding.dismiss(old)
                assertEquals(rows, root.fixture.guest.rows.value, "Old submit/remove must not write after same-owner recovery")
                assertSame(replacement, root.latestPrompt, "Old dismiss must not close the fresh prompt")
                root.binding.submit(fresh, 8.0)
                assertEquals(8.0, root.fixture.guest.rows.value.single { it.kind == LibraryKind.RATING }.value)
                assertNull(root.latestPrompt)
            } finally {
                observer.cancel()
            }
        }
    }

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
        val actions = mutableListOf<RenderedDetailAction>()
        fun capture(): RenderedDetailAction = assertNotNull(actions.lastOrNull()).also {
            assertNotNull(it.authority)
            assertEquals(it.model.state.value.authority, it.authority)
            assertEquals(MediaRef(MediaType.Movie, 101), it.model.ref)
        }
        setContent {
            MobileDetailRootUiFixture(actionObserver = { model, authority, onAct ->
                actions += RenderedDetailAction(model, authority, onAct)
            }) { fixture, lifecycle -> f = fixture; owner = lifecycle }
        }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        onNodeWithContentDescription("Favourite title").assertIsEnabled()
        lateinit var beforeBackground: RenderedDetailAction
        runOnIdle {
            beforeBackground = capture()
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            beforeBackground.onAct(LibraryKind.FAVOURITE)
            assertTrue(f.guest.rows.value.isEmpty())
        }
        waitForIdle()
        runOnIdle { assertTrue(f.guest.rows.value.isEmpty()) }
        runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        onNodeWithContentDescription("Favourite title").assertIsEnabled()
        runOnIdle {
            val recovered = capture()
            assertSame(beforeBackground.model, recovered.model)
            assertNotEquals(beforeBackground.authority, recovered.authority, "Resume must publish genuinely different authority")
            beforeBackground.onAct(LibraryKind.FAVOURITE)
            assertTrue(f.guest.rows.value.isEmpty())
        }
        waitForIdle()
        runOnIdle {
            assertTrue(f.guest.rows.value.isEmpty(), "Post-resume stale action must not write before tab action")
        }
        lateinit var beforeTab: RenderedDetailAction
        runOnIdle { beforeTab = capture() }
        onNodeWithText("Explore", useUnmergedTree = true).onParent().performSemanticsAction(SemanticsActions.OnClick) {
            it()
            beforeTab.onAct(LibraryKind.FAVOURITE)
            assertTrue(f.guest.rows.value.isEmpty())
        }
        waitForIdle()
        runOnIdle { assertTrue(f.guest.rows.value.isEmpty()) }
        onNodeWithText("Home", useUnmergedTree = true).performClick()
        onNodeWithText("Root title 101 en-US").assertExists()
        onNodeWithContentDescription("Favourite title").assertIsEnabled()
        lateinit var beforeBack: RenderedDetailAction
        runOnIdle {
            beforeBack = capture()
            assertSame(beforeBackground.model, beforeBack.model, "Home must retain the original Detail owner")
        }
        onNodeWithContentDescription("Back").performSemanticsAction(SemanticsActions.OnClick) {
            it()
            beforeBack.onAct(LibraryKind.FAVOURITE)
            assertTrue(f.guest.rows.value.isEmpty())
        }
        waitForIdle()
        runOnIdle { assertTrue(f.guest.rows.value.isEmpty()) }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        onNodeWithContentDescription("Favourite title").assertIsEnabled()
        runOnIdle {
            assertNotSame(beforeBack.model, capture().model, "Reopening must create a different entry owner")
            beforeBack.onAct(LibraryKind.FAVOURITE)
            assertTrue(f.guest.rows.value.isEmpty())
        }
        waitForIdle()
        runOnIdle { assertTrue(f.guest.rows.value.isEmpty()) }
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
