package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.presentation.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MobileExploreViewModelTest {
    @Test fun routeDefaultsAndEveryHomeSourcePreserveExactIdentity() {
        assertEquals(BrowseSelection.Trending(TrendingWindow.Week), Json.decodeFromString<MobileBrowseRoute>("""{"row":"Trending"}""").selection())
        HomeRow.entries.forEach { row ->
            TrendingWindow.entries.forEach { window ->
                val route = MobileBrowseRoute(row.name, window.name)
                assertEquals(BrowseSelection.fromHomeRow(row, window), Json.decodeFromString<MobileBrowseRoute>(Json.encodeToString(route)).selection())
            }
        }
        assertFailsWith<IllegalArgumentException> { MobileBrowseRoute("Trending", "invalid").selection() }
    }

    @Test fun typesRetainIndependentSelectionsAndLoadedPagesAndRestorePrimitiveScalars() = runTest {
        val f = MobileFixture(backgroundScope)
        val saved = SavedStateHandle()
        val model = MobileExploreViewModel(f.graph, saved, scope = backgroundScope)
        runCurrent()
        model.select(BrowseSelection.Movies(MovieList.Upcoming))
        runCurrent()
        val movies = model.currentPage.value
        movies.browse.loadMore()
        runCurrent()
        val loaded = movies.browse.uiState.value
        assertEquals(2, (loaded.grid as SectionState.Content).value.page)
        model.select(BrowseSelection.Tv(TvList.OnTheAir))
        runCurrent()
        val tv = model.currentPage.value
        model.selectType(MobileExploreType.People)
        runCurrent()
        assertEquals(BrowseSelection.People, model.currentPage.value.browse.uiState.value.selection)
        assertTrue(model.currentPage.value.browse.uiState.value.genres.isEmpty())
        model.selectType(MobileExploreType.Movies)
        runCurrent()
        assertSame(movies, model.currentPage.value)
        assertEquals(loaded, model.currentPage.value.browse.uiState.value)
        model.selectType(MobileExploreType.Tv)
        runCurrent()
        assertSame(tv, model.currentPage.value)
        val restored = MobileExploreViewModel(f.graph, saved, scope = backgroundScope)
        runCurrent()
        assertEquals(BrowseSelection.Tv(TvList.OnTheAir), restored.currentPage.value.browse.uiState.value.selection)
        restored.selectType(MobileExploreType.Movies)
        runCurrent()
        assertEquals(BrowseSelection.Movies(MovieList.Upcoming), restored.currentPage.value.browse.uiState.value.selection)
        assertEquals(1, (restored.currentPage.value.browse.uiState.value.grid as SectionState.Content).value.page)
        f.graph.close()
    }

    @Test fun homeTrendingStaysMixedAndIndependentFromMoviesAndOtherRouteOwners() = runTest {
        val f = MobileFixture(backgroundScope)
        val root = MobileExploreViewModel(f.graph, SavedStateHandle(), scope = backgroundScope)
        val handoff = MobileExploreViewModel(f.graph, SavedStateHandle(), MobileBrowseRoute("Trending", "Day").selection(), backgroundScope)
        runCurrent()
        val trend = handoff.currentPage.value
        assertEquals(BrowseSelection.Trending(TrendingWindow.Day), trend.browse.uiState.value.selection)
        assertTrue(f.api.calls.contains("day-en-US/1"))
        handoff.selectType(MobileExploreType.Movies)
        runCurrent()
        handoff.select(BrowseSelection.Movies(MovieList.NowPlaying))
        runCurrent()
        assertEquals(BrowseSelection.Movies(MovieList.Popular), root.currentPage.value.browse.uiState.value.selection)
        handoff.selectType(MobileExploreType.Trending)
        runCurrent()
        assertSame(trend, handoff.currentPage.value)
        f.graph.close()
    }

    @Test fun failedNextPageHasNoViewportRetryAndExplicitRetryRetainsRealItems() = runTest {
        val f = MobileFixture(backgroundScope)
        val model = MobileExploreViewModel(f.graph, SavedStateHandle(), scope = backgroundScope)
        runCurrent()
        val browse = model.currentPage.value.browse
        val first = (browse.uiState.value.grid as SectionState.Content).value
        f.api.pageFailure = 2
        browse.loadMore()
        runCurrent()
        repeat(3) { browse.loadMore(); runCurrent() }
        assertEquals(1, f.api.calls.count { it == "popular-en-US-IN/2" })
        assertEquals(first.items, (browse.uiState.value.grid as SectionState.Content).value.items)
        assertEquals(SectionErrorKind.Offline, (browse.uiState.value.grid as SectionState.Content).value.moreError)
        f.api.pageFailure = null
        model.retry()
        runCurrent()
        val recovered = (browse.uiState.value.grid as SectionState.Content).value
        assertEquals(2, recovered.page)
        assertNull(recovered.moreError)
        assertEquals(first.items, recovered.items.take(first.items.size))
        f.graph.close()
    }

    @Test fun equalSettingsRecoveryRestartsGraphAndHolderReadsWithoutOwnerReplacement() = runTest {
        val f = MobileFixture(backgroundScope)
        val model = MobileExploreViewModel(f.graph, SavedStateHandle(), scope = backgroundScope)
        runCurrent()
        val page = model.currentPage.value
        val before = (page.browse.uiState.value.grid as SectionState.Content).value
        val settingsBeforeFailure = f.graph.settings.value
        f.store.fail = true
        f.store.values.value = mapOf("unrelated" to "failed")
        page.browse.loadMore()
        runCurrent()
        assertTrue(f.graph.settingsFailed.value)
        assertTrue(page.storageFailed.value)
        assertEquals(before.items, (page.browse.uiState.value.grid as SectionState.Content).value.items)
        val subscriptionsBeforeRetry = f.store.subscriptions
        f.store.fail = false
        model.retryStorage()
        runCurrent()
        assertSame(page, model.currentPage.value)
        // Catalog settings.current() also takes one-shot subscriptions; prove the graph reader is live below.
        assertTrue(f.store.subscriptions > subscriptionsBeforeRetry)
        assertFalse(f.graph.settingsFailed.value)
        assertFalse(page.storageFailed.value)
        assertEquals(settingsBeforeFailure, f.graph.settings.value)
        val recovered = (page.browse.uiState.value.grid as SectionState.Content).value
        assertEquals(2, recovered.page)
        assertEquals(before.items, recovered.items.take(before.items.size))
        assertNull(recovered.moreError)
        f.settings.update { it.copy(haptics = false) }
        runCurrent()
        assertEquals(false, f.graph.settings.value?.haptics)
        assertTrue(f.guest.rows.value.isEmpty())
        f.graph.close()
    }

    @Test fun retainedTypesReconcileChangedGraphSettingsAndGeneration() = runTest {
        val f = MobileFixture(backgroundScope)
        val model = MobileExploreViewModel(f.graph, SavedStateHandle(), scope = backgroundScope)
        runCurrent()
        val movies = model.currentPage.value
        movies.browse.loadMore()
        runCurrent()
        model.selectType(MobileExploreType.Tv)
        runCurrent()
        val tv = model.currentPage.value
        val generation = f.generation.current
        f.settings.update { it.copy(language = "hi-IN") }
        runCurrent()
        assertEquals(generation + 1, f.generation.current)
        assertEquals(1, (movies.browse.uiState.value.grid as SectionState.Content).value.page)
        assertTrue((movies.browse.uiState.value.grid as SectionState.Content).value.items.all { (it as com.techgv.cineverse.domain.Hero.OfTitle).title.title.contains("hi-IN") })
        assertTrue((tv.browse.uiState.value.grid as SectionState.Content).value.items.all { (it as com.techgv.cineverse.domain.Hero.OfTitle).title.title.contains("hi-IN") })
        f.graph.close()
    }

    @Test fun unknownSettingsAndGenreErrorsAreExplicitAndRecoverInSameOwner() = runTest {
        val f = MobileFixture(backgroundScope)
        f.store.fail = true
        val model = MobileExploreViewModel(f.graph, SavedStateHandle(), scope = backgroundScope)
        runCurrent()
        val page = model.currentPage.value
        assertFalse(page.browse.uiState.value.settingsKnown)
        assertTrue(f.graph.settingsFailed.value)
        assertNotNull(page.browse.uiState.value.genresError)
        f.store.fail = false
        model.retryStorage()
        runCurrent()
        assertSame(page, model.currentPage.value)
        assertTrue(page.browse.uiState.value.settingsKnown)
        assertNull(page.browse.uiState.value.genresError)
        f.api.failGenresStorage = true
        f.catalog.onContentSettingsChanged()
        model.retryGenres()
        runCurrent()
        assertNotNull(page.browse.uiState.value.genresError)
        assertTrue(page.storageFailed.value)
        f.api.failGenresStorage = false
        model.retryGenres()
        runCurrent()
        assertNull(page.browse.uiState.value.genresError)
        assertFalse(page.storageFailed.value)
        f.graph.close()
    }

    @Test fun disposedEntryDropsLateDiagnosticsWhileGraphRemainsLive() = runTest {
        val f = MobileFixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        f.api.genreGate = gate
        f.api.failGenresStorage = true
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val model = MobileExploreViewModel(f.graph, SavedStateHandle(), scope = scope)
        runCurrent()
        val page = model.currentPage.value
        scope.cancel()
        gate.complete(Unit)
        runCurrent()
        assertFalse(page.storageFailed.value)
        assertNull(page.browse.uiState.value.genresError)
        f.settings.update { it.copy(haptics = false) }
        runCurrent()
        assertEquals(false, f.graph.settings.value?.haptics)
        f.graph.close()
    }
}
