package com.techgv.cineverse.presentation

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HomeStorageRecoveryTest {
    private val owners = mutableListOf<CoroutineScope>()
    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun tearDown() { owners.forEach { it.cancel() }; Dispatchers.resetMain() }

    private inner class Fixture {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { owners += it }
        val app = GenerationCounter()
        val api = FakeCatalogApi().apply { totalPages = 3 }
        val failure = StorageException("fixture")
        val reports = mutableListOf<StorageException>()
        val events = mutableListOf<TrailerEvent>()
        var preferences = Settings(language = "en-US")
        var failRead = false
        var failObserve = false
        var failGenres = false
        var subscriptions = 0
        var readHook: (suspend () -> Unit)? = null
        var observeHook: (suspend () -> Unit)? = null
        var genreHook: (suspend () -> Unit)? = null
        val ticks = MutableStateFlow(0)
        val settings = flow {
            subscriptions++
            ticks.collect {
                observeHook?.invoke()
                if (failObserve) throw failure
                emit(preferences)
            }
        }
        val transport = object : TmdbApi by api {
            override suspend fun genres(media: MediaType, language: String): GenreListDto {
                genreHook?.invoke()
                if (failGenres) throw failure
                return api.genres(media, language)
            }
        }
        val catalog = CatalogRepository(transport, app, settings = {
            readHook?.invoke()
            if (failRead) throw failure
            preferences
        })
        val holder = HomeStateHolder(catalog, app, settings, SavedStateHandleStateSaver(SavedStateHandle()), owner, reports::add)
        init { owner.launch { holder.events.collect { events += it } } }
        fun signal() { ticks.value++ }
        fun page(row: HomeRow = HomeRow.Trending) = (holder.uiState.value.rows.getValue(row) as SectionState.Content).value
    }

    @Test fun unknownStorageIsExplicitAndGenericRetryRecoversRowsGenresAndObservation() = runTest {
        val f = Fixture()
        f.failRead = true; f.failObserve = true
        advanceUntilIdle()
        assertTrue(f.holder.uiState.value.rows.values.all { it == SectionState.Error(SectionErrorKind.Generic) })
        assertEquals(SectionErrorKind.Generic, f.holder.uiState.value.settingsError)
        assertEquals(SectionErrorKind.Generic, f.holder.uiState.value.genresError)
        assertTrue(f.holder.uiState.value.genres.isEmpty())
        assertNull(f.holder.uiState.value.hero)
        assertTrue(f.reports.all { it.message == f.failure.message })
        f.holder.retryStorage()
        advanceUntilIdle()
        assertEquals(2, f.subscriptions)
        f.failRead = false; f.failObserve = false
        f.holder.retryStorage()
        advanceUntilIdle()
        assertEquals(3, f.subscriptions)
        assertTrue(f.holder.uiState.value.rows.values.all { it is SectionState.Content })
        assertEquals(mapOf(28 to "movie@en-US", 18 to "tv@en-US"), f.holder.uiState.value.genres)
        assertNull(f.holder.uiState.value.settingsError)
        assertNull(f.holder.uiState.value.genresError)
    }

    @Test fun pageAndRefreshFailuresRetainPageSelectionGenresAndRecoverWithoutDuplicates() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        val selected = Hero.OfTitle(title(88))
        f.holder.selectHero(selected)
        advanceUntilIdle()
        val genres = f.holder.uiState.value.genres
        val before = f.page()
        f.failRead = true
        f.holder.loadMore(HomeRow.Trending)
        advanceUntilIdle()
        assertEquals(before.copy(moreError = SectionErrorKind.Generic), f.page())
        assertEquals(selected, f.holder.uiState.value.hero)
        f.holder.retryStorage()
        advanceUntilIdle()
        assertEquals(before.copy(moreError = SectionErrorKind.Generic), f.page())
        f.failRead = false
        f.holder.retryStorage()
        advanceUntilIdle()
        assertEquals(2, f.page().page)
        assertEquals(6, f.page().items.size)
        assertNull(f.page().moreError)
        val paged = f.page()
        f.failRead = true
        f.holder.retry(HomeRow.Trending)
        advanceUntilIdle()
        assertEquals(paged.copy(moreError = SectionErrorKind.Generic), f.page())
        f.holder.retryStorage()
        advanceUntilIdle()
        assertEquals(paged.copy(moreError = SectionErrorKind.Generic), f.page())
        assertEquals(selected, f.holder.uiState.value.hero)
        assertEquals(genres, f.holder.uiState.value.genres)
        f.failRead = false
        f.holder.retryStorage()
        advanceUntilIdle()
        assertEquals(1, f.page().page)
        assertNull(f.page().moreError)
        assertEquals(selected, f.holder.uiState.value.hero)
    }

    @Test fun resubscriptionDetectsMissedPreferencesAndNeverResurrectsOldContext() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.holder.selectHero(Hero.OfTitle(title(88)))
        advanceUntilIdle()
        f.failObserve = true
        f.signal()
        advanceUntilIdle()
        val old = f.page()
        assertEquals(SectionErrorKind.Generic, f.holder.uiState.value.settingsError)
        f.preferences = Settings(language = "hi-IN")
        f.failRead = true
        f.failObserve = false
        f.holder.retryStorage()
        advanceUntilIdle()
        assertEquals(SectionState.Error(SectionErrorKind.Generic), f.holder.uiState.value.rows[HomeRow.Trending])
        assertNull(f.holder.uiState.value.hero)
        assertTrue(f.holder.uiState.value.genres.isEmpty())
        f.failRead = false
        f.holder.retryStorage()
        advanceUntilIdle()
        assertNotEquals(old.items, f.page().items)
        assertEquals("trending@hi-IN-1-0", (f.holder.uiState.value.hero as Hero.OfTitle).title.title)
        assertEquals(mapOf(28 to "movie@hi-IN", 18 to "tv@hi-IN"), f.holder.uiState.value.genres)
        assertNull(f.holder.uiState.value.settingsError)
        // The recovered collector remains live, not merely a one-shot read.
        f.preferences = Settings(language = "fr-FR")
        f.signal()
        advanceUntilIdle()
        assertEquals("trending@fr-FR-1-0", (f.holder.uiState.value.hero as Hero.OfTitle).title.title)
    }

    @Test fun trendAndAppGenerationSwitchesDoNotRestoreOldSelectionOrItems() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.holder.selectHero(Hero.OfTitle(title(88)), 120)
        f.failRead = true
        f.holder.selectTrend(TrendingWindow.Day)
        advanceUntilIdle()
        assertNull(f.holder.uiState.value.hero)
        assertEquals(SectionState.Error(SectionErrorKind.Generic), f.holder.uiState.value.rows[HomeRow.Trending])
        f.failRead = false
        f.holder.retryStorage()
        advanceUntilIdle()
        f.holder.selectHero(Hero.OfTitle(title(99)))
        advanceUntilIdle()
        f.app.bump()
        f.failRead = true
        f.holder.retryStorage()
        advanceUntilIdle()
        assertNull(f.holder.uiState.value.hero)
        assertTrue(f.holder.uiState.value.rows.values.all { it == SectionState.Error(SectionErrorKind.Generic) })
    }

    @Test fun genreOnlyFailureIsTypedAndExplicitRetryRecoversIt() = runTest {
        val f = Fixture()
        f.failGenres = true
        advanceUntilIdle()
        assertTrue(f.holder.uiState.value.rows.values.all { it is SectionState.Content })
        assertEquals(SectionErrorKind.Generic, f.holder.uiState.value.genresError)
        assertEquals(listOf(f.failure.message), f.reports.map { it.message })
        f.failGenres = false
        f.holder.retryStorage()
        advanceUntilIdle()
        assertNull(f.holder.uiState.value.genresError)
        assertEquals(2, f.holder.uiState.value.genres.size)
    }

    @Test fun rowRetryRetriesTheFailedNextPageInsteadOfReplacingLoadedPages() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.failRead = true
        f.holder.loadMore(HomeRow.Trending)
        advanceUntilIdle()
        f.holder.retry(HomeRow.Trending)
        advanceUntilIdle()
        assertEquals(1, f.page().page)
        assertEquals(SectionErrorKind.Generic, f.page().moreError)
        f.failRead = false
        f.holder.retry(HomeRow.Trending)
        advanceUntilIdle()
        assertEquals(2, f.page().page)
        assertEquals(6, f.page().items.size)
        assertNull(f.page().moreError)
    }

    @Test fun staleAppSettingsFailureIsIgnoredAndExplicitRetryReestablishesObservation() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        f.observeHook = { gate.await(); throw f.failure }
        f.signal()
        runCurrent()
        f.app.bump()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(f.reports.isEmpty())
        assertNull(f.holder.uiState.value.settingsError)
        f.observeHook = null
        f.preferences = Settings(language = "hi-IN")
        f.holder.retryStorage()
        advanceUntilIdle()
        assertEquals(2, f.subscriptions)
        assertEquals("trending@hi-IN-1-0", (f.holder.uiState.value.hero as Hero.OfTitle).title.title)
    }

    @Test fun canceledObservationCannotReportIntoTheReplacementSubscription() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        f.observeHook = { withContext(NonCancellable) { gate.await(); throw f.failure } }
        f.signal()
        runCurrent()
        f.holder.retryStorage()
        runCurrent()
        f.observeHook = null
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, f.subscriptions)
        assertTrue(f.reports.isEmpty())
        assertNull(f.holder.uiState.value.settingsError)
        assertTrue(f.holder.uiState.value.rows.values.all { it is SectionState.Content })
    }

    @Test fun obsoleteGenresFailureCannotClobberNewLanguageOrReportIt() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.genreHook = { withContext(NonCancellable) { gate.await(); throw f.failure } }
        runCurrent()
        f.genreHook = null
        f.preferences = Settings(language = "hi-IN")
        f.signal()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(mapOf(28 to "movie@hi-IN", 18 to "tv@hi-IN"), f.holder.uiState.value.genres)
        assertNull(f.holder.uiState.value.genresError)
        assertTrue(f.reports.isEmpty())
    }

    @Test fun staleAppGenresFailureReloadsWithoutReportingTheOldFailure() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.genreHook = { gate.await(); throw f.failure }
        runCurrent()
        f.genreHook = null
        f.app.bump()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, f.holder.uiState.value.genres.size)
        assertNull(f.holder.uiState.value.genresError)
        assertTrue(f.reports.isEmpty())
    }

    @Test fun trailerStorageFailureClearsLoadingAndGenericRetryNeverReplaysIt() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.failRead = true
        f.holder.setActive(true)
        f.holder.playTrailer(title(7))
        advanceUntilIdle()
        assertEquals(listOf<TrailerEvent>(TrailerEvent.Failed), f.events)
        assertNull(f.holder.uiState.value.trailerLoading)
        assertEquals(listOf(f.failure.message), f.reports.map { it.message })
        f.failRead = false
        f.holder.retryStorage()
        advanceUntilIdle()
        assertTrue(f.api.calls.none { it.startsWith("videos/") })
        assertEquals(1, f.events.size)
    }

    @Test fun trailerFallbackSettingsFailureIsTypedAndNotReplayed() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.failObserve = true // Catalog videos can succeed, but fallback settings.first() fails.
        f.holder.setActive(true)
        f.holder.playTrailer(title(7))
        advanceUntilIdle()
        assertEquals(listOf<TrailerEvent>(TrailerEvent.Failed), f.events)
        assertEquals(listOf(f.failure.message), f.reports.map { it.message })
        assertNull(f.holder.uiState.value.trailerLoading)
        f.failObserve = false
        f.holder.retryStorage()
        advanceUntilIdle()
        assertEquals(listOf("videos/7/en-US"), f.api.calls.filter { it.startsWith("videos/") })
        assertEquals(1, f.events.size)
    }

    @Test fun inactiveCanceledOrStaleAppTrailerFailureNeverReportsOrNavigates() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        suspend fun lateFailure(dispose: Boolean = false, staleApp: Boolean = false) {
            val gate = CompletableDeferred<Unit>()
            f.readHook = { withContext(NonCancellable) { gate.await(); throw f.failure } }
            f.holder.setActive(true)
            f.holder.playTrailer(title(7))
            runCurrent()
            if (dispose) f.owner.cancel() else if (staleApp) f.app.bump() else f.holder.setActive(false)
            gate.complete(Unit)
            advanceUntilIdle()
        }
        lateFailure()
        assertNull(f.holder.uiState.value.trailerLoading)
        lateFailure(staleApp = true)
        assertNull(f.holder.uiState.value.trailerLoading)
        lateFailure(dispose = true)
        assertTrue(f.events.isEmpty())
        assertTrue(f.reports.isEmpty())
    }

    @Test fun lateSettingsAndGenreFailuresCannotReportIntoADisposedOwner() = runTest {
        val gate = CompletableDeferred<Unit>()
        val reports = mutableListOf<StorageException>()
        val failure = StorageException("fixture")
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { owners += it }
        val app = GenerationCounter()
        val settings = flow<Settings> { withContext(NonCancellable) { gate.await(); throw failure } }
        val catalog = CatalogRepository(FakeCatalogApi(), app, settings = {
            withContext(NonCancellable) { gate.await(); throw failure }
        })
        val holder = HomeStateHolder(catalog, app, settings, SavedStateHandleStateSaver(SavedStateHandle()), owner, reports::add)
        runCurrent()
        owner.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(reports.isEmpty())
        assertTrue(holder.uiState.value.rows.values.all { it == SectionState.Loading })
    }

    @Test fun programmerErrorsAndCancellationFromEveryStorageBoundaryAreNotReported() = runTest {
        val errors = mutableListOf<Throwable>()
        val reports = mutableListOf<StorageException>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> errors += e }).also { owners += it }
        val app = GenerationCounter()
        val bug = IllegalStateException("fixture bug")
        val catalog = CatalogRepository(FakeCatalogApi(), app, settings = { throw bug })
        val holder = HomeStateHolder(catalog, app, flow { throw bug }, SavedStateHandleStateSaver(SavedStateHandle()), owner, reports::add)
        advanceUntilIdle()
        assertEquals(9, errors.size) // seven rows, genres, observation
        assertTrue(errors.all { it is IllegalStateException && it.message == bug.message })
        holder.setActive(true)
        holder.playTrailer(title(7))
        advanceUntilIdle()
        assertEquals(10, errors.size)
        assertTrue(reports.isEmpty())
        val cancelCatalog = CatalogRepository(FakeCatalogApi(), app, settings = { throw CancellationException("fixture") })
        HomeStateHolder(cancelCatalog, app, flow { throw CancellationException("fixture") }, SavedStateHandleStateSaver(SavedStateHandle()), owner, reports::add)
        advanceUntilIdle()
        assertEquals(10, errors.size)
        assertTrue(reports.isEmpty())
    }
}
