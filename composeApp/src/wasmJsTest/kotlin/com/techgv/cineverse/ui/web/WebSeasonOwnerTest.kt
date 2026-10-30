package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.presentation.SectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WebSeasonOwnerTest {
    @Test fun malformedScalarsRejectBeforeSubscriptionScopeOrCatalogWithoutRepair() = runTest {
        val cases = listOf(
            mapOf("selectedSeason" to "0"), mapOf("selectedSeason" to "07"), mapOf("selectedSeason" to "true"),
            mapOf("openEpisode" to "-1"), mapOf("openEpisode" to "1.0"), mapOf("openEpisode" to "2147483648"),
            mapOf("season.index" to "+1"), mapOf("season.offset" to " 0"),
            mapOf("season.seriesId" to "7"), mapOf("season.seriesId" to "8", "season.number" to "7"))
        cases.forEach { values ->
            val fake = WebSeasonFake()
            val entry = fake.entry(values = values)
            val before = entry.encoded()
            val owner = fake.owner(backgroundScope, entry)
            runCurrent()
            assertTrue(owner.state.value.invalidSavedState, values.toString())
            assertEquals(0, fake.subscriptions)
            assertTrue(fake.calls.isEmpty())
            assertEquals(before, entry.encoded())
            owner.close()
        }
    }

    @Test fun invalidRouteDoesNotReadOrConstructHolder() = runTest {
        listOf(WebRoute.Season(0, 7), WebRoute.Season(7, -1), WebRoute.Home).forEach { route ->
            val fake = WebSeasonFake()
            val owner = fake.owner(backgroundScope, WebEntry(route, mutableMapOf()))
            runCurrent()
            assertTrue(owner.state.value.invalidSavedState)
            assertEquals(0, fake.subscriptions)
            owner.close()
        }
    }

    @Test fun firstRawFailureHasNoDefaultFetchAndEqualColdRecoveryWaitsForReplacement() = runTest {
        val fake = WebSeasonFake().apply { readFailure = true }
        val owner = fake.owner(backgroundScope)
        runCurrent()
        assertIs<WebSeasonReader.Failed>(owner.state.value.reader)
        assertTrue(fake.calls.isEmpty())
        assertEquals(1, fake.reports.size)
        fake.readFailure = false
        val blocked = CompletableDeferred<Unit>().also { fake.showGate = it }
        owner.retryStorage()
        runCurrent()
        assertEquals(2, fake.subscriptions)
        assertEquals(1, fake.upstreamRetries)
        assertIs<WebSeasonReader.Ready>(owner.state.value.reader)
        assertFalse(owner.state.value.bodyReady)
        blocked.complete(Unit)
        runCurrent()
        assertTrue(owner.state.value.bodyReady)
        owner.close()
    }

    @Test fun equalRecoveryNeverMakesOldContentCurrentEvenForSynchronousObservers() = runTest {
        val fake = WebSeasonFake()
        val owner = fake.owner(backgroundScope)
        runCurrent()
        val original = owner.state.value.context
        fake.readFailure = true
        fake.preferences.value = Settings(sounds = true)
        runCurrent()
        assertIs<WebSeasonReader.Failed>(owner.state.value.reader)
        assertSame(original, owner.state.value.retainedSeason?.context)
        fake.readFailure = false
        fake.preferences.value = Settings()
        val observed = mutableListOf<WebSeasonState>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.state.collect { observed += it } }
        fake.catalog.onContentSettingsChanged()
        fake.showGate = CompletableDeferred()
        owner.retryStorage()
        runCurrent()
        assertTrue(owner.state.value.context !== original)
        assertEquals(original, owner.state.value.retainedSeason?.context)
        assertTrue(observed.filter { it.reader is WebSeasonReader.Ready && it.context !== original }.none { it.bodyReady })
        fake.showGate!!.complete(Unit)
        runCurrent()
        assertTrue(owner.state.value.bodyReady)
        observer.cancel()
        owner.close()
    }

    @Test fun alreadyBlockedAndInactiveOwnersDoNotSubscribeOrFetch() = runTest {
        val blocked = WebSeasonFake().apply { storage.value = BrowserStorageFailure.Blocked }
        val owner = blocked.owner(backgroundScope)
        runCurrent()
        assertEquals(0, blocked.subscriptions)
        assertTrue(blocked.calls.isEmpty())
        owner.close()
        val parent = CoroutineScope(coroutineContext + Job().also { it.cancel() })
        val inactive = WebSeasonFake()
        inactive.owner(parent).close()
        assertEquals(0, inactive.subscriptions)
        assertTrue(inactive.calls.isEmpty())
    }

    @Test fun queuedRawSubscriptionRechecksActualBlockedStatusBeforeReading() = runTest {
        val fake = WebSeasonFake()
        val owner = fake.owner(backgroundScope)
        fake.storage.value = BrowserStorageFailure.Blocked
        runCurrent()
        assertEquals(0, fake.subscriptions)
        assertTrue(fake.calls.isEmpty())
        owner.close()
    }

    @Test fun holderForwardingRechecksActualStorageAfterConstructionBeforeIo() = runTest {
        val fake = WebSeasonFake()
        val owner = fake.owner(backgroundScope)
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            owner.state.collect {
                if (it.current?.show == SectionState.Loading) fake.storage.value = BrowserStorageFailure.Blocked
            }
        }
        runCurrent()
        assertTrue(fake.calls.isEmpty())
        assertIs<WebSeasonReader.Failed>(owner.state.value.reader)
        observer.cancel()
        owner.close()
    }

    @Test fun immutableSaverInputAndLateShowWriteCannotChangeLiveHistory() = runTest {
        val fake = WebSeasonFake().apply { stubborn = true; showGate = CompletableDeferred() }
        val entry = fake.entry(values = mapOf("selectedSeason" to "7"))
        val owner = fake.owner(backgroundScope, entry)
        runCurrent()
        val old = owner.state.value.context
        entry.putInt("selectedSeason", 0)
        fake.storage.value = BrowserStorageFailure.Blocked
        runCurrent()
        val beforeLate = entry.encoded()
        fake.showGate!!.complete(Unit)
        runCurrent()
        assertEquals(beforeLate, entry.encoded())
        assertNull(owner.state.value.current)
        assertTrue(fake.calls.none { it.startsWith("season:7:0:") })
        assertNull(owner.state.value.context)
        assertNotNull(old)
        owner.close()
    }

    @Test fun currentHolderReadsItsValidatedSnapshotRatherThanLaterLiveSelection() = runTest {
        val fake = WebSeasonFake().apply { showGate = CompletableDeferred() }
        val entry = fake.entry(values = mapOf("selectedSeason" to "7"))
        val owner = fake.owner(backgroundScope, entry)
        runCurrent()
        entry.putInt("selectedSeason", 0)
        fake.showGate!!.complete(Unit)
        runCurrent()
        assertEquals(7, owner.state.value.current!!.selected)
        assertEquals("7", entry.getString("selectedSeason"))
        assertEquals(listOf("show:7:en-US", "season:7:7:en-US"), fake.calls)
        owner.close()
    }

    @Test fun parentOnlyCancellationRejectsLateSaverWritesAndCatalogResults() = runTest {
        val fake = WebSeasonFake().apply { stubborn = true; showGate = CompletableDeferred() }
        val parent = Job(coroutineContext[Job])
        val entry = fake.entry()
        val owner = fake.owner(CoroutineScope(coroutineContext + parent), entry)
        runCurrent()
        parent.cancel()
        entry.putInt("selectedSeason", 0)
        val saved = entry.encoded()
        fake.showGate!!.complete(Unit)
        runCurrent()
        assertEquals(saved, entry.encoded())
        assertNull(owner.state.value.current)
        owner.back()
        assertTrue(fake.destinations.isEmpty())
        owner.close()
    }

    @Test fun unsupportedReaderTupleIsTypedCorruptWithoutCatalogIo() = runTest {
        val fake = WebSeasonFake().apply { preferences.value = Settings(language = "fr-FR") }
        val owner = fake.owner(backgroundScope)
        runCurrent()
        assertEquals(WebSeasonReader.Failed(BrowserStorageFailure.Corrupt), owner.state.value.reader)
        assertTrue(fake.calls.isEmpty())
        assertIs<BrowserStorageException>(fake.reports.single())
        owner.close()
    }

    @Test fun cancelResistantOldEpisodesNeverReplaceNewContextOrSaver() = runTest {
        val fake = WebSeasonFake().apply { stubborn = true; episodeGate = CompletableDeferred() }
        val entry = fake.entry()
        val owner = fake.owner(backgroundScope, entry)
        runCurrent()
        val oldGate = fake.episodeGate!!
        val original = owner.state.value.context
        fake.episodeGate = null
        fake.preferences.value = Settings(language = "ta-IN")
        runCurrent()
        val current = owner.state.value.context
        assertTrue(current !== original)
        assertTrue(owner.state.value.bodyReady)
        val saved = entry.encoded()
        oldGate.complete(Unit)
        runCurrent()
        assertSame(current, owner.state.value.context)
        assertEquals(saved, entry.encoded())
        owner.close()
    }

    @Test fun exactCapturedSettingsAndOriginalRetainedLabelsSurviveContextChange() = runTest {
        val fake = WebSeasonFake()
        val owner = fake.owner(backgroundScope)
        runCurrent()
        val original = owner.state.value.context!!
        fake.preferences.value = Settings(language = "hi-IN", region = "US", sounds = true)
        fake.showGate = CompletableDeferred()
        runCurrent()
        assertEquals(Settings(language = "hi-IN", region = "US", sounds = true), owner.state.value.context!!.settings)
        assertEquals(Settings(), original.settings)
        assertSame(original, owner.state.value.retainedSeason!!.context)
        fake.showGate!!.complete(Unit)
        runCurrent()
        owner.close()
    }

    @Test fun missingSeasonAndNoSeasonsNeverFetchDefaultOrRewriteRequestedSelection() = runTest {
        listOf(false, true).forEach { noSeasons ->
            val fake = WebSeasonFake()
            if (noSeasons) fake.show = fake.show.copy(seasons = emptyList())
            val entry = fake.entry(9, mapOf("selectedSeason" to "9"))
            val owner = fake.owner(backgroundScope, entry)
            runCurrent()
            assertNull(owner.state.value.current!!.selected)
            assertEquals(SectionState.Empty, owner.state.value.current!!.episodes)
            assertTrue(owner.state.value.bodyReady)
            assertEquals("9", entry.getString("selectedSeason"))
            assertEquals(listOf("show:7:en-US"), fake.calls)
            owner.close()
        }
    }

    @Test fun specialsZeroAndExactNonIndexSeasonUseRealNumbers() = runTest {
        listOf(0, 7).forEach { number ->
            val fake = WebSeasonFake().apply { season = season.copy(seasonNumber = number, episodes = emptyList()) }
            val owner = fake.owner(backgroundScope, fake.entry(number, mapOf("season.seriesId" to "7", "season.number" to "$number")))
            runCurrent()
            assertEquals(number, owner.state.value.current!!.selected)
            assertEquals(listOf("show:7:en-US", "season:7:$number:en-US"), fake.calls)
            owner.close()
        }
    }

    @Test fun seasonSelectionNavigatesRequiredRouteWithoutReusingHolderOrChangingSavedSelection() = runTest {
        val fake = WebSeasonFake()
        val entry = fake.entry()
        val owner = fake.owner(backgroundScope, entry)
        runCurrent()
        val context = owner.state.value.context!!
        owner.selectSeason(context, 0)
        owner.selectSeason(context, 3)
        assertEquals(listOf<WebRoute>(WebRoute.Season(7, 0)), fake.destinations)
        assertEquals("7", entry.getString("selectedSeason"))
        assertEquals(2, fake.calls.size)
        owner.close()
    }

    @Test fun showAndEpisodeFailuresAreIndependentAndRetryReplacesInstance() = runTest {
        val fake = WebSeasonFake().apply { episodeError = TmdbError.Network }
        val owner = fake.owner(backgroundScope)
        runCurrent()
        assertIs<SectionState.Content<*>>(owner.state.value.current!!.show)
        assertIs<SectionState.Error>(owner.state.value.current!!.episodes)
        val context = owner.state.value.context!!
        fake.episodeError = null
        owner.retry(context)
        runCurrent()
        assertTrue(context !== owner.state.value.context)
        assertTrue(owner.state.value.bodyReady)
        owner.close()
        val failed = WebSeasonFake().apply { showError = TmdbError.NotFound }
        val other = failed.owner(backgroundScope)
        runCurrent()
        assertIs<SectionState.Error>(other.state.value.current!!.show)
        assertTrue(failed.calls.none { it.startsWith("season:") })
        other.close()
    }

    @Test fun mismatchedShowAndSeasonIdentitiesAreNotRenderedAsRequestedData() = runTest {
        val wrongShow = WebSeasonFake().apply { show = show.copy(id = 8) }
        val a = wrongShow.owner(backgroundScope)
        runCurrent()
        assertIs<SectionState.Error>(a.state.value.current!!.show)
        assertNull(a.state.value.retainedShow)
        a.close()
        val wrongSeason = WebSeasonFake().apply { season = season.copy(seasonNumber = 0) }
        val b = wrongSeason.owner(backgroundScope)
        runCurrent()
        assertIs<SectionState.Error>(b.state.value.current!!.episodes)
        assertNull(b.state.value.retainedSeason)
        b.close()
    }

    @Test fun restoredPanelRequiresActualMembershipAndValidActionsPersistExactScalars() = runTest {
        val fake = WebSeasonFake()
        val entry = fake.entry(values = mapOf("openEpisode" to "99"))
        val owner = fake.owner(backgroundScope, entry)
        runCurrent()
        val context = owner.state.value.context!!
        assertNull(owner.state.value.current!!.openEpisode)
        assertEquals("99", entry.getString("openEpisode"))
        owner.openEpisode(context, 99)
        assertEquals("99", entry.getString("openEpisode"))
        owner.openEpisode(context, 4)
        runCurrent()
        assertEquals("4", entry.getString("openEpisode"))
        assertEquals(4, owner.state.value.current!!.openEpisode)
        owner.closeEpisode(context)
        runCurrent()
        assertNull(entry.getString("openEpisode"))
        owner.close()
    }

    @Test fun idleActualInvalidationReplacesAndClickTimeSnapshotCheckDropsOldActions() = runTest {
        val fake = WebSeasonFake()
        val owner = fake.owner(backgroundScope)
        runCurrent()
        val old = owner.state.value.context!!
        fake.generation.bump()
        owner.selectSeason(old, 0)
        owner.openEpisode(old, 4)
        assertTrue(fake.destinations.isEmpty())
        fake.invalidations.emit(Unit)
        runCurrent()
        assertTrue(old !== owner.state.value.context)
        assertEquals(fake.generation.current, owner.state.value.context!!.appSnapshot)
        owner.close()
    }

    @Test fun reentrantReadyObserverCanDisposeBeforeConstructorIo() = runTest {
        val fake = WebSeasonFake()
        val owner = fake.owner(backgroundScope)
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            owner.state.collect { if (it.reader is WebSeasonReader.Ready) owner.close() }
        }
        runCurrent()
        assertTrue(fake.calls.isEmpty())
        assertNull(owner.state.value.context)
        observer.cancel()
    }

    @Test fun reentrantSaverCallbackRevokesBeforeFollowingWritesAndConstructorPublication() = runTest {
        val fake = WebSeasonFake()
        val entry = fake.entry()
        val parent = Job(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + parent)
        entry.changed = { parent.cancel() }
        val owner = fake.owner(scope, entry)
        runCurrent()
        assertNull(owner.state.value.current)
        assertFalse(owner.state.value.bodyReady)
        assertEquals("7", entry.getString("selectedSeason"))
        owner.close()
    }

    @Test fun upstreamRetryReentrancyAndParentCancellationPreventFurtherIoAndActions() = runTest {
        val fake = WebSeasonFake()
        val parent = Job(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + parent)
        val owner = fake.owner(scope, retryUpstream = { parent.cancel() })
        runCurrent()
        val context = owner.state.value.context!!
        val calls = fake.calls.toList()
        owner.retryStorage()
        runCurrent()
        owner.back()
        owner.show(context)
        owner.selectSeason(context, 0)
        owner.retry(context)
        assertEquals(calls, fake.calls)
        assertTrue(fake.destinations.isEmpty())
        assertNull(owner.state.value.context)
        owner.close()
    }

    @Test fun scrollWritesRequireMatchingRealBodyAndNeverOverwriteSavedTargetWhileLoading() = runTest {
        val fake = WebSeasonFake().apply { episodeGate = CompletableDeferred() }
        val entry = fake.entry(values = mapOf("season.index" to "4", "season.offset" to "37"))
        val owner = fake.owner(backgroundScope, entry)
        runCurrent()
        val context = owner.state.value.context!!
        owner.saveScroll(context, 0, 0)
        assertEquals(4 to 37, owner.scrollTarget)
        assertEquals("4", entry.getString("season.index"))
        fake.episodeGate!!.complete(Unit)
        runCurrent()
        owner.saveScroll(context, 1, 37)
        assertEquals("1", entry.getString("season.index"))
        assertEquals("37", entry.getString("season.offset"))
        owner.close()
    }
}
