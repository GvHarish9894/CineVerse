package com.techgv.cineverse.presentation

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.search.RECENT_QUERIES_KEY
import com.techgv.cineverse.data.search.RecentQueryStore
import com.techgv.cineverse.data.settings.*
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProductSearchStateHolderTest {
    private val owners = mutableListOf<CoroutineScope>()
    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun tearDown() { owners.forEach { it.cancel() }; Dispatchers.resetMain() }

    private inner class Fixture(
        query: String? = null,
        facet: String? = null,
        val saver: StateSaver = SavedStateHandleStateSaver(SavedStateHandle().apply {
            if (query != null) set("productSearch.query", query)
            if (facet != null) set("productSearch.facet", facet)
        }),
    ) {
        val errors = mutableListOf<Throwable>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> errors += e }).also { owners += it }
        val app = GenerationCounter()
        val failure = StorageException("fixture")
        val reports = mutableListOf<StorageException>()
        var preferences = Settings()
        var settingsError: Exception? = null
        var settingsHook: (suspend () -> Unit)? = null
        var catalogReadError: Exception? = null
        var pageHook: (suspend (String) -> Unit)? = null
        var pageError: Exception? = null
        var failedPage = 2
        var empty = false
        var subscriptions = 0
        var activeSettings = 0
        val ticks = MutableStateFlow(0)
        val settings = flow {
            subscriptions++
            activeSettings++
            try {
                ticks.collect {
                    settingsHook?.invoke()
                    settingsError?.let { throw it }
                    emit(preferences)
                }
            } finally { activeSettings-- }
        }
        val calls = mutableListOf<String>()
        val api = object : UnusedTmdbApi() {
            private suspend fun page(key: String, page: Int, media: String): PageDto<MediaItemDto> {
                calls += key
                pageHook?.invoke(key)
                if (page == failedPage) pageError?.let { throw it }
                val items = if (empty) emptyList() else listOf(
                    MediaItemDto(1, mediaType = media, title = key, name = key),
                    MediaItemDto(page + 1, mediaType = media, title = key, name = key),
                )
                return PageDto(page, items, totalPages = 3) // absent totalResults: deliberately ambiguous zero
            }
            override suspend fun trending(window: TrendingWindow, language: String, page: Int) = page("trending/$window/$language/$page", page, "movie")
            override suspend fun searchMulti(query: String, language: String, includeAdult: Boolean, page: Int) =
                search(SearchFacet.Multi, query, language, includeAdult, page)
            override suspend fun search(facet: SearchFacet, query: String, language: String, includeAdult: Boolean, page: Int) =
                page("${facet.path}/$query/$language/$includeAdult/$page", page, if (facet == SearchFacet.Multi) "movie" else facet.path)
        }
        val catalog = CatalogRepository(api, app, settings = { catalogReadError?.let { throw it }; preferences })
        val disk = InMemorySettingsStore()
        var recentReadHook: (suspend () -> Unit)? = null
        var recentReadError: Exception? = null
        var writeHook: (suspend () -> Unit)? = null
        var writeError: Exception? = null
        var edits = 0
        var reads = 0
        var activeRecent = 0
        val storage = object : SettingsStore {
            override val data = flow {
                reads++
                activeRecent++
                try {
                    disk.data.collect { value ->
                        recentReadHook?.invoke()
                        recentReadError?.let { throw it }
                        emit(value)
                    }
                } finally { activeRecent-- }
            }
            override suspend fun edit(transform: (MutableSettingsValues) -> Unit) {
                edits++
                writeHook?.invoke()
                writeError?.let { throw it }
                disk.edit(transform)
            }
        }
        val holder = ProductSearchStateHolder(catalog, app, settings, RecentQueryStore(storage), saver, owner, reports::add)
        fun signal() { ticks.value++ }
        fun state() = holder.uiState.value
        fun page() = (state().results as SectionState.Content).value
        fun title() = (page().items.first() as? Hero.OfTitle)?.title?.title ?: (page().items.first() as Hero.OfPerson).person.name
    }

    @Test fun initialRestoreIsImmediateButWaitsForRealSettingsAndRecentStartsUnknown() = runTest {
        val f = Fixture("  restored  ", "Tv")
        val gate = CompletableDeferred<Unit>()
        f.settingsHook = { gate.await() }
        f.recentReadHook = { gate.await() }
        assertFalse(f.state().settingsKnown)
        assertNull(f.state().recentQueries)
        runCurrent()
        assertTrue(f.calls.isEmpty())
        assertNull(f.state().results)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("tv/restored/en-US/false/1"), f.calls)
        assertEquals(emptyList(), f.state().recentQueries)
        assertEquals("  restored  ", f.state().query)
        assertEquals(0, f.edits)
    }

    @Test fun changedQueryDebouncesExactly300msWhileWhitespaceOnlyEditsKeepWorkAndRawValue() = runTest {
        val f = Fixture(); runCurrent()
        f.holder.setQuery("a"); advanceTimeBy(150); f.holder.setQuery("arrival")
        advanceTimeBy(299); runCurrent()
        assertEquals(1, f.calls.size)
        assertEquals(SectionState.Loading, f.state().results)
        f.holder.setQuery("  arrival \n")
        advanceTimeBy(1); runCurrent()
        assertEquals("multi/arrival/en-US/false/1", f.calls.last())
        assertEquals(2, f.calls.size)
        val before = f.page()
        f.holder.setQuery("arrival "); runCurrent()
        assertEquals(before, f.page())
        assertEquals("arrival ", f.saver.getString("productSearch.query"))
        assertEquals(0, f.edits)
    }

    @Test fun allFourFacetsUseRealEndpointsAndAtomicInputHasNoIntermediateRequest() = runTest {
        val f = Fixture("alien"); runCurrent()
        for (facet in SearchFacet.entries) {
            f.holder.selectFacet(facet); runCurrent()
            assertEquals("${facet.path}/alien/en-US/false/1", f.calls.last())
            assertEquals(2, f.state().loadedCount(facet))
            SearchFacet.entries.filterNot { it == facet }.forEach { assertNull(f.state().loadedCount(it)) }
            if (facet == SearchFacet.Person) assertIs<Hero.OfPerson>(f.page().items.first())
        }
        val before = f.calls.size
        f.holder.setInput("new", SearchFacet.Movie); advanceUntilIdle()
        assertEquals(listOf("movie/new/en-US/false/1"), f.calls.drop(before))
        assertEquals(0, f.edits)
    }

    @Test fun blankIsRealTrendingTitlesAndLongValidInputIsNeverTVTruncated() = runTest {
        val f = Fixture(); runCurrent()
        assertTrue(f.state().showingSuggestions)
        assertEquals(listOf("trending/week/en-US/1"), f.calls)
        assertIs<Hero.OfTitle>(f.page().items.first())
        assertNull(f.state().loadedCount(SearchFacet.Multi))
        val long = "x".repeat(200)
        f.holder.setQuery(" $long "); advanceUntilIdle()
        assertNull(f.state().inputError)
        assertEquals("multi/$long/en-US/false/1", f.calls.last())
        f.holder.setQuery(" \n\t"); runCurrent()
        assertTrue(f.state().showingSuggestions)
        assertTrue(f.title().startsWith("trending/"))
    }

    @Test fun invalidInputCancelsLateWorkAndHasNoSourceNetworkOrInventedSuccess() = runTest {
        val f = Fixture("old")
        val gate = CompletableDeferred<Unit>()
        f.pageHook = { withContext(NonCancellable) { gate.await(); throw f.failure } }
        runCurrent()
        f.holder.setQuery("x".repeat(201)); runCurrent()
        assertEquals(SearchInputError.TooLong, f.state().inputError)
        assertNull(f.state().results)
        f.holder.retry(); f.holder.loadMore(); f.holder.commitQuery(); advanceUntilIdle()
        gate.complete(Unit); advanceUntilIdle()
        assertNull(f.state().results)
        assertNull(f.state().loadedCount(SearchFacet.Multi))
        assertEquals(1, f.calls.size)
        assertEquals(0, f.edits)
        assertTrue(f.reports.isEmpty())
    }

    @Test fun overBudgetRestoreIsAnExplicitEditableError() = runTest {
        val f = Fixture("x".repeat(201)); advanceUntilIdle()
        assertEquals(201, f.state().query.length)
        assertEquals(SearchInputError.TooLong, f.state().inputError)
        assertNull(f.state().results)
        assertTrue(f.calls.isEmpty())
        f.holder.setQuery("valid"); advanceUntilIdle()
        assertEquals("multi/valid/en-US/false/1", f.calls.single())
    }

    @Test fun canceledQueryAndFacetDropLateSuccessErrorsCountsAndDiagnostics() = runTest {
        for (fail in listOf(false, true)) {
            val f = Fixture("old")
            val gate = CompletableDeferred<Unit>()
            f.pageHook = { withContext(NonCancellable) { gate.await(); if (fail) throw f.failure } }
            runCurrent()
            f.pageHook = null
            f.holder.setInput("new", SearchFacet.Person); advanceUntilIdle()
            val before = f.state()
            gate.complete(Unit); advanceUntilIdle()
            assertEquals(before, f.state())
            assertEquals("person/new/en-US/false/1", f.title())
            assertTrue(f.reports.isEmpty())
        }
    }

    @Test fun facetAloneCancelsPendingOldFacetAndDoesNotDebounce() = runTest {
        val f = Fixture("same")
        val gate = CompletableDeferred<Unit>()
        f.pageHook = { withContext(NonCancellable) { gate.await() } }; runCurrent()
        f.pageHook = null; f.holder.selectFacet(SearchFacet.Tv); runCurrent()
        assertEquals("tv/same/en-US/false/1", f.title())
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("tv/same/en-US/false/1", f.title())
    }

    @Test fun nextPageFailureRetainsDeduplicatedContentAndViewportNeverLoopsRetry() = runTest {
        val f = Fixture("query"); advanceUntilIdle()
        val first = f.page()
        f.pageError = TmdbException(TmdbError.Network)
        f.holder.loadMore(); advanceUntilIdle()
        assertEquals(first.copy(moreError = SectionErrorKind.Offline), f.page())
        repeat(5) { f.holder.loadMore() }; advanceUntilIdle()
        assertEquals(2, f.calls.size)
        f.pageError = null; f.holder.retry(); advanceUntilIdle()
        assertEquals(2, f.page().page)
        assertEquals(3, f.page().items.size)
        assertEquals(listOf(1, 2, 3), f.page().items.map { (it as Hero.OfTitle).title.ref.id })
        assertEquals(listOf("1", "2", "2"), f.calls.map { it.substringAfterLast('/') })
        assertEquals(3, f.state().loadedCount(SearchFacet.Multi))
        assertEquals(0, f.edits)
    }

    @Test fun initialErrorEmptyAndContentAreDifferentAndExplicitlyRetryable() = runTest {
        val f = Fixture("q"); f.failedPage = 1; f.pageError = TmdbException(TmdbError.Network)
        advanceUntilIdle()
        assertEquals(SectionState.Error(SectionErrorKind.Offline), f.state().results)
        f.pageError = null; f.empty = true; f.holder.retry(); advanceUntilIdle()
        assertEquals(SectionState.Empty, f.state().results)
        assertEquals(0, f.state().loadedCount(SearchFacet.Multi))
        f.empty = false; f.catalog.onContentSettingsChanged(); f.holder.retry(); advanceUntilIdle()
        assertEquals(2, f.page().items.size)
    }

    @Test fun firstSettingsFailureStaysUnknownAndFirstRecoveryFetchesActualSettingsImmediately() = runTest {
        val f = Fixture("q"); f.settingsError = f.failure; advanceUntilIdle()
        assertFalse(f.state().settingsKnown)
        assertEquals(SectionErrorKind.Generic, f.state().settingsError)
        assertNull(f.state().results)
        assertTrue(f.calls.isEmpty())
        f.preferences = Settings(language = "es-ES", includeAdult = true)
        f.settingsError = null; f.holder.retrySettings(); runCurrent()
        assertEquals("multi/q/es-ES/true/1", f.title())
        assertTrue(f.state().settingsKnown)
        assertNull(f.state().settingsError)
        assertEquals(0, f.edits)
    }

    @Test fun equalSettingsRecoveryPreservesSameSectionPagesAndFailedNextPageMarker() = runTest {
        val f = Fixture("q"); advanceUntilIdle()
        f.catalogReadError = f.failure
        f.holder.loadMore(); advanceUntilIdle()
        val before = f.page()
        f.settingsError = f.failure; f.signal(); advanceUntilIdle()
        assertTrue(f.state().settingsKnown)
        assertEquals(before, f.page())
        f.catalogReadError = null; f.settingsError = null
        f.holder.retrySettings(); advanceUntilIdle()
        assertEquals(2, f.page().page)
        assertEquals(3, f.page().items.size)
        val calls = f.calls.toList()
        f.holder.retrySettings(); advanceUntilIdle()
        assertEquals(calls, f.calls)
        assertEquals(2, f.page().page)
        assertEquals(0, f.edits)
    }

    @Test fun changedSettingsRecoveryDropsOldLanguageAdultPagesEvenWithoutGenerationBump() = runTest {
        val f = Fixture("q"); advanceUntilIdle(); f.holder.loadMore(); advanceUntilIdle()
        f.settingsError = f.failure; f.signal(); advanceUntilIdle()
        f.preferences = Settings(language = "es-ES", region = "US", includeAdult = true)
        f.settingsError = null; f.holder.retrySettings(); runCurrent()
        assertEquals(1, f.page().page)
        assertEquals("multi/q/es-ES/true/1", f.title())
        f.holder.loadMore(); advanceUntilIdle()
        assertTrue(f.page().items.all { (it as Hero.OfTitle).title.title.contains("es-ES/true") })
    }

    @Test fun observedSettingsChangeCancelsLateOldResponsesAndErrors() = runTest {
        for (fail in listOf(false, true)) {
            val f = Fixture("q"); val gate = CompletableDeferred<Unit>()
            f.pageHook = { withContext(NonCancellable) { gate.await(); if (fail) throw f.failure } }; runCurrent()
            f.pageHook = null; f.preferences = Settings(language = "hi-IN", includeAdult = true); f.signal(); runCurrent()
            assertEquals("multi/q/hi-IN/true/1", f.title())
            gate.complete(Unit); advanceUntilIdle()
            assertEquals("multi/q/hi-IN/true/1", f.title())
            assertTrue(f.reports.isEmpty())
        }
    }

    @Test fun appGenerationLateFailureReloadsCurrentSourceWithoutOldDiagnostics() = runTest {
        val f = Fixture("q"); val gate = CompletableDeferred<Unit>()
        f.pageHook = { gate.await(); throw f.failure }; runCurrent()
        f.pageHook = null; f.preferences = Settings(language = "es-ES"); f.catalog.onContentSettingsChanged()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("multi/q/es-ES/false/1", f.title())
        assertTrue(f.reports.isEmpty())
    }

    @Test fun staleSettingsReaderFailureResubscribesAndRemainsLive() = runTest {
        val f = Fixture("q"); val gate = CompletableDeferred<Unit>(); var first = true
        f.settingsHook = { if (first) { first = false; gate.await(); throw f.failure } }; runCurrent()
        f.preferences = Settings(language = "es-ES"); f.app.bump(); gate.complete(Unit); advanceUntilIdle()
        assertEquals(2, f.subscriptions)
        assertEquals("multi/q/es-ES/false/1", f.title())
        assertTrue(f.reports.isEmpty())
        f.preferences = Settings(language = "hi-IN"); f.signal(); advanceUntilIdle()
        assertEquals("multi/q/hi-IN/false/1", f.title())
        assertEquals(2, f.subscriptions)
    }

    @Test fun staleSettingsRecoveryStopsOnCurrentFailureRatherThanLooping() = runTest {
        val f = Fixture("q"); val gate = CompletableDeferred<Unit>(); var first = true
        f.settingsHook = { if (first) { first = false; gate.await() } }; f.settingsError = f.failure
        runCurrent(); f.app.bump(); gate.complete(Unit); advanceUntilIdle()
        assertEquals(2, f.subscriptions)
        assertFalse(f.state().settingsKnown)
        assertEquals(SectionErrorKind.Generic, f.state().settingsError)
        assertEquals(listOf(f.failure), f.reports)
        f.signal(); advanceUntilIdle(); assertEquals(2, f.subscriptions)
    }

    @Test fun queryReplacementDoesNotWaitForNonCancellableOldSettingsReader() = runTest {
        for (fail in listOf(false, true)) {
            val f = Fixture("old"); val gate = CompletableDeferred<Unit>()
            f.settingsHook = { withContext(NonCancellable) { gate.await(); if (fail) throw f.failure } }; runCurrent()
            f.settingsHook = null; f.holder.setQuery("new"); advanceUntilIdle()
            assertEquals("multi/new/en-US/false/1", f.title())
            gate.complete(Unit); advanceUntilIdle()
            assertNull(f.state().settingsError)
            assertTrue(f.reports.isEmpty())
        }
    }

    @Test fun recentReadFailureRetainsLastRealValuesAndExplicitRetryNeverWrites() = runTest {
        val f = Fixture(); f.disk.edit { it.setString(RECENT_QUERIES_KEY, "[\"saved\"]") }; advanceUntilIdle()
        assertEquals(listOf("saved"), f.state().recentQueries)
        f.recentReadError = f.failure
        f.disk.edit { it.setBoolean("unrelated", true) }; advanceUntilIdle()
        assertEquals(listOf("saved"), f.state().recentQueries)
        assertEquals(SectionErrorKind.Generic, f.state().recentReadError)
        f.recentReadError = null; f.holder.retryRecentRead(); advanceUntilIdle()
        assertNull(f.state().recentReadError)
        assertEquals(listOf("saved"), f.state().recentQueries)
        assertEquals(0, f.edits)
    }

    @Test fun failedInitialRecentReadIsUnknownNotEmpty() = runTest {
        val f = Fixture(); f.recentReadError = f.failure; advanceUntilIdle()
        assertNull(f.state().recentQueries)
        assertEquals(SectionErrorKind.Generic, f.state().recentReadError)
        f.recentReadError = null; f.holder.retryRecentRead(); advanceUntilIdle()
        assertEquals(emptyList(), f.state().recentQueries)
        assertEquals(0, f.edits)
    }

    @Test fun typingFacetStartupAndAllReadRetriesNeverMutateRecentButExplicitRepeatedCommitDoes() = runTest {
        val f = Fixture(); advanceUntilIdle()
        f.holder.setQuery("  arrival  "); advanceUntilIdle(); f.holder.selectFacet(SearchFacet.Movie); advanceUntilIdle()
        f.holder.retry(); f.holder.retrySettings(); f.holder.retryRecentRead(); advanceUntilIdle()
        assertEquals(0, f.edits)
        f.holder.commitQuery(); advanceUntilIdle()
        assertEquals(listOf("arrival"), f.state().recentQueries)
        assertEquals(RecentMutationStatus.Succeeded, f.state().recentMutation)
        f.holder.commitQuery(); advanceUntilIdle(); assertEquals(2, f.edits)
        f.holder.clearRecentQueries(); advanceUntilIdle()
        assertEquals(emptyList(), f.state().recentQueries)
        assertEquals(3, f.edits)
        f.holder.setQuery(" "); f.holder.commitQuery(); advanceUntilIdle(); assertEquals(3, f.edits)
    }

    @Test fun mutationFailureIsHonestAndReadRecoveryNeverReplaysRecordOrClear() = runTest {
        for (clear in listOf(false, true)) {
            val f = Fixture("q"); advanceUntilIdle()
            f.writeError = f.failure
            if (clear) f.holder.clearRecentQueries() else f.holder.commitQuery()
            advanceUntilIdle()
            assertEquals(RecentMutationStatus.Failed, f.state().recentMutation)
            assertEquals(emptyList(), f.state().recentQueries)
            assertEquals(listOf(f.failure), f.reports)
            f.writeError = null; f.holder.retryRecentRead(); f.holder.retrySettings(); f.holder.retry(); advanceUntilIdle()
            assertEquals(1, f.edits)
            assertEquals(RecentMutationStatus.Failed, f.state().recentMutation)
            assertEquals(emptyList(), f.state().recentQueries)
        }
    }

    @Test fun appGenerationChangeDuringFailedWriteReconcilesPendingWithoutStaleFeedback() = runTest {
        val f = Fixture("q"); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        f.writeHook = { gate.await(); throw f.failure }
        val reads = f.reads
        val subscriptions = f.subscriptions
        f.holder.commitQuery(); runCurrent()
        assertEquals(RecentMutationStatus.Pending, f.state().recentMutation)
        f.app.bump()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(RecentMutationStatus.Idle, f.state().recentMutation)
        assertTrue(f.reports.isEmpty())
        assertNull(f.state().recentReadError)
        assertEquals(emptyList(), f.state().recentQueries)
        assertEquals(reads + 1, f.reads)
        assertEquals(subscriptions + 1, f.subscriptions)
        assertEquals(1, f.activeSettings)
        assertEquals(1, f.activeRecent)
        assertEquals(1, f.edits)
        f.holder.retryRecentRead(); advanceUntilIdle()
        assertEquals(1, f.edits)
    }

    @Test fun obsoleteSuccessfulClearRecoversCurrentCorruptReaderWithoutPublishingWriteFeedback() = runTest {
        val f = Fixture("old")
        f.disk.edit { it.setString(RECENT_QUERIES_KEY, "corrupt-private"); it.setBoolean("unrelated", true) }
        advanceUntilIdle()
        assertNull(f.state().recentQueries)
        assertEquals(SectionErrorKind.Generic, f.state().recentReadError)
        assertEquals(0, f.activeRecent)
        val writeGate = CompletableDeferred<Unit>()
        f.writeHook = { writeGate.await() }
        f.holder.clearRecentQueries(); runCurrent()
        f.holder.setInput("new", SearchFacet.Movie); advanceUntilIdle()
        assertEquals("movie/new/en-US/false/1", f.title())
        assertEquals(RecentMutationStatus.Idle, f.state().recentMutation)
        assertEquals(SectionErrorKind.Generic, f.state().recentReadError)
        assertEquals(0, f.activeRecent)
        val reads = f.reads
        val reports = f.reports.toList()
        val readGate = CompletableDeferred<Unit>()
        f.recentReadHook = { readGate.await() }
        writeGate.complete(Unit); runCurrent()
        assertNull(f.disk.data.first().string(RECENT_QUERIES_KEY))
        assertEquals(true, f.disk.data.first().boolean("unrelated"))
        assertEquals(reads + 1, f.reads)
        assertEquals(1, f.activeRecent)
        assertNull(f.state().recentQueries) // A successful clear is not an observed empty history.
        assertEquals(SectionErrorKind.Generic, f.state().recentReadError)
        assertEquals(RecentMutationStatus.Idle, f.state().recentMutation)
        readGate.complete(Unit); advanceUntilIdle()
        assertEquals(emptyList(), f.state().recentQueries)
        assertNull(f.state().recentReadError)
        assertEquals(RecentMutationStatus.Idle, f.state().recentMutation)
        assertEquals(reports, f.reports)
        assertEquals(1, f.activeSettings)
        assertEquals(1, f.activeRecent)
        assertEquals(reads + 1, f.reads)
        assertEquals(1, f.edits)
    }

    @Test fun recentReadRetryAfterAppGenerationChangeInvalidatesOldSourceAndCounts() = runTest {
        val f = Fixture("q"); advanceUntilIdle()
        f.holder.loadMore(); advanceUntilIdle()
        assertEquals(2, f.page().page)
        assertEquals(3, f.state().loadedCount(SearchFacet.Multi))
        val reads = f.reads
        val subscriptions = f.subscriptions
        f.app.bump()
        f.holder.retryRecentRead()
        assertEquals(SectionState.Loading, f.state().results)
        assertNull(f.state().loadedCount(SearchFacet.Multi))
        advanceUntilIdle()
        assertEquals(1, f.page().page)
        assertEquals(2, f.state().loadedCount(SearchFacet.Multi))
        assertEquals(reads + 1, f.reads)
        assertEquals(subscriptions + 1, f.subscriptions)
        assertEquals(1, f.activeSettings)
        assertEquals(1, f.activeRecent)
        assertEquals(0, f.edits)
        assertTrue(f.reports.isEmpty())

        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val immediate = Fixture("q"); immediate.holder.loadMore()
        assertEquals(2, immediate.page().page)
        val immediateReads = immediate.reads
        val immediateSubscriptions = immediate.subscriptions
        immediate.preferences = Settings(language = "es-ES")
        immediate.app.bump()
        immediate.holder.retryRecentRead()
        assertEquals("multi/q/es-ES/false/1", immediate.title())
        assertEquals(1, immediate.page().page)
        assertEquals(immediateReads + 1, immediate.reads)
        assertEquals(immediateSubscriptions + 1, immediate.subscriptions)
        assertEquals(1, immediate.activeSettings)
        assertEquals(1, immediate.activeRecent)
        assertEquals(0, immediate.edits)
        assertTrue(immediate.reports.isEmpty())
    }

    @Test fun corruptRecentIsNotResetByReadRecoveryOrRecordAndOnlyExplicitClearRecovers() = runTest {
        val f = Fixture("q")
        f.disk.edit { it.setString(RECENT_QUERIES_KEY, "corrupt-private"); it.setBoolean("unrelated", true) }; advanceUntilIdle()
        assertNull(f.state().recentQueries)
        assertEquals(SectionErrorKind.Generic, f.state().recentReadError)
        f.holder.retryRecentRead(); f.holder.commitQuery(); advanceUntilIdle()
        assertEquals("corrupt-private", f.disk.data.first().string(RECENT_QUERIES_KEY))
        assertEquals(RecentMutationStatus.Failed, f.state().recentMutation)
        f.holder.clearRecentQueries(); advanceUntilIdle()
        assertEquals(emptyList(), f.state().recentQueries)
        assertNull(f.state().recentReadError)
        assertEquals(true, f.disk.data.first().boolean("unrelated"))
        assertEquals(2, f.edits)
        assertTrue(f.reports.none { it.message.orEmpty().contains("private") })
    }

    @Test fun explicitWritesKeepIntentOrderAcrossSuspensionAndObsoleteFeedbackCannotOverwriteNewIntent() = runTest {
        val f = Fixture("first"); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); var first = true
        f.writeHook = { if (first) { first = false; gate.await() } }
        f.holder.commitQuery()
        f.holder.clearRecentQueries()
        f.holder.setQuery("last"); f.holder.commitQuery()
        assertEquals(RecentMutationStatus.Pending, f.state().recentMutation)
        assertEquals(1, f.edits)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(3, f.edits)
        assertEquals(listOf("last"), f.state().recentQueries)
        assertEquals(RecentMutationStatus.Succeeded, f.state().recentMutation)
    }

    @Test fun obsoleteWriteFailureAfterQueryOrSettingsChangeCannotPublishFeedbackOrDiagnostics() = runTest {
        for (settingsChange in listOf(false, true)) {
            val f = Fixture("old"); advanceUntilIdle(); val gate = CompletableDeferred<Unit>()
            f.writeHook = { gate.await(); throw f.failure }
            f.holder.commitQuery(); runCurrent()
            if (settingsChange) { f.preferences = Settings(language = "es-ES"); f.signal() }
            else f.holder.setQuery("new")
            advanceUntilIdle(); gate.complete(Unit); advanceUntilIdle()
            assertEquals(RecentMutationStatus.Idle, f.state().recentMutation)
            assertEquals(emptyList(), f.state().recentQueries)
            assertTrue(f.reports.isEmpty())
        }
    }

    @Test fun newerSuccessfulWriteIsNotContaminatedByOlderFailedFeedback() = runTest {
        val f = Fixture("q"); advanceUntilIdle(); val gate = CompletableDeferred<Unit>(); var first = true
        f.writeHook = { if (first) { first = false; gate.await(); throw f.failure } }
        f.holder.commitQuery(); f.holder.clearRecentQueries()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(RecentMutationStatus.Succeeded, f.state().recentMutation)
        assertTrue(f.reports.isEmpty())
        assertEquals(emptyList(), f.state().recentQueries)
        assertEquals(2, f.edits)
    }

    @Test fun staleRecentReaderCannotClobberCurrentReadAndGenerationRecoveryRemainsLive() = runTest {
        val f = Fixture(); val gate = CompletableDeferred<Unit>()
        f.recentReadHook = { withContext(NonCancellable) { gate.await(); throw f.failure } }; runCurrent()
        f.recentReadHook = null; f.holder.retryRecentRead(); runCurrent()
        assertEquals(emptyList(), f.state().recentQueries)
        gate.complete(Unit); advanceUntilIdle()
        assertNull(f.state().recentReadError)
        assertTrue(f.reports.isEmpty())
        f.app.bump(); f.disk.edit { it.setString(RECENT_QUERIES_KEY, "[\"real\"]") }; advanceUntilIdle()
        assertEquals(listOf("real"), f.state().recentQueries)
        assertEquals(0, f.edits)
    }

    @Test fun disposeDropsLatePagesReadsWritesErrorsAndFurtherActions() = runTest {
        val f = Fixture("q"); val gate = CompletableDeferred<Unit>()
        f.pageHook = { withContext(NonCancellable) { gate.await(); throw f.failure } }
        f.recentReadHook = { withContext(NonCancellable) { gate.await(); throw f.failure } }
        f.writeHook = { withContext(NonCancellable) { gate.await(); throw f.failure } }
        runCurrent(); f.holder.commitQuery(); runCurrent(); f.holder.dispose()
        val before = f.state()
        f.holder.setQuery("ignored"); f.holder.retry(); f.holder.retrySettings(); f.holder.retryRecentRead(); f.holder.clearRecentQueries()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(before, f.state())
        assertTrue(f.reports.isEmpty())
        assertEquals(1, f.edits)
    }

    @Test fun sourceReplacementAndRecoveryKeepObserversBoundedAndParentCancellationDisposesThem() = runTest {
        val f = Fixture("q"); advanceUntilIdle()
        repeat(12) {
            f.holder.setInput("q$it", SearchFacet.entries[it % 4]); advanceUntilIdle()
            f.holder.retrySettings(); f.holder.retryRecentRead(); advanceUntilIdle()
            assertEquals(1, f.activeSettings)
            assertEquals(1, f.activeRecent)
        }
        f.owner.cancel(); advanceUntilIdle()
        assertEquals(0, f.activeSettings)
        assertEquals(0, f.activeRecent)
        assertEquals(0, f.edits)
    }

    @Test fun disposedOwnerCannotPublishLateSuccessfulReadsOrWriteFeedback() = runTest {
        val f = Fixture("q"); val gate = CompletableDeferred<Unit>()
        f.pageHook = { withContext(NonCancellable) { gate.await() } }
        f.recentReadHook = { withContext(NonCancellable) { gate.await() } }
        f.writeHook = { withContext(NonCancellable) { gate.await() } }
        runCurrent(); f.holder.commitQuery(); f.holder.dispose()
        val before = f.state()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(before, f.state())
        assertTrue(f.reports.isEmpty())
    }

    @Test fun cancellationAndProgrammerErrorsAreNotConvertedToStorageRecovery() = runTest {
        val f = Fixture(); f.settingsError = IllegalStateException("bug"); advanceUntilIdle()
        assertIs<IllegalStateException>(f.errors.single())
        assertNull(f.state().settingsError)
        assertTrue(f.reports.isEmpty())
        val g = Fixture("q"); g.catalogReadError = CancellationException("cancel"); advanceUntilIdle()
        assertEquals(SectionState.Loading, g.state().results)
        assertTrue(g.reports.isEmpty())
        val h = Fixture(); h.recentReadError = IllegalStateException("read bug"); advanceUntilIdle()
        assertIs<IllegalStateException>(h.errors.single())
        assertNull(h.state().recentReadError)
    }

    @Test fun savedPrimitivesRestoreRawQueryFacetWithoutTouchingLegacyTVKeys() = runTest {
        val f = Fixture(); f.saver.putString("search.query", "legacy")
        f.holder.setInput(" raw ", SearchFacet.Person); advanceUntilIdle()
        val g = Fixture(saver = f.saver); runCurrent()
        assertEquals(" raw ", g.state().query)
        assertEquals(SearchFacet.Person, g.state().facet)
        assertEquals("person/raw/en-US/false/1", g.calls.single())
        assertEquals("legacy", f.saver.getString("search.query"))
        assertEquals(0, g.edits)
    }

    @Test fun malformedSavedFacetAndWrongScalarTypesAreRejectedBeforeWork() = runTest {
        assertFailsWith<IllegalArgumentException> { Fixture("q", "not-a-facet") }
        val values = mutableMapOf<String, Any>("productSearch.query" to 12)
        val strict = object : StateSaver {
            override fun getString(key: String): String? {
                val value = values[key]
                require(value == null || value is String) { "Wrong saved scalar type" }
                return value
            }
            override fun getInt(key: String): Int? = error("unused")
            override fun getBoolean(key: String): Boolean? = error("unused")
            override fun putString(key: String, value: String?) = error("must not overwrite invalid input")
            override fun putInt(key: String, value: Int?) = error("unused")
            override fun putBoolean(key: String, value: Boolean?) = error("unused")
        }
        assertFailsWith<IllegalArgumentException> { Fixture(saver = strict) }
        assertEquals(12, values["productSearch.query"])
    }
}
