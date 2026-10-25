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
class ProductBrowseStateHolderTest {
    private val owners = mutableListOf<CoroutineScope>()
    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun tearDown() { owners.forEach { it.cancel() }; Dispatchers.resetMain() }

    private inner class Fixture(
        initial: BrowseSelection = BrowseSelection.Movies(MovieList.Popular),
        val saver: StateSaver = SavedStateHandleStateSaver(SavedStateHandle()),
    ) {
        val errors = mutableListOf<Throwable>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> errors += e }).also { owners += it }
        val app = GenerationCounter()
        val failure = StorageException("fixture")
        val reports = mutableListOf<StorageException>()
        var preferences = Settings()
        var readError: Exception? = null
        var observeError: Exception? = null
        var genreError: Exception? = null
        var pageError: Exception? = null
        var failPage = 2
        var empty = false
        var subscriptions = 0
        var readHook: (suspend () -> Unit)? = null
        var observeHook: (suspend () -> Unit)? = null
        var genreHook: (suspend () -> Unit)? = null
        val ticks = MutableStateFlow(0)
        val settings = flow {
            subscriptions++
            ticks.collect {
                observeHook?.invoke()
                observeError?.let { throw it }
                emit(preferences)
            }
        }
        val api = FakeCatalogApi().apply { totalPages = 3 }
        val discoverCalls = mutableListOf<String>()
        val transport = object : TmdbApi by api {
            override suspend fun movieList(list: MovieList, language: String, region: String, page: Int): PageDto<MediaItemDto> {
                if (page == failPage) pageError?.let { throw it }
                return if (empty) PageDto(page, emptyList(), 1) else api.movieList(list, language, region, page)
            }
            override suspend fun genres(media: MediaType, language: String): GenreListDto {
                genreHook?.invoke()
                genreError?.let { throw it }
                return api.genres(media, language)
            }
            override suspend fun discover(media: MediaType, genreIds: List<Int>, sort: DiscoverSort, language: String, region: String?, page: Int): PageDto<MediaItemDto> {
                discoverCalls += "$media/$genreIds/$sort/$language/$region/$page"
                return PageDto(page, listOf(MediaItemDto(7, title = "discover", name = "discover")), 1)
            }
        }
        val catalog = CatalogRepository(transport, app, settings = {
            readHook?.invoke()
            readError?.let { throw it }
            preferences
        })
        val holder = ProductBrowseStateHolder(catalog, app, settings, saver, initial, owner, reports::add)
        fun signal() { ticks.value++ }
        fun page() = (holder.uiState.value.grid as SectionState.Content).value
        fun firstTitle() = (page().items.first() as Hero.OfTitle).title.title
    }

    @Test fun curatedSourcesPeopleAndTrendingUseRealSeparateCachedEndpoints() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        for (list in MovieList.entries) {
            f.holder.select(BrowseSelection.Movies(list)); advanceUntilIdle()
            assertTrue(f.api.calls.contains("movie/$list/en-US/1"))
            assertEquals(MediaType.Movie, (f.page().items.first() as Hero.OfTitle).title.mediaType)
        }
        for (list in TvList.entries) {
            f.holder.select(BrowseSelection.Tv(list)); advanceUntilIdle()
            assertTrue(f.api.calls.contains("tv/$list/en-US/1"))
            assertEquals(MediaType.Tv, (f.page().items.first() as Hero.OfTitle).title.mediaType)
        }
        for (window in TrendingWindow.entries) {
            f.holder.select(BrowseSelection.fromHomeRow(HomeRow.Trending, window)); advanceUntilIdle()
            assertTrue(f.api.calls.contains("trending/$window/en-US/1"))
        }
        f.holder.select(BrowseSelection.People); advanceUntilIdle()
        assertIs<Hero.OfPerson>(f.page().items.first())
        assertTrue(f.api.calls.contains("people/en-US/1"))
        val before = f.api.calls.size
        f.holder.select(BrowseSelection.Movies(MovieList.Popular)); advanceUntilIdle()
        assertEquals(before, f.api.calls.size) // shared catalog cache, not a new repository per selection
        assertTrue(f.discoverCalls.isEmpty())
    }

    @Test fun genericDiscoverNeverKeepsCuratedProvenanceOrInventsParameters() = runTest {
        val f = Fixture(BrowseSelection.Movies(MovieList.Upcoming))
        advanceUntilIdle()
        val discover = BrowseSelection.Discover(MediaType.Movie, listOf(28, 18, 28), DiscoverSort.ReleaseDate)
        f.holder.select(discover); advanceUntilIdle()
        assertEquals(discover, f.holder.uiState.value.selection)
        assertEquals(listOf("movie/[18, 28]/ReleaseDate/en-US/IN/1"), f.discoverCalls)
        val before = f.subscriptions
        f.holder.select(BrowseSelection.Discover(MediaType.Movie, listOf(18, 28), DiscoverSort.ReleaseDate))
        advanceUntilIdle()
        assertEquals(before, f.subscriptions)
        f.holder.select(BrowseSelection.Discover(MediaType.Tv, sort = DiscoverSort.Title)); advanceUntilIdle()
        assertEquals("tv/[]/Title/en-US/null/1", f.discoverCalls.last())
        assertEquals(MediaType.Tv, (f.page().items.first() as Hero.OfTitle).title.mediaType)
    }

    @Test fun selectionCancelsAndDropsLateNonCancellableReadsAndDiagnostics() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.readHook = { withContext(NonCancellable) { gate.await(); throw f.failure } }
        runCurrent()
        assertIs<SectionState.Loading>(f.holder.uiState.value.grid)
        f.readHook = null
        f.holder.select(BrowseSelection.Tv(TvList.OnTheAir))
        runCurrent()
        val before = f.holder.uiState.value
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(before, f.holder.uiState.value)
        assertTrue(f.firstTitle().startsWith("on_the_air@"))
        assertTrue(f.reports.isEmpty())
    }

    @Test fun selectionCancelsOldSettingsObservationAndItsLateFailure() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.observeHook = { withContext(NonCancellable) { gate.await(); throw f.failure } }
        runCurrent()
        f.observeHook = null
        f.holder.select(BrowseSelection.People)
        runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(2, f.subscriptions)
        assertTrue(f.holder.uiState.value.settingsKnown)
        assertNull(f.holder.uiState.value.settingsError)
        assertTrue(f.reports.isEmpty())
    }

    @Test fun selectionDropsLateSuccessfulPageAndGenreResults() = runTest {
        val f = Fixture()
        val pageGate = f.api.gate("movie/popular/en-US/1")
        val genreGate = f.api.gate("genres/movie/en-US")
        runCurrent()
        f.holder.select(BrowseSelection.Tv(TvList.AiringToday)); runCurrent()
        pageGate.complete(Unit); genreGate.complete(Unit); advanceUntilIdle()
        assertTrue(f.firstTitle().startsWith("airing_today@"))
        assertEquals(mapOf(18 to "tv@en-US"), f.holder.uiState.value.genres)
    }

    @Test fun appGenerationDropsLateGenreFailureAndReloadsCurrentNames() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        var first = true
        f.genreHook = {
            if (first) { first = false; gate.await(); throw f.failure }
        }
        runCurrent()
        f.preferences = Settings(language = "es-ES")
        f.catalog.onContentSettingsChanged()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(mapOf(28 to "movie@es-ES"), f.holder.uiState.value.genres)
        assertNull(f.holder.uiState.value.genresError)
        assertTrue(f.reports.isEmpty())
    }

    @Test fun appGenerationDropsStalePageAndGenresWithoutReportingOldFailure() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        var first = true
        f.readHook = {
            if (first) { first = false; gate.await(); throw f.failure }
        }
        runCurrent()
        f.preferences = Settings(language = "es-ES")
        f.catalog.onContentSettingsChanged()
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(f.firstTitle().contains("es-ES"))
        assertTrue(f.reports.isEmpty())
    }

    @Test fun paginationRetainsContentAndRetriesTheCorrectNetworkFailedPageOnlyExplicitly() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        val before = f.page()
        f.pageError = TmdbException(TmdbError.Network)
        f.holder.loadMore(); advanceUntilIdle()
        assertEquals(before.copy(moreError = SectionErrorKind.Offline), f.page())
        f.pageError = null
        repeat(4) { f.holder.loadMore() }; advanceUntilIdle()
        assertEquals(before.copy(moreError = SectionErrorKind.Offline), f.page())
        f.holder.retry(); f.holder.loadMore(); advanceUntilIdle()
        assertEquals(2, f.page().page)
        assertEquals(6, f.page().items.size)
        assertEquals(listOf("movie/popular/en-US/1", "movie/popular/en-US/2"), f.api.calls.filter { it.startsWith("movie/") })
        assertNull(f.page().moreError)
    }

    @Test fun emptyAndInitialNetworkFailureAreDistinctAndExplicitlyRetryable() = runTest {
        val f = Fixture()
        f.failPage = 1; f.pageError = TmdbException(TmdbError.Network)
        runCurrent()
        assertEquals(SectionState.Error(SectionErrorKind.Offline), f.holder.uiState.value.grid)
        f.pageError = null; f.empty = true
        f.holder.retry(); advanceUntilIdle()
        assertEquals(SectionState.Empty, f.holder.uiState.value.grid)
        f.empty = false; f.catalog.onContentSettingsChanged()
        f.holder.retry(); advanceUntilIdle()
        assertEquals(1, f.page().page)
    }

    @Test fun unknownStorageIsExplicitAndRetryResubscribesWithoutInventingSettings() = runTest {
        val f = Fixture()
        f.readError = f.failure; f.observeError = f.failure
        advanceUntilIdle()
        assertEquals(SectionState.Error(SectionErrorKind.Generic), f.holder.uiState.value.grid)
        assertFalse(f.holder.uiState.value.settingsKnown)
        assertEquals(SectionErrorKind.Generic, f.holder.uiState.value.settingsError)
        assertEquals(SectionErrorKind.Generic, f.holder.uiState.value.genresError)
        assertTrue(f.holder.uiState.value.genres.isEmpty())
        val count = f.reports.size
        advanceUntilIdle()
        assertEquals(count, f.reports.size)
        f.readError = null; f.observeError = null
        f.holder.retry(); advanceUntilIdle()
        assertEquals(2, f.subscriptions)
        assertTrue(f.holder.uiState.value.settingsKnown)
        assertNull(f.holder.uiState.value.settingsError)
        assertNull(f.holder.uiState.value.genresError)
        assertEquals(1, f.page().page)
    }

    @Test fun equalSettingsRecoveryRetainsPagesAndRetriesStorageFailedNextPage() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        val before = f.page()
        val genres = f.holder.uiState.value.genres
        f.readError = f.failure; f.observeError = f.failure; f.signal()
        f.holder.loadMore(); advanceUntilIdle()
        assertEquals(before.copy(moreError = SectionErrorKind.Generic), f.page())
        assertTrue(f.holder.uiState.value.settingsKnown)
        assertEquals(genres, f.holder.uiState.value.genres)
        f.holder.retryStorage(); advanceUntilIdle()
        assertEquals(before.copy(moreError = SectionErrorKind.Generic), f.page())
        f.readError = null; f.observeError = null
        f.holder.retryStorage(); advanceUntilIdle()
        assertEquals(3, f.subscriptions)
        assertEquals(2, f.page().page)
        assertEquals(6, f.page().items.size)
        assertNull(f.page().moreError)
        f.holder.retryStorage(); advanceUntilIdle()
        assertEquals(2, f.page().page) // equal values must not reset valid pagination
        assertEquals(genres, f.holder.uiState.value.genres)
    }

    @Test fun changedSettingsRecoveryResetsOldPagesAndGenresAndKeepsSelection() = runTest {
        val f = Fixture(BrowseSelection.Movies(MovieList.NowPlaying))
        advanceUntilIdle()
        f.holder.loadMore(); advanceUntilIdle()
        f.observeError = f.failure; f.signal(); advanceUntilIdle()
        f.preferences = Settings(language = "es-ES", region = "US", includeAdult = true)
        f.observeError = null
        f.catalog.onContentSettingsChanged()
        f.holder.retryStorage(); advanceUntilIdle()
        assertEquals(BrowseSelection.Movies(MovieList.NowPlaying), f.holder.uiState.value.selection)
        assertEquals(1, f.page().page)
        assertTrue(f.firstTitle().contains("es-ES"))
        assertEquals(mapOf(28 to "movie@es-ES"), f.holder.uiState.value.genres)
        assertNull(f.holder.uiState.value.settingsError)
        f.preferences = f.preferences.copy(language = "hi-IN"); f.catalog.onContentSettingsChanged(); f.signal()
        advanceUntilIdle()
        assertTrue(f.firstTitle().contains("hi-IN")) // recovered observer remains live
    }

    @Test fun settingsTupleChangeWithoutGenerationStillCancelsPendingOldPagesAndGenres() = runTest {
        val f = Fixture()
        val pageGate = f.api.gate("movie/popular/en-US/1")
        val genreGate = f.api.gate("genres/movie/en-US")
        runCurrent()
        f.preferences = Settings(language = "es-ES"); f.signal(); runCurrent()
        pageGate.complete(Unit); genreGate.complete(Unit); advanceUntilIdle()
        assertTrue(f.firstTitle().contains("es-ES"))
        assertEquals(mapOf(28 to "movie@es-ES"), f.holder.uiState.value.genres)
    }

    @Test fun changedSettingsDuringOutageAreDetectedEvenWithoutAnAppBump() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.holder.loadMore(); advanceUntilIdle()
        f.observeError = f.failure; f.signal(); advanceUntilIdle()
        f.preferences = Settings(language = "es-ES")
        f.observeError = null
        f.holder.retryStorage(); advanceUntilIdle()
        assertEquals(1, f.page().page)
        assertTrue(f.firstTitle().contains("es-ES"))
        assertEquals(mapOf(28 to "movie@es-ES"), f.holder.uiState.value.genres)
    }

    @Test fun genreNetworkAndStorageErrorsAreVisibleRetainValidNamesAndRetryWithoutReloadingGrid() = runTest {
        val f = Fixture()
        f.genreError = TmdbException(TmdbError.Network)
        advanceUntilIdle()
        assertEquals(SectionErrorKind.Offline, f.holder.uiState.value.genresError)
        val before = f.page()
        f.genreError = null
        f.holder.retryGenres(); advanceUntilIdle()
        val genres = f.holder.uiState.value.genres
        assertEquals(mapOf(28 to "movie@en-US"), genres)
        f.readError = f.failure
        f.holder.retryGenres(); advanceUntilIdle()
        assertEquals(SectionErrorKind.Generic, f.holder.uiState.value.genresError)
        assertEquals(genres, f.holder.uiState.value.genres)
        assertEquals(listOf(f.failure), f.reports)
        f.readError = null
        f.holder.retryStorage(); advanceUntilIdle()
        assertNull(f.holder.uiState.value.genresError)
        assertEquals(before, f.page())
    }

    @Test fun peopleHasNoGenreReadsAndDropsLateGenreResultsAndErrors() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.genreHook = { withContext(NonCancellable) { gate.await(); throw f.failure } }
        runCurrent()
        f.holder.select(BrowseSelection.People); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        f.holder.retryGenres(); advanceUntilIdle()
        assertTrue(f.holder.uiState.value.genres.isEmpty())
        assertFalse(f.holder.uiState.value.genresLoading)
        assertNull(f.holder.uiState.value.genresError)
        assertTrue(f.reports.isEmpty())
        assertTrue(f.api.calls.none { it.startsWith("genres/") })
    }

    @Test fun savedSelectionWinsOverInitialAndRestoresWithoutSavingRepositoryObjects() = runTest {
        val saver = SavedStateHandleStateSaver(SavedStateHandle())
        val f = Fixture(saver = saver)
        val selection = BrowseSelection.Discover(MediaType.Tv, listOf(18, 28), DiscoverSort.Rating)
        f.holder.select(selection); advanceUntilIdle()
        val restored = Fixture(BrowseSelection.People, saver)
        advanceUntilIdle()
        assertEquals(selection, restored.holder.uiState.value.selection)
        assertEquals(listOf("tv/[18, 28]/Rating/en-US/null/1"), restored.discoverCalls)
    }

    @Test fun disposedOwnerDropsLateFailuresAndProgrammerErrorsAndCancellationAreNotRecovery() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.readHook = { withContext(NonCancellable) { gate.await(); throw f.failure } }
        runCurrent(); f.owner.cancel(); gate.complete(Unit); advanceUntilIdle()
        assertTrue(f.reports.isEmpty())
        val bug = IllegalStateException("fixture bug")
        val g = Fixture(); g.observeError = bug
        advanceUntilIdle()
        assertIs<IllegalStateException>(g.errors.single())
        assertEquals(bug.message, g.errors.single().message)
        assertNull(g.holder.uiState.value.settingsError)
        val h = Fixture(); h.readError = CancellationException("fixture cancellation")
        advanceUntilIdle()
        assertTrue(h.errors.isEmpty())
        assertTrue(h.reports.isEmpty())
        assertIs<SectionState.Loading>(h.holder.uiState.value.grid)
    }
}
