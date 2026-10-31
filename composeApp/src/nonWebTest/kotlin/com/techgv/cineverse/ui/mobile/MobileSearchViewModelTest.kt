package com.techgv.cineverse.ui.mobile

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.IOException as DataStoreIOException
import okio.IOException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.techgv.cineverse.data.tmdb.SearchFacet
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.presentation.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MobileSearchViewModelTest {
    @Test fun nativeAdapterReadFailuresRetainUnknownAndKnownStateUntilExplicitEqualRecovery() = runTest {
        val f = NativeMobileStorageBoundaryFixture(backgroundScope)
        f.native.readFailure = DataStoreIOException("owned-fixture disk read")
        val model = MobileSearchViewModel(f.graph, SavedStateHandle(mapOf("productSearch.query" to "fixture")), backgroundScope)
        val search = assertNotNull(model.search)
        runCurrent()
        assertTrue(f.graph.settingsFailed.value)
        assertFalse(search.uiState.value.settingsKnown)
        assertNotNull(search.uiState.value.settingsError)
        assertNotNull(search.uiState.value.recentReadError)
        assertNull(search.uiState.value.recentQueries)
        assertNull(search.uiState.value.results)
        f.native.readFailure = null
        runCurrent()
        assertFalse(search.uiState.value.settingsKnown)
        assertNull(search.uiState.value.recentQueries)
        model.retrySettings(); search.retryRecentRead(); runCurrent()
        assertTrue(search.uiState.value.settingsKnown)
        assertFalse(f.graph.settingsFailed.value)
        assertNull(search.uiState.value.settingsError)
        assertNull(search.uiState.value.recentReadError)
        model.submit(); runCurrent()
        search.loadMore(); runCurrent()
        val retained = search.uiState.value.results
        val settings = f.graph.settings.value
        assertEquals(listOf("fixture"), search.uiState.value.recentQueries)
        f.native.readFailure = CorruptionException("owned-fixture corrupt read")
        runCurrent()
        assertTrue(f.graph.settingsFailed.value)
        assertNotNull(search.uiState.value.settingsError)
        assertNotNull(search.uiState.value.recentReadError)
        assertEquals(listOf("fixture"), search.uiState.value.recentQueries)
        assertEquals(retained, search.uiState.value.results)
        assertEquals(settings, f.graph.settings.value)
        f.native.readFailure = null
        runCurrent()
        assertNotNull(search.uiState.value.settingsError)
        model.retrySettings(); search.retryRecentRead(); runCurrent()
        assertSame(search, model.search)
        assertFalse(f.graph.settingsFailed.value)
        assertNull(search.uiState.value.settingsError)
        assertNull(search.uiState.value.recentReadError)
        assertEquals(retained, search.uiState.value.results)
        assertEquals(listOf("fixture"), search.uiState.value.recentQueries)
        assertEquals(1, f.native.edits)
        search.setQuery("usable"); advanceTimeBy(300); runCurrent()
        assertTrue(search.uiState.value.results is SectionState.Content)
        assertEquals(1, f.native.edits)
        f.graph.close()
    }

    @Test fun nativeAdapterWriteFailureReportsFailedKeepsOwnerUsableAndNeverReplays() = runTest {
        val f = NativeMobileStorageBoundaryFixture(backgroundScope)
        val model = MobileSearchViewModel(f.graph, SavedStateHandle(mapOf("productSearch.query" to "fixture")), backgroundScope)
        val search = assertNotNull(model.search)
        runCurrent()
        f.native.writeFailure = IOException("owned-fixture disk write")
        model.submit(); runCurrent()
        assertEquals(RecentMutationStatus.Failed, search.uiState.value.recentMutation)
        assertEquals(emptyList(), search.uiState.value.recentQueries)
        assertEquals(1, f.native.edits)
        f.native.writeFailure = null
        f.native.readFailure = CorruptionException("owned-fixture corrupt read")
        runCurrent()
        assertNotNull(search.uiState.value.recentReadError)
        f.native.readFailure = null
        search.retryRecentRead(); model.retrySettings(); model.retry(); runCurrent()
        assertEquals(1, f.native.edits)
        assertEquals(RecentMutationStatus.Failed, search.uiState.value.recentMutation)
        assertEquals(emptyList(), search.uiState.value.recentQueries)
        search.setQuery("usable"); advanceTimeBy(300); runCurrent()
        assertTrue(search.uiState.value.results is SectionState.Content)
        assertEquals(1, f.native.edits)
        model.submit(); runCurrent()
        assertSame(search, model.search)
        assertEquals(2, f.native.edits)
        assertEquals(RecentMutationStatus.Succeeded, search.uiState.value.recentMutation)
        assertEquals(listOf("usable"), search.uiState.value.recentQueries)
        f.graph.close()
    }

    @Test fun fourFacetsUseTypedSourcesLoadedOnlyCountsAndRealDestinations() = runTest {
        val f = MobileFixture(backgroundScope)
        val model = MobileSearchViewModel(f.graph, SavedStateHandle(mapOf("productSearch.query" to "  dune  ")), backgroundScope)
        val search = assertNotNull(model.search)
        runCurrent()
        SearchFacet.entries.forEach { facet ->
            search.selectFacet(facet)
            runCurrent()
            val state = search.uiState.value
            assertEquals(12, state.loadedCount(facet))
            SearchFacet.entries.filterNot { it == facet }.forEach { assertNull(state.loadedCount(it)) }
            assertTrue(f.api.calls.contains("search-$facet-dune-en-US-false/1"))
            val hero = (state.results as SectionState.Content).value.items.first()
            when (hero) {
                is Hero.OfTitle -> {
                    assertEquals(101, hero.title.ref.id)
                    assertEquals(if (facet == SearchFacet.Tv) "tv" else "movie", hero.title.ref.mediaType.path)
                    assertEquals(MobileDestination.Detail(hero.title.ref, hero.title.posterPath, hero.title.title), hero.searchDestination())
                }
                is Hero.OfPerson -> assertEquals(MobileDestination.Person(101), hero.searchDestination())
            }
        }
        assertEquals(0, f.store.edits)
        f.graph.close()
    }

    @Test fun rawLimitsRestoreWithoutTruncationOrRequestsAndRemainCorrectable() = runTest {
        val f = MobileFixture(backgroundScope)
        val raw = "  " + "x".repeat(201) + "  "
        val saved = SavedStateHandle(mapOf("productSearch.query" to raw, "productSearch.facet" to "Tv"))
        val model = MobileSearchViewModel(f.graph, saved, backgroundScope)
        val search = assertNotNull(model.search)
        runCurrent()
        assertEquals(raw, search.uiState.value.query)
        assertEquals(SearchInputError.TooLong, search.uiState.value.inputError)
        assertNull(search.uiState.value.results)
        model.submit()
        assertTrue(f.api.calls.isEmpty())
        assertEquals(0, f.store.edits)
        search.setQuery("x".repeat(200))
        advanceTimeBy(300); runCurrent()
        assertNull(search.uiState.value.inputError)
        assertTrue(search.uiState.value.results is SectionState.Content)
        assertEquals("x".repeat(200), saved.get<String>("productSearch.query"))
        assertEquals(0, f.store.edits)
        f.graph.close()
    }

    @Test fun malformedSavedPrimitivesAndUnknownFacetRejectBeforeAnyOwnerReads() = runTest {
        val cases = listOf(mapOf("productSearch.query" to 123), mapOf("productSearch.facet" to false),
            mapOf("productSearch.facet" to "unknown"), mapOf("productSearch.query" to "real", "productSearch.facet" to 1))
        cases.forEach { raw ->
            val f = MobileFixture(backgroundScope)
            runCurrent()
            val reads = f.store.subscriptions
            val saved = SavedStateHandle(raw)
            val model = MobileSearchViewModel(f.graph, saved, backgroundScope)
            runCurrent()
            assertNull(model.search)
            assertEquals(reads, f.store.subscriptions)
            assertTrue(f.api.calls.isEmpty())
            model.submit(); model.chooseQuery("do not overwrite")
            runCurrent()
            assertEquals(0, f.store.edits)
            assertEquals(raw, saved.keys().associateWith { saved.get<Any?>(it) })
            f.graph.close()
        }
    }

    @Test fun typingDebounceFacetRestoreAndReadRetriesNeverWriteOnlyExplicitIntentsDo() = runTest {
        val f = MobileFixture(backgroundScope)
        val saved = SavedStateHandle()
        val model = MobileSearchViewModel(f.graph, saved, backgroundScope)
        val search = assertNotNull(model.search)
        runCurrent()
        assertTrue(search.uiState.value.showingSuggestions)
        assertTrue(f.api.calls.contains("week-en-US/1"))
        SearchFacet.entries.forEach { assertNull(search.uiState.value.loadedCount(it)) }
        assertEquals(emptyList(), search.uiState.value.recentQueries)
        search.setQuery("d"); advanceTimeBy(200)
        search.setQuery("dune"); advanceTimeBy(299); runCurrent()
        assertTrue(f.api.calls.none { it.startsWith("search-") })
        advanceTimeBy(1); runCurrent()
        search.selectFacet(SearchFacet.Movie); runCurrent()
        search.retryRecentRead(); model.retrySettings(); model.retry(); runCurrent()
        val restored = MobileSearchViewModel(f.graph, saved, backgroundScope)
        runCurrent()
        assertEquals("dune", assertNotNull(restored.search).uiState.value.query)
        assertEquals(SearchFacet.Movie, restored.search.uiState.value.facet)
        assertEquals(0, f.store.edits)
        model.submit(); runCurrent()
        assertEquals(listOf("dune"), search.uiState.value.recentQueries)
        search.setQuery(""); runCurrent()
        assertEquals(1, f.store.edits)
        model.chooseQuery("chosen"); runCurrent()
        assertEquals(listOf("chosen", "dune"), search.uiState.value.recentQueries)
        search.clearRecentQueries(); runCurrent()
        assertEquals(emptyList(), search.uiState.value.recentQueries)
        assertEquals(3, f.store.edits)
        f.graph.close()
    }

    @Test fun failedWritesAreHonestAndNeverReplayedByReadOrSettingsRecovery() = runTest {
        val f = MobileFixture(backgroundScope)
        val model = MobileSearchViewModel(f.graph, SavedStateHandle(mapOf("productSearch.query" to "fixture")), backgroundScope)
        val search = assertNotNull(model.search)
        runCurrent()
        f.store.failWrites = true
        model.submit(); runCurrent()
        assertEquals(RecentMutationStatus.Failed, search.uiState.value.recentMutation)
        assertEquals(emptyList(), search.uiState.value.recentQueries)
        f.store.failWrites = false
        search.retryRecentRead(); model.retrySettings(); model.retry(); runCurrent()
        assertEquals(1, f.store.edits)
        assertEquals(RecentMutationStatus.Failed, search.uiState.value.recentMutation)
        model.submit(); runCurrent()
        assertEquals(2, f.store.edits)
        assertEquals(RecentMutationStatus.Succeeded, search.uiState.value.recentMutation)
        assertEquals(listOf("fixture"), search.uiState.value.recentQueries)
        f.graph.close()
    }

    @Test fun corruptRecentReadStaysUnknownUntilExplicitClearWithoutWritingOnRetry() = runTest {
        val f = MobileFixture(backgroundScope)
        f.store.values.value = mapOf("search.recentQueries" to "broken", "owned-fixture" to "keep")
        val model = MobileSearchViewModel(f.graph, SavedStateHandle(), backgroundScope)
        val search = assertNotNull(model.search)
        runCurrent()
        assertNull(search.uiState.value.recentQueries)
        assertNotNull(search.uiState.value.recentReadError)
        assertTrue(search.uiState.value.results is SectionState.Content)
        search.retryRecentRead(); runCurrent()
        assertEquals(0, f.store.edits)
        assertEquals("broken", f.store.values.value["search.recentQueries"])
        search.clearRecentQueries(); runCurrent()
        assertNull(search.uiState.value.recentReadError)
        assertEquals(emptyList(), search.uiState.value.recentQueries)
        assertEquals("keep", f.store.values.value["owned-fixture"])
        f.graph.close()
    }

    @Test fun firstSettingsFailureAndEqualRecoveryRestartBothRawReadersInSameOwner() = runTest {
        val f = MobileFixture(backgroundScope)
        f.store.fail = true
        val model = MobileSearchViewModel(f.graph, SavedStateHandle(mapOf("productSearch.query" to "fixture")), backgroundScope)
        val search = assertNotNull(model.search)
        runCurrent()
        assertTrue(f.graph.settingsFailed.value)
        assertFalse(search.uiState.value.settingsKnown)
        assertNotNull(search.uiState.value.settingsError)
        assertNull(search.uiState.value.results)
        assertTrue(f.api.calls.isEmpty())
        f.store.fail = false
        model.retrySettings(); search.retryRecentRead(); runCurrent()
        assertSame(search, model.search)
        assertTrue(search.uiState.value.settingsKnown)
        assertFalse(f.graph.settingsFailed.value)
        search.loadMore(); runCurrent()
        val pages = (search.uiState.value.results as SectionState.Content).value
        f.store.fail = true
        f.store.values.value = mapOf("owned-fixture" to "failed")
        runCurrent()
        assertNotNull(search.uiState.value.settingsError)
        assertNotNull(search.uiState.value.recentReadError)
        assertEquals(emptyList(), search.uiState.value.recentQueries)
        f.store.fail = false
        model.retrySettings(); search.retryRecentRead(); runCurrent()
        assertEquals(pages, (search.uiState.value.results as SectionState.Content).value)
        assertNull(search.uiState.value.settingsError)
        f.settings.update { it.copy(language = "hi-IN") }; runCurrent()
        assertEquals("hi-IN", f.graph.settings.value?.language)
        assertTrue(f.api.calls.contains("search-Multi-fixture-hi-IN-false/1"))
        assertEquals(1, f.store.edits) // Only the explicit fixture settings update.
        f.graph.close()
    }

    @Test fun retainedPageErrorRequiresExplicitRetryAndKeepsRealRows() = runTest {
        val f = MobileFixture(backgroundScope)
        val model = MobileSearchViewModel(f.graph, SavedStateHandle(mapOf("productSearch.query" to "fixture")), backgroundScope)
        val search = assertNotNull(model.search)
        runCurrent()
        val first = (search.uiState.value.results as SectionState.Content).value.items
        f.api.pageFailure = 2
        search.loadMore(); runCurrent()
        repeat(3) { search.loadMore(); runCurrent() }
        assertEquals(1, f.api.calls.count { it == "search-Multi-fixture-en-US-false/2" })
        assertEquals(first, (search.uiState.value.results as SectionState.Content).value.items)
        assertEquals(SectionErrorKind.Offline, (search.uiState.value.results as SectionState.Content).value.moreError)
        f.api.pageFailure = null
        model.retry(); runCurrent()
        assertEquals(2, (search.uiState.value.results as SectionState.Content).value.page)
        assertEquals(first, (search.uiState.value.results as SectionState.Content).value.items.take(first.size))
        assertEquals(0, f.store.edits)
        f.graph.close()
    }

    @Test fun rawSettingsCanRecoverWhileGraphReaderIsDeadAndExplicitRetryRecoversBoth() = runTest {
        val f = MobileFixture(backgroundScope)
        f.store.fail = true
        runCurrent()
        assertTrue(f.graph.settingsFailed.value)
        assertNull(f.graph.settings.value)
        f.store.fail = false
        val model = MobileSearchViewModel(f.graph, SavedStateHandle(mapOf("productSearch.query" to "fixture")), backgroundScope)
        val search = assertNotNull(model.search)
        runCurrent()
        assertTrue(search.uiState.value.settingsKnown) // Raw flow, not a fabricated graph default/last-value stream.
        search.loadMore(); runCurrent()
        val pages = search.uiState.value.results
        assertNull(f.graph.settings.value)
        model.retrySettings(); runCurrent()
        assertSame(search, model.search)
        assertNotNull(f.graph.settings.value)
        assertFalse(f.graph.settingsFailed.value)
        assertEquals(pages, search.uiState.value.results)
        assertEquals(0, f.store.edits)
        f.graph.close()
    }

    @Test fun equalRawSettingsRecoveryRetriesTheActualStorageFailedNextPage() = runTest {
        val f = MobileFixture(backgroundScope)
        val model = MobileSearchViewModel(f.graph, SavedStateHandle(mapOf("productSearch.query" to "fixture")), backgroundScope)
        val search = assertNotNull(model.search)
        runCurrent()
        val first = (search.uiState.value.results as SectionState.Content).value.items
        // Fail only the new one-shot catalog settings read; existing graph/holder observations stay alive.
        f.store.fail = true
        search.loadMore(); runCurrent()
        val failed = (search.uiState.value.results as SectionState.Content).value
        assertEquals(first, failed.items)
        assertEquals(SectionErrorKind.Generic, failed.moreError)
        repeat(3) { search.loadMore(); runCurrent() }
        assertEquals(failed, (search.uiState.value.results as SectionState.Content).value)
        f.store.fail = false
        model.retrySettings(); runCurrent()
        assertSame(search, model.search)
        val recovered = (search.uiState.value.results as SectionState.Content).value
        assertEquals(2, recovered.page)
        assertEquals(first, recovered.items.take(first.size))
        assertNull(recovered.moreError)
        assertEquals(0, f.store.edits)
        f.graph.close()
    }

    @Test fun initialNetworkErrorRecoversAndEntryDisposalStopsSearchButNotGraph() = runTest {
        val f = MobileFixture(backgroundScope)
        f.api.failure = TmdbError.Network
        val model = MobileSearchViewModel(f.graph, SavedStateHandle(mapOf("productSearch.query" to "fixture")), backgroundScope)
        val search = assertNotNull(model.search)
        val store = ViewModelStore().apply { put("search", model) }
        runCurrent()
        assertEquals(SectionState.Error(SectionErrorKind.Offline), search.uiState.value.results)
        f.api.failure = null
        model.retry(); runCurrent()
        assertTrue(search.uiState.value.results is SectionState.Content)
        assertEquals(3, f.store.activeSubscriptions) // Graph + raw settings + recent, no graph-wide Search cache.
        store.clear(); runCurrent()
        val state = search.uiState.value
        assertEquals(1, f.store.activeSubscriptions)
        search.setQuery("discarded"); search.commitQuery(); search.retryRecentRead()
        advanceTimeBy(300); runCurrent()
        assertEquals(state, search.uiState.value)
        assertEquals(0, f.store.edits)
        f.settings.update { it.copy(haptics = false) }; runCurrent()
        assertEquals(false, f.graph.settings.value?.haptics)
        f.graph.close(); runCurrent()
        assertEquals(0, f.store.activeSubscriptions)
    }
}
