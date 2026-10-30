package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.LibraryStateSnapshot
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.MovieDetailDto
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MobileDetailViewModelTest {
    private fun MobileDetailComponentFixture.owner(scope: CoroutineScope, saved: SavedStateHandle = SavedStateHandle(), media: String = "movie", id: Int = 42) =
        MobileDetailViewModel(graph, media, id, saved, rawSettings, accountState, scope)

    @Test fun invalidMediaAndNonpositiveIdsNeverSubscribeFetchOrRewrite() = runTest {
        listOf("person" to 42, "Movie" to 42, "" to 42, "movie" to 0, "tv" to -1).forEach { (media, id) ->
            val f = MobileDetailComponentFixture(backgroundScope)
            val saved = SavedStateHandle(mapOf("mobileDetail.segment" to "Cast"))
            val model = f.owner(backgroundScope, saved, media, id)
            model.setActive(true); model.retryReads(); model.contextChanged(); runCurrent()
            assertTrue(model.state.value.invalid)
            assertTrue(f.api.calls.isEmpty())
            assertEquals(0, f.settingsSubscriptions)
            assertEquals(0, f.accountSubscriptions)
            assertEquals("Cast", saved.get<Any?>("mobileDetail.segment"))
            model.close(); f.graph.close()
        }
    }

    @Test fun wrongTypedSavedScalarsAndImpossibleSegmentsRemainUntouched() = runTest {
        listOf<Map<String, Any>>(mapOf("mobileDetail.segment" to 1), mapOf("mobileDetail.segment" to "Seasons"),
            mapOf("mobileDetail.expanded" to "true"), mapOf("mobileDetail.Overview.index" to "2"),
            mapOf("mobileDetail.Cast.offset" to -1), mapOf("mobileDetail.Reviews.index" to 1L)).forEach { raw ->
            val f = MobileDetailComponentFixture(backgroundScope)
            val saved = SavedStateHandle(raw)
            val model = f.owner(backgroundScope, saved)
            model.setActive(true); model.selectSegment(MobileDetailSegment.Overview); model.setExpanded(true)
            model.saveScroll(MobileDetailSegment.Overview, 2, 10); model.retryReads(); runCurrent()
            assertTrue(model.state.value.invalid)
            assertEquals(raw, saved.keys().associateWith { saved.get<Any?>(it) })
            assertTrue(f.api.calls.isEmpty())
            model.close(); f.graph.close()
        }
    }

    @Test fun initiallyHiddenOwnerDoesNotFetchAndColdSettingsAreNotInvented() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope)
        val raw = MutableSharedFlow<Settings>()
        val model = MobileDetailViewModel(f.graph, "movie", 42, SavedStateHandle(), raw, f.accountState, backgroundScope)
        runCurrent(); assertTrue(f.api.calls.isEmpty())
        model.setActive(true); runCurrent()
        assertTrue(f.api.calls.isEmpty()); assertNull(model.state.value.authority)
        raw.emit(Settings()); runCurrent()
        assertIs<SectionState.Content<Detail>>(model.state.value.section)
        assertNull(model.state.value.authority) // Unknown is not guest.
        model.close(); f.graph.close()
    }

    @Test fun equalRawRecoveryResubscribesAndReprovesCompletionWithoutWriting() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest()
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        val first = assertNotNull(model.state.value.authority)
        f.failSettings(); runCurrent()
        assertTrue(model.state.value.settingsFailed); assertTrue(model.state.value.stale)
        assertNotNull(model.state.value.retained); assertNull(model.state.value.authority)
        val subscriptions = f.settingsSubscriptions
        f.raw.value = Result.success(Settings()); model.retryReads(); runCurrent()
        assertTrue(f.settingsSubscriptions > subscriptions)
        assertNotEquals(first.context.requestToken, assertNotNull(model.state.value.authority).context.requestToken)
        assertFalse(model.state.value.stale); assertTrue(f.guest.rows.value.isEmpty())
        model.close(); f.graph.close()
    }

    @Test fun coldFailureCanRecoverInTheSameOwner() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.failSettings()
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        assertTrue(model.state.value.settingsFailed); assertTrue(f.api.calls.isEmpty())
        f.raw.value = Result.success(Settings()); model.retryReads(); runCurrent()
        assertIs<SectionState.Content<Detail>>(model.state.value.section)
        model.close(); f.graph.close()
    }

    @Test fun cancelledOldSettingsRequestCannotPublishOverNewContext() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest()
        val gate = CompletableDeferred<Unit>(); f.api.gate = gate; f.api.ignoreCancellation = true
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        f.raw.value = Result.success(Settings(language = "hi-IN")); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("hi-IN", assertNotNull(model.state.value.authority).context.language)
        assertEquals("Detail movie hi-IN", (model.state.value.section as SectionState.Content).value.title)
        model.close(); f.graph.close()
    }

    @Test fun realRepositoryOriginFlowUpdatesGuestDisplayAfterCapturedWrite() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest()
        val model = MobileDetailViewModel(f.graph, "movie", 42, SavedStateHandle(), f.rawSettings,
            f.library.stateWithOrigin(MediaRef(MediaType.Movie, 42)), backgroundScope)
        model.setActive(true); runCurrent()
        assertTrue(model.state.value.account.known); assertFalse(model.state.value.account.watchlist)
        model.act(LibraryKind.WATCHLIST, assertNotNull(model.state.value.authority)); runCurrent()
        assertTrue(model.state.value.account.watchlist)
        assertEquals(MediaRef(MediaType.Movie, 42), f.guest.rows.value.single().ref)
        model.close(); f.graph.close()
    }

    @Test fun sameEmissionSourceProofIsRequiredEvenForIdenticalDtoAndGuest() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest()
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        assertTrue(model.state.value.account.known)
        val payload = f.snapshots.value.state.copy(watchlist = true)
        f.snapshots.value = LibraryStateSnapshot(payload); runCurrent()
        assertEquals(LibraryState.Unknown, model.state.value.account)
        f.snapshots.value = LibraryStateSnapshot(payload, true, null); runCurrent()
        assertTrue(model.state.value.account.watchlist)
        val a = AuthSession(AccountObjectId("synthetic-detail"), "Synthetic", 9007199254740993L)
        f.auth.emissions.emit(a); runCurrent()
        assertEquals(LibraryState.Unknown, model.state.value.account)
        f.snapshots.value = LibraryStateSnapshot(payload, true, a); runCurrent()
        assertTrue(model.state.value.account.watchlist)
        f.auth.emissions.emit(a.copy(sessionGeneration = 9007199254740994L)); runCurrent()
        assertEquals(LibraryState.Unknown, model.state.value.account)
        model.close(); f.graph.close()
    }

    @Test fun accountCollectorFailureMasksDataUntilExplicitResubscription() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest()
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        f.accountFailure = true
        f.snapshots.value = f.snapshots.value.copy(state = f.snapshots.value.state.copy(watchlist = true)); runCurrent()
        assertTrue(model.state.value.libraryFailed); assertEquals(LibraryState.Unknown, model.state.value.account)
        val count = f.accountSubscriptions
        f.accountFailure = false; runCurrent(); assertEquals(count, f.accountSubscriptions)
        model.retryReads(); runCurrent()
        assertTrue(f.accountSubscriptions > count); assertTrue(model.state.value.account.watchlist)
        model.close(); f.graph.close()
    }

    @Test fun hidingSynchronouslyWhenLibraryFailureClearsCannotSubscribeWhileHidden() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest(); f.accountFailure = true
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        assertTrue(model.state.value.libraryFailed)
        val subscriptions = f.accountSubscriptions
        var armed = true
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.collect {
                if (armed && !it.libraryFailed) { armed = false; model.setActive(false) }
            }
        }
        f.accountFailure = false; model.retryReads(); runCurrent()
        assertFalse(armed)
        assertEquals(subscriptions, f.accountSubscriptions)
        assertEquals(0, f.activeAccountSubscriptions)
        assertNull(model.state.value.authority)
        assertEquals(LibraryState.Unknown, model.state.value.account)
        observer.cancel(); model.close(); f.graph.close()
    }

    @Test fun disposalDuringAccountRestartCannotInstallAReader() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest(); f.accountFailure = true
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        assertTrue(model.state.value.libraryFailed)
        val subscriptions = f.accountSubscriptions
        var armed = true
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.collect {
                if (armed && !it.libraryFailed) { armed = false; model.close() }
            }
        }
        f.accountFailure = false; model.retryReads(); runCurrent()
        assertFalse(armed)
        assertEquals(subscriptions, f.accountSubscriptions)
        assertEquals(0, f.activeAccountSubscriptions)
        assertNull(model.state.value.authority)
        observer.cancel(); model.close(); f.graph.close()
    }

    @Test fun retryDuringAccountRestartLeavesOnlyTheNewestReader() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest(); f.accountFailure = true
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        assertTrue(model.state.value.libraryFailed)
        val subscriptions = f.accountSubscriptions
        var armed = true
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.collect {
                if (armed && !it.libraryFailed) { armed = false; model.retryReads() }
            }
        }
        f.accountFailure = false; model.retryReads(); runCurrent()
        assertFalse(armed)
        assertEquals(subscriptions + 1, f.accountSubscriptions)
        assertEquals(1, f.activeAccountSubscriptions)
        f.snapshots.value = f.snapshots.value.copy(state = f.snapshots.value.state.copy(favourite = true)); runCurrent()
        assertTrue(model.state.value.account.favourite)
        observer.cancel(); model.close(); runCurrent()
        assertEquals(0, f.activeAccountSubscriptions)
        f.graph.close()
    }

    @Test fun reactivationDuringRevocationKeepsTheNewestAccountReaderAlive() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest()
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        val rendered = assertNotNull(model.state.value.authority)
        var armed = true
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.collect {
                if (armed && it.authority == null) { armed = false; model.setActive(true) }
            }
        }
        model.setActive(false); runCurrent()
        assertFalse(armed)
        assertEquals(1, f.activeAccountSubscriptions)
        f.snapshots.value = f.snapshots.value.copy(state = f.snapshots.value.state.copy(favourite = true)); runCurrent()
        assertTrue(model.state.value.account.favourite)
        assertNotEquals(rendered.lifetime, assertNotNull(model.state.value.authority).lifetime)
        model.act(LibraryKind.WATCHLIST, rendered); runCurrent()
        assertTrue(f.guest.rows.value.isEmpty())
        observer.cancel(); model.close(); runCurrent()
        assertEquals(0, f.activeAccountSubscriptions)
        f.graph.close()
    }

    @Test fun capturedContextAndGenerationAreCheckedSynchronouslyAtDispatch() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest()
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        val old = assertNotNull(model.state.value.authority)
        f.catalog.onContentSettingsChanged()
        model.act(LibraryKind.WATCHLIST, old); runCurrent()
        assertTrue(f.guest.rows.value.isEmpty())
        model.contextChanged(); runCurrent()
        model.act(LibraryKind.WATCHLIST, old); runCurrent(); assertTrue(f.guest.rows.value.isEmpty())
        model.act(LibraryKind.WATCHLIST, assertNotNull(model.state.value.authority)); runCurrent()
        assertEquals(MediaRef(MediaType.Movie, 42), f.guest.rows.value.single().ref)
        model.close(); f.graph.close()
    }

    @Test fun renderedSessionIsNeverRetargetedAfterSignIn() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest()
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        val guest = assertNotNull(model.state.value.authority)
        val a = AuthSession(AccountObjectId("synthetic-detail"), "Synthetic", 6)
        f.auth.emissions.emit(a); runCurrent()
        f.snapshots.value = f.snapshots.value.copy(sourceIdentity = a); runCurrent()
        model.act(LibraryKind.FAVOURITE, guest); model.act(LibraryKind.RATING, guest); runCurrent()
        assertTrue(f.accountWrites.isEmpty()); assertTrue(f.guest.rows.value.isEmpty())
        val prompts = mutableListOf<RatePrompt?>()
        val collecting = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.graph.actions.ratePrompt.toList(prompts) }
        model.act(LibraryKind.FAVOURITE, assertNotNull(model.state.value.authority)); runCurrent()
        assertEquals(listOf(a), f.accountWrites)
        model.act(LibraryKind.RATING, assertNotNull(model.state.value.authority)); runCurrent()
        assertEquals(a, assertNotNull(prompts.last()).identity)
        collecting.cancel(); model.close(); f.graph.close()
    }

    @Test fun hiddenDisposedAndParentCancelledOwnersRejectAllCallbacks() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest()
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val model = f.owner(scope); model.setActive(true); runCurrent()
        val rendered = assertNotNull(model.state.value.authority)
        var browsed = 0
        model.setActive(false); model.act(LibraryKind.WATCHLIST, rendered); model.browse { browsed++ }; runCurrent()
        assertTrue(f.guest.rows.value.isEmpty()); assertEquals(0, browsed)
        val subscriptions = f.settingsSubscriptions
        model.setActive(true); runCurrent(); assertTrue(f.settingsSubscriptions > subscriptions)
        scope.cancel(); model.act(LibraryKind.WATCHLIST, model.state.value.authority); model.browse { browsed++ }; runCurrent()
        assertEquals(0, browsed); assertTrue(f.guest.rows.value.isEmpty())
        model.close(); model.setActive(true); model.retryReads(); runCurrent()
        assertNull(model.state.value.authority); f.graph.close()
    }

    @Test fun pendingActionsAreDisabledAndReadRetryNeverReplaysWrites() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest()
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        f.snapshots.value = f.snapshots.value.copy(state = f.snapshots.value.state.copy(pending = setOf(LibraryKind.WATCHLIST))); runCurrent()
        model.act(LibraryKind.WATCHLIST, model.state.value.authority); runCurrent()
        assertTrue(f.guest.rows.value.isEmpty())
        f.snapshots.value = f.snapshots.value.copy(state = f.snapshots.value.state.copy(pending = emptySet())); runCurrent()
        model.act(LibraryKind.WATCHLIST, model.state.value.authority); runCurrent()
        assertEquals(1, f.guest.rows.value.size)
        model.retryReads(); model.contextChanged(); runCurrent()
        assertEquals(1, f.guest.rows.value.size)
        model.close(); f.graph.close()
    }

    @Test fun segmentExpansionAndIndependentScrollRestorePerEntry() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope)
        val saved = SavedStateHandle()
        val model = f.owner(backgroundScope, saved, "tv")
        model.selectSegment(MobileDetailSegment.Cast); model.setExpanded(true)
        model.saveScroll(MobileDetailSegment.Cast, 3, 17)
        model.saveScroll(MobileDetailSegment.Seasons, 1, 8)
        model.close()
        val restored = f.owner(backgroundScope, saved, "tv")
        assertEquals(MobileDetailSegment.Cast, restored.state.value.segment)
        assertTrue(restored.state.value.expanded)
        assertEquals(MobileDetailScroll(3, 17), restored.scroll(MobileDetailSegment.Cast))
        assertEquals(MobileDetailScroll(1, 8), restored.scroll(MobileDetailSegment.Seasons))
        assertEquals(MobileDetailSegment.Overview, f.owner(backgroundScope).state.value.segment)
        restored.close(); f.graph.close()
    }

    @Test fun wrongReturnedTitleNeverBecomesActionable() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest(); f.api.wrongId = true
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        assertIs<SectionState.Error>(model.state.value.section); assertNull(model.state.value.authority)
        model.act(LibraryKind.WATCHLIST, null); runCurrent(); assertTrue(f.guest.rows.value.isEmpty())
        model.close(); f.graph.close()
    }

    @Test fun identityFailureIsUnknownNotGuestAndRetryResubscribesWithoutWriting() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest()
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        val previous = assertNotNull(model.state.value.authority)
        f.auth.fail = true; f.auth.emissions.emit(null); runCurrent()
        assertTrue(model.state.value.identityFailed)
        assertNull(f.graph.identity.value)
        assertEquals(LibraryState.Unknown, model.state.value.account)
        model.act(LibraryKind.WATCHLIST, previous); runCurrent(); assertTrue(f.guest.rows.value.isEmpty())
        f.auth.fail = false; model.retryReads(); runCurrent()
        assertEquals(MobileIdentity(null), f.graph.identity.value)
        assertTrue(model.state.value.account.known); assertTrue(f.guest.rows.value.isEmpty())
        model.close(); f.graph.close()
    }

    @Test fun queuedGuestIntentCannotWriteToANewSessionBeforeDispatchCoroutineRuns() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest()
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        model.act(LibraryKind.WATCHLIST, assertNotNull(model.state.value.authority))
        assertTrue(f.auth.emissions.tryEmit(AuthSession(AccountObjectId("synthetic-race"), "Synthetic", 11)))
        runCurrent()
        assertTrue(f.guest.rows.value.isEmpty()); assertTrue(f.accountWrites.isEmpty())
        model.close(); f.graph.close()
    }

    @Test fun disposedEntryDropsCancellationResistantCatalogCompletion() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest()
        val gate = CompletableDeferred<Unit>(); f.api.gate = gate; f.api.ignoreCancellation = true
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        model.close(); gate.complete(Unit); runCurrent()
        assertNull(model.state.value.authority); assertNull(model.state.value.retained)
        assertFalse(model.state.value.section is SectionState.Content)
        assertTrue(f.guest.rows.value.isEmpty()); f.graph.close()
    }

    @Test fun manualContentWithoutProducerCompletionNeverProvesAuthority() {
        val ref = MediaRef(MediaType.Movie, 42)
        val detail = MovieDetailDto(42, "Manual").toDetail("US")
        val content = SectionState.Content(detail)
        val context = DetailReadContext(ref, "en-US", "US", 1, 1)
        val manual = DetailUiState(detail = content, settingsRead = DetailSettingsRead.Ready, request = context)
        assertNull(manual.completedMobileDetail(ref, 1))
        val completed = manual.copy(completion = DetailReadCompletion(context, content))
        assertNull(completed.completedMobileDetail(ref, 1))
        val real = completed.copy(retained = DetailReadContent(context, detail))
        assertNotNull(real.completedMobileDetail(ref, 1))
        assertNull(real.completedMobileDetail(ref, 2))
    }

    @Test fun graphFailureFlagsMaskPrivateDisplayAndRejectBeforeCollectorRuns() = runTest {
        val f = MobileDetailComponentFixture(backgroundScope); f.guest()
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        val authority = assertNotNull(model.state.value.authority)
        f.guest.fail = true
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.graph.actions.state(MediaRef(MediaType.Movie, 42)).collect() }
        runCurrent(); assertTrue(f.graph.storageFailed.value)
        model.act(LibraryKind.WATCHLIST, authority); runCurrent()
        assertEquals(LibraryState.Unknown, model.state.value.account); assertTrue(f.guest.rows.value.isEmpty())
        collector.cancel(); model.close(); f.graph.close()
    }
}
