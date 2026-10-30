package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MobileSeasonViewModelTest {
    private fun MobileSeasonComponentFixture.owner(scope: CoroutineScope, saved: SavedStateHandle = SavedStateHandle(), id: Int = 42, number: Int = 3, raw: Flow<Settings> = rawSettings) =
        MobileSeasonViewModel(graph, id, number, saved, raw, scope)

    @Test fun invalidRoutesAndContradictoryOrWrongTypedScalarsNeverReadOrRewrite() = runTest {
        val cases = listOf(0 to 3, -1 to 3, 42 to -1).map { Triple(it.first, it.second, emptyMap<String, Any>()) } +
            listOf(mapOf("selectedSeason" to 7), mapOf("selectedSeason" to "3"), mapOf("openEpisode" to -1),
                mapOf("openEpisode" to 1L), mapOf("mobileSeason.index" to "2"), mapOf("mobileSeason.offset" to -1))
                .map { Triple(42, 3, it) }
        cases.forEach { (id, number, raw) ->
            val f = MobileSeasonComponentFixture(backgroundScope); val saved = SavedStateHandle(raw)
            val model = f.owner(backgroundScope, saved, id, number)
            model.setActive(true); model.retryReads(); model.contextChanged(); runCurrent()
            assertTrue(model.state.value.invalid); assertEquals(0, f.subscriptions); assertTrue(f.api.calls.isEmpty())
            assertEquals(raw, saved.keys().associateWith { saved.get<Any?>(it) })
            model.close(); f.graph.close()
        }
    }

    @Test fun startsHiddenAndWaitsForActualRawSettingsNotGraphDefaults() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val raw = MutableSharedFlow<Settings>()
        val model = f.owner(backgroundScope, raw = raw)
        runCurrent(); assertTrue(f.api.calls.isEmpty())
        model.setActive(true); runCurrent(); assertTrue(f.api.calls.isEmpty()); assertNull(model.state.value.context)
        raw.emit(Settings(language = "hi-IN")); runCurrent()
        assertEquals("hi-IN", assertNotNull(model.state.value.context).settings.language)
        assertEquals(listOf("tv/42/hi-IN", "season/42/3/hi-IN"), f.api.calls)
        model.close(); f.graph.close()
    }

    @Test fun initialGraphObservationIsNotAnAutomaticRawFailureRetry() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); runCurrent(); f.failSettings()
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        assertTrue(model.state.value.settingsFailed); assertEquals(1, f.subscriptions)
        model.close(); f.graph.close()
    }

    @Test fun unsupportedActualSettingsFailWithoutFetchingOrInventingSettings() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); f.raw.value = Result.success(Settings(language = "xx"))
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        assertTrue(model.state.value.settingsFailed); assertNull(model.state.value.context); assertTrue(f.api.calls.isEmpty())
        model.close(); f.graph.close()
    }

    @Test fun specialsZeroAndSparseSeasonNumbersUseTheirActualRequests() = runTest {
        listOf(0, 7).forEach { number ->
            val f = MobileSeasonComponentFixture(backgroundScope); val saved = SavedStateHandle()
            val model = f.owner(backgroundScope, saved, number = number); model.setActive(true); runCurrent()
            assertEquals(number, model.state.value.current?.selected)
            assertEquals(number, (model.state.value.current?.episodes as SectionState.Content).value.seasonNumber)
            assertEquals(number, saved.get<Int>("selectedSeason"))
            assertEquals(listOf("tv/42/en-US", "season/42/$number/en-US"), f.api.calls)
            model.close(); f.graph.close()
        }
    }

    @Test fun missingRequestedSeasonNeverFetchesAlternativeOrRewritesSavedHistory() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope)
        val saved = SavedStateHandle(mapOf("selectedSeason" to 9, "openEpisode" to 5))
        val model = f.owner(backgroundScope, saved, number = 9); model.setActive(true); runCurrent()
        assertNull(model.state.value.current?.selected); assertEquals(SectionState.Empty, model.state.value.current?.episodes)
        assertEquals(SeasonNotice.SeasonNotAvailable, model.state.value.current?.notice)
        assertEquals(listOf("tv/42/en-US"), f.api.calls)
        model.retryEpisodes(assertNotNull(model.state.value.context)); runCurrent()
        assertEquals(listOf("tv/42/en-US"), f.api.calls); assertEquals(9, saved.get<Int>("selectedSeason")); assertEquals(5, saved.get<Int>("openEpisode"))
        model.close(); f.graph.close()
    }

    @Test fun emptySeasonOptionsDoNotCreateDefaultSelection() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); f.api.seasons = emptyList()
        val saved = SavedStateHandle(); val model = f.owner(backgroundScope, saved); model.setActive(true); runCurrent()
        assertEquals(SectionState.Empty, model.state.value.current?.episodes); assertFalse(saved.keys().contains("selectedSeason"))
        assertEquals(listOf("tv/42/en-US"), f.api.calls)
        model.close(); f.graph.close()
    }

    @Test fun equalRawRecoveryRecollectsAndCreatesNewAuthorityWithImmutableSettings() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val model = f.owner(backgroundScope)
        model.setActive(true); runCurrent(); val old = assertNotNull(model.state.value.context)
        val subscriptions = f.subscriptions
        f.failSettings(); runCurrent()
        assertTrue(model.state.value.settingsFailed); assertTrue(model.state.value.stale); assertNull(model.state.value.context)
        f.raw.value = Result.success(Settings()); model.retryReads(); runCurrent()
        assertEquals(subscriptions + 1, f.subscriptions); assertNotSame(old, model.state.value.context); assertFalse(model.state.value.stale)
        assertEquals(Settings(), old.settings)
        model.close(); f.graph.close()
    }

    @Test fun coldFailureThenEqualRecoveryDoesNotNeedAnotherClick() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); f.failSettings()
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        assertTrue(model.state.value.settingsFailed); assertTrue(f.api.calls.isEmpty())
        val subscriptions = f.subscriptions
        f.raw.value = Result.success(Settings()); model.retryReads(); runCurrent()
        assertTrue(model.state.value.bodyReady); assertEquals(subscriptions + 1, f.subscriptions)
        model.close(); f.graph.close()
    }

    @Test fun actualGraphStorageFailureRecoversEqualSettingsWithOneExplicitRetry() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val model = f.owner(backgroundScope, raw = f.graph.settingsFlow)
        model.setActive(true); runCurrent(); val first = assertNotNull(model.state.value.context)
        f.store.fail = true; f.store.values.value = mapOf("probe" to true); runCurrent()
        assertTrue(f.graph.settingsFailed.value); assertNull(model.state.value.context); assertTrue(model.state.value.settingsFailed)
        f.store.fail = false; model.retryReads(); runCurrent()
        assertFalse(f.graph.settingsFailed.value); assertTrue(model.state.value.bodyReady)
        val recovered = assertNotNull(model.state.value.context)
        assertNotSame(first, recovered); assertEquals(first.settings, recovered.settings); assertEquals(0, f.store.edits)
        model.close(); f.graph.close()
    }

    @Test fun graphContentAdultAndIdentityInvalidationRecollectActualReader() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val model = f.owner(backgroundScope, raw = f.graph.settingsFlow)
        model.setActive(true); runCurrent(); val first = assertNotNull(model.state.value.context)
        f.store.values.value = mapOf("include_adult" to true); runCurrent()
        assertNotSame(first, model.state.value.context); assertTrue(assertNotNull(model.state.value.context).settings.includeAdult)
        val beforeGeneration = assertNotNull(model.state.value.context)
        // Also exercise the explicit root seam for generation-only invalidation.
        f.generation.bump(); model.contextChanged(); runCurrent()
        assertNotSame(beforeGeneration, model.state.value.context)
        val beforeIdentity = assertNotNull(model.state.value.context)
        f.auth.emissions.emit(null); runCurrent()
        assertNotSame(beforeIdentity, model.state.value.context)
        model.close(); f.graph.close()
    }

    @Test fun stubbornOldShowCannotPublishOrWriteSelectionAfterRawFailure() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val gate = CompletableDeferred<Unit>()
        f.api.stubborn = true; f.api.showGate = gate
        val saved = SavedStateHandle(); val model = f.owner(backgroundScope, saved); model.setActive(true); runCurrent()
        f.failSettings(); runCurrent(); gate.complete(Unit); runCurrent()
        assertTrue(model.state.value.settingsFailed); assertNull(model.state.value.current)
        assertFalse(saved.keys().contains("selectedSeason")); assertFalse(f.api.calls.any { it.startsWith("season/") })
        model.close(); f.graph.close()
    }

    @Test fun stubbornOldFailureCannotReplaceNewLanguageSuccess() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val gate = CompletableDeferred<Unit>()
        f.api.showGate = gate; f.api.stubborn = true; f.api.showFailure = true
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        f.api.showGate = null; f.api.showFailure = false; f.raw.value = Result.success(Settings(language = "hi-IN")); runCurrent()
        val fresh = assertNotNull(model.state.value.context); assertTrue(model.state.value.bodyReady)
        gate.complete(Unit); runCurrent()
        assertSame(fresh, model.state.value.context); assertEquals("Actual show hi-IN", (model.state.value.current?.show as SectionState.Content).value.title)
        model.close(); f.graph.close()
    }

    @Test fun stubbornOldEpisodeCannotPublishAfterReplacementOrDisposal() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val gate = CompletableDeferred<Unit>()
        f.api.stubborn = true; f.api.episodeGate = gate
        val saved = SavedStateHandle(mapOf("openEpisode" to 1)); val model = f.owner(backgroundScope, saved)
        model.setActive(true); runCurrent(); val old = assertNotNull(model.state.value.context)
        f.api.episodeGate = null; f.raw.value = Result.success(Settings(language = "hi-IN")); runCurrent()
        val fresh = assertNotNull(model.state.value.context)
        model.openEpisode(old, 2); assertEquals(1, saved.get<Int>("openEpisode"))
        gate.complete(Unit); runCurrent(); assertSame(fresh, model.state.value.context)
        model.close(); model.openEpisode(fresh, 2); model.saveScroll(fresh, 4, 7)
        assertEquals(1, saved.get<Int>("openEpisode")); assertNull(saved.get<Int>("mobileSeason.index"))
        f.graph.close()
    }

    @Test fun actualShowAndSeasonIdentityAndEpisodeTopologyAreRequired() = runTest {
        listOf<(MobileSeasonComponentApi) -> Unit>({ it.wrongShow = true }, { it.wrongSeason = true },
            { it.wrongEpisodeSeason = true }, { it.duplicateEpisodes = true }).forEach { configure ->
            val f = MobileSeasonComponentFixture(backgroundScope); configure(f.api)
            val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
            assertFalse(model.state.value.bodyReady)
            val current = assertNotNull(model.state.value.current)
            assertTrue(current.show is SectionState.Error || current.episodes is SectionState.Error)
            assertNull(model.state.value.retainedSeason)
            model.close(); f.graph.close()
        }
    }

    @Test fun episodeExpansionRequiresLoadedMembershipAndDoesNotRewriteMissingSavedPanel() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val saved = SavedStateHandle(mapOf("openEpisode" to 99))
        val model = f.owner(backgroundScope, saved); model.setActive(true); runCurrent()
        val token = assertNotNull(model.state.value.context); assertNull(model.state.value.current?.openEpisode)
        model.openEpisode(token, 99); assertEquals(99, saved.get<Int>("openEpisode"))
        model.openEpisode(token, 2); runCurrent(); assertEquals(2, model.state.value.current?.openEpisode); assertEquals(2, saved.get<Int>("openEpisode"))
        model.closeEpisode(token); runCurrent(); assertNull(model.state.value.current?.openEpisode)
        model.close(); f.graph.close()
    }

    @Test fun seasonChipsNavigateNewOwnersAndShowUsesExactActualRef() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val saved = SavedStateHandle()
        val model = f.owner(backgroundScope, saved); model.setActive(true); runCurrent()
        val destinations = mutableListOf<MobileDestination>(); val nav = MobileNavigator(destinations::add)
        val token = assertNotNull(model.state.value.context)
        model.selectSeason(token, 0, nav); model.selectSeason(token, 7, nav); model.selectSeason(token, 99, nav); model.show(token, nav)
        assertEquals(listOf(MobileDestination.Season(42, 0), MobileDestination.Season(42, 7), MobileDestination.Detail(MediaRef(MediaType.Tv, 42))), destinations)
        assertEquals(3, saved.get<Int>("selectedSeason")); assertEquals(3, model.state.value.current?.selected)
        model.close(); f.graph.close()
    }

    @Test fun showAndEpisodeFailuresHaveIndependentRetries() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); f.api.episodeFailure = true
        val model = f.owner(backgroundScope); model.setActive(true); runCurrent()
        assertIs<SectionState.Content<Detail>>(model.state.value.current?.show)
        assertIs<SectionState.Error>(model.state.value.current?.episodes)
        f.api.episodeFailure = false; model.retryEpisodes(assertNotNull(model.state.value.context)); runCurrent()
        assertTrue(model.state.value.bodyReady); assertEquals(1, f.api.calls.count { it.startsWith("tv/") })
        model.close(); f.graph.close()
    }

    @Test fun scrollSavesOnlyAfterCompletedEpisodeTopologyAndRejectsStaleTokens() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val gate = CompletableDeferred<Unit>(); f.api.episodeGate = gate
        val saved = SavedStateHandle(mapOf("mobileSeason.index" to 5, "mobileSeason.offset" to 37))
        val model = f.owner(backgroundScope, saved); model.setActive(true); runCurrent()
        val token = assertNotNull(model.state.value.context); model.saveScroll(token, 0, 0)
        assertEquals(5, saved.get<Int>("mobileSeason.index")); gate.complete(Unit); runCurrent()
        model.saveScroll(token, 6, 12); assertEquals(6, saved.get<Int>("mobileSeason.index"))
        model.setActive(false); model.saveScroll(token, 0, 0); assertEquals(6, saved.get<Int>("mobileSeason.index"))
        model.close(); f.graph.close()
    }

    @Test fun scrollTargetReadsLatestSavedPositionAcrossSameOwnerReactivation() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val saved = SavedStateHandle()
        val model = f.owner(backgroundScope, saved)
        assertEquals(MobileSeasonScroll(), model.scrollTarget)
        model.setActive(true); runCurrent()
        model.saveScroll(assertNotNull(model.state.value.context), 8, 37)
        assertEquals(MobileSeasonScroll(8, 37), model.scrollTarget)
        model.setActive(false); model.setActive(true); runCurrent()
        assertEquals(MobileSeasonScroll(8, 37), model.scrollTarget)
        model.close(); f.graph.close()
    }

    @Test fun scrollTargetNeverSubstitutesDefaultsForInvalidSavedScalars() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope)
        val invalid = SavedStateHandle(mapOf("mobileSeason.index" to "8"))
        val rejected = f.owner(backgroundScope, invalid)
        assertNull(rejected.scrollTarget); rejected.setActive(true); runCurrent()
        assertTrue(f.api.calls.isEmpty()); assertEquals("8", invalid.get<Any?>("mobileSeason.index"))
        val saved = SavedStateHandle(); val model = f.owner(backgroundScope, saved)
        saved["mobileSeason.offset"] = -1
        assertNull(model.scrollTarget); model.setActive(true); runCurrent()
        assertTrue(model.state.value.invalid); assertTrue(f.api.calls.isEmpty())
        assertEquals(-1, saved.get<Int>("mobileSeason.offset"))
        rejected.close(); model.close(); f.graph.close()
    }

    @Test fun hidingRejectsEveryCallbackAndReentryCreatesNewReaderAndToken() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val model = f.owner(backgroundScope)
        model.setActive(true); runCurrent(); val token = assertNotNull(model.state.value.context)
        model.setActive(false); runCurrent(); val reads = f.subscriptions
        var callbacks = 0; val nav = MobileNavigator { callbacks++ }
        model.selectSeason(token, 0, nav); model.show(token, nav); model.openEpisode(token, 1); model.closeEpisode(token)
        model.retryShow(token); model.retryEpisodes(token); model.retryReads(); model.back { callbacks++ }; model.contextChanged(); runCurrent()
        assertEquals(0, callbacks); assertEquals(reads, f.subscriptions); assertEquals(0, f.activeSubscriptions)
        model.setActive(true); runCurrent(); assertEquals(reads + 1, f.subscriptions); assertNotSame(token, model.state.value.context)
        model.close(); f.graph.close()
    }

    @Test fun synchronousHideDuringRevocationCannotStartReaderOrCancelNewReentrantOwner() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val model = f.owner(backgroundScope)
        model.setActive(true); runCurrent(); val subscriptions = f.subscriptions
        var armed = true
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.collect { if (armed && it.context == null) { armed = false; model.setActive(false) } }
        }
        model.retryReads(); runCurrent()
        assertFalse(armed); assertEquals(subscriptions, f.subscriptions); assertNull(model.state.value.context)
        observer.cancel(); model.close(); f.graph.close()
    }

    @Test fun oldRecoveryAndBackCallbacksCannotRetargetReactivatedEntry() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val model = f.owner(backgroundScope)
        model.setActive(true); runCurrent(); f.failSettings(); runCurrent()
        val obsolete = model.state.value.revision
        model.setActive(false); f.raw.value = Result.success(Settings()); model.setActive(true); runCurrent()
        val fresh = assertNotNull(model.state.value.context); val subscriptions = f.subscriptions
        var backs = 0
        model.retryReads(obsolete); model.back(obsolete) { backs++ }; runCurrent()
        assertSame(fresh, model.state.value.context); assertEquals(subscriptions, f.subscriptions); assertEquals(0, backs)
        model.close(); f.graph.close()
    }

    @Test fun reentrantReplacementDuringRevocationDoesNotGetCancelledByOuterRestart() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val model = f.owner(backgroundScope)
        model.setActive(true); runCurrent(); val subscriptions = f.subscriptions
        var armed = true
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.collect {
                if (armed && it.context == null) { armed = false; model.contextChanged() }
            }
        }
        model.retryReads(); runCurrent()
        assertFalse(armed); assertEquals(subscriptions + 1, f.subscriptions)
        assertTrue(model.state.value.bodyReady); assertEquals(1, f.activeSubscriptions)
        observer.cancel(); model.close(); f.graph.close()
    }

    @Test fun cancelledRawReaderLateFailureCannotRevokeReplacement() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val release = CompletableDeferred<Unit>()
        var reads = 0
        val raw = flow {
            val read = ++reads
            if (read == 1) {
                withContext(NonCancellable) { release.await() }
                throw StorageException("obsolete raw reader")
            }
            emit(Settings(language = "hi-IN")); awaitCancellation()
        }
        val model = f.owner(backgroundScope, raw = raw); model.setActive(true); runCurrent()
        model.retryReads(); runCurrent(); val fresh = assertNotNull(model.state.value.context)
        release.complete(Unit); runCurrent()
        assertSame(fresh, model.state.value.context); assertFalse(model.state.value.settingsFailed)
        assertTrue(model.state.value.bodyReady)
        model.close(); f.graph.close()
    }

    @Test fun sameReaderEqualEmissionsStillCreateDistinctImmutableHolderTokens() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val raw = MutableSharedFlow<Settings>()
        val model = f.owner(backgroundScope, raw = raw); model.setActive(true); runCurrent()
        raw.emit(Settings()); runCurrent(); val first = assertNotNull(model.state.value.context)
        raw.emit(Settings()); runCurrent(); val second = assertNotNull(model.state.value.context)
        assertNotSame(first, second); assertEquals(first.settings, second.settings)
        model.close(); f.graph.close()
    }

    @Test fun normalRawReaderCompletionRevokesAuthorityInsteadOfLeavingActionableCachedData() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope)
        val model = f.owner(backgroundScope, raw = flow { emit(Settings()) })
        model.setActive(true); runCurrent(); assertNull(model.state.value.context)
        model.close(); f.graph.close()
    }

    @Test fun parentCancellationRevokesBeforeStubbornWorkReturns() = runTest {
        val f = MobileSeasonComponentFixture(backgroundScope); val gate = CompletableDeferred<Unit>(); f.api.showGate = gate; f.api.stubborn = true
        val parent = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val saved = SavedStateHandle(); val model = f.owner(parent, saved); model.setActive(true); runCurrent()
        parent.cancel(); gate.complete(Unit); runCurrent()
        assertNull(model.state.value.context); assertFalse(saved.keys().contains("selectedSeason"))
        f.graph.close()
    }
}
