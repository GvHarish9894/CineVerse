package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

internal class WebDetailActionsFake : LibraryActions {
    val value = MutableStateFlow(LibraryState.Unknown)
    val actions = mutableListOf<Pair<LibraryKind, Title>>()
    val refs = mutableListOf<MediaRef>()
    override fun state(ref: MediaRef): Flow<LibraryState> { refs += ref; return value }
    override fun toggleWatchlist(title: Title) { actions += LibraryKind.WATCHLIST to title }
    override fun toggleFavourite(title: Title) { actions += LibraryKind.FAVOURITE to title }
    override fun openRate(title: Title) { actions += LibraryKind.RATING to title }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WebDetailOwnerTest {
    private class Fixture {
        val api = WebDetailCatalogFake()
        val generation = GenerationCounter()
        val session = MutableStateFlow<WebSessionState>(WebSessionState.Checking)
        val storage = MutableStateFlow<BrowserStorageFailure?>(null)
        val revisions = MutableStateFlow(0)
        val library = WebDetailActionsFake()
        var actual = Settings()
        var failSettings = false
        var subscriptions = 0
        var activeReaders = 0
        var upstreamRetries = 0
        var onRetry: () -> Unit = {}
        var firstRaw: CompletableDeferred<Unit>? = null
        val failures = mutableListOf<StorageException>()
        val rawSettings = flow {
            subscriptions++
            firstRaw?.await()
            activeReaders++
            try {
                revisions.collect {
                    if (failSettings) throw BrowserStorageException(BrowserStorageFailure.Blocked)
                    emit(actual)
                }
            } finally { activeReaders-- }
        }
        val catalog = CatalogRepository(api, generation, { actual })
        fun owner(scope: CoroutineScope, entry: WebEntry = entry()): WebDetailOwner = WebDetailOwner(entry, catalog, generation,
            rawSettings, session, storage, { upstreamRetries++; onRetry() }, { failures += it; storage.value = BrowserStorageFailure.Blocked }, library, scope)
        fun act(owner: WebDetailOwner, kind: LibraryKind) =
            owner.act(kind, owner.state.value.producer.request, session.value as? WebSessionState.Ready)
    }

    @Test fun movieUsesSharedMappingAndTruthfulEnglishFallbackAndValidatedVideoOrder() = runTest {
        val f = Fixture()
        f.actual = Settings(language = "hi-IN")
        f.api.movie = { id, language -> MovieDetailDto(id, title = "Fixture film", overview = if (language == "en-US") "English overview" else "स्थानीय",
            tagline = if (language == "en-US") "English tagline" else "", runtime = 121, releaseDate = "2026-10-01", genres = listOf(GenreDto(18, "Drama"))) }
        f.api.videoResult = VideosDto(listOf(VideoDto(key = "bad", site = "YouTube", type = "Trailer"),
            VideoDto(key = "aB_09-xYz12", name = "Real trailer", site = "YouTube", type = "Trailer", official = true)))
        val owner = f.owner(backgroundScope)
        runCurrent()
        val detail = (owner.state.value.section as SectionState.Content).value
        assertEquals(MediaRef(MediaType.Movie, 7), detail.ref)
        assertEquals("English tagline (English)", detail.webDetailTagline())
        assertEquals("स्थानीय", detail.webDetailOverview())
        assertEquals("2026 · 2026-10-01 · Drama · 2h 1m", detail.webDetailMetadata())
        assertEquals(listOf("aB_09-xYz12"), detail.webDetailVideos().map { it.key })
        assertEquals(listOf("movie:7:hi-IN", "movie:7:en-US", "videos:movie:7:en-US"), f.api.calls)
        owner.close()
    }

    @Test fun tvUsesActualNetworkRuntimeSeasonsAndCreatorWithoutInventedCounts() = runTest {
        val f = Fixture()
        f.api.tv = { id, _ -> TvDetailDto(id, name = "Fixture series", episodeRunTime = listOf(47), networks = listOf(NetworkDto(9, "Fixture network")),
            createdBy = listOf(CreatorDto(42, "Fixture creator")), seasons = listOf(SeasonSummaryDto(90, 0, "Specials"), SeasonSummaryDto(91, 2, "Second season"))) }
        val owner = f.owner(backgroundScope, entry(WebRoute.Detail("tv", 81)))
        runCurrent()
        val detail = (owner.state.value.section as SectionState.Content).value
        assertEquals(MediaRef(MediaType.Tv, 81), detail.ref)
        assertEquals("47m · Fixture network", detail.webDetailMetadata())
        assertEquals(listOf(2, 0), detail.seasons.map { it.seasonNumber })
        assertEquals(2, detail.episodesSeason())
        assertEquals(CastCredit(42, "Fixture creator", null, "Creator"), detail.leadCrew)
        assertTrue(detail.facts.none { it.label == "Episodes" })
        owner.close()
    }

    @Test fun missingOptionalFieldsHaveNoSyntheticMetadataOrEnglishLabels() {
        val detail = MovieDetailDto(7).toDetail("US")
        assertNull(detail.webDetailTagline())
        assertNull(detail.webDetailOverview())
        assertEquals("", detail.webDetailMetadata())
        assertTrue(detail.facts.isEmpty())
        assertTrue(detail.webDetailVideos().isEmpty())
        assertNull(detail.trailer())
        assertTrue(detail.cast.isEmpty())
        assertNull(detail.leadCrew)
    }

    @Test fun malformedRoutesAndSavedScalarsRejectBeforeHolderOrReaderAndPreserveRaw() = runTest {
        val f = Fixture()
        val bad = listOf(entry(WebRoute.Person(7)), entry(WebRoute.Detail("person", 7)), entry(WebRoute.Detail("movie", 0)), entry(WebRoute.Detail("tv", -1)),
            entry(values = mapOf("detail.index" to "oops")), entry(values = mapOf("detail.offset" to "-1")),
            entry(values = mapOf("detail.index" to "01")), entry(values = mapOf("detail.offset" to "2147483648")),
            entry(values = mapOf("detail.id" to "7")), entry(values = mapOf("detail.id" to "8", "detail.media" to "movie")),
            entry(values = mapOf("detail.id" to "7", "detail.media" to "tv")))
        bad.forEach { candidate ->
            val before = candidate.encoded()
            assertFailsWith<IllegalArgumentException> { f.owner(backgroundScope, candidate) }
            assertEquals(before, candidate.encoded())
        }
        runCurrent()
        assertEquals(0, f.subscriptions)
        assertTrue(f.api.calls.isEmpty())
        assertTrue(f.library.refs.isEmpty())
        assertEquals(WebDetailEntry(MediaRef(MediaType.Movie, 7), 0, 0), validateWebDetailEntry(entry()))
        assertEquals(12, validateWebDetailEntry(entry(values = mapOf("detail.index" to "12", "detail.id" to "7", "detail.media" to "movie"))).index)
    }

    @Test fun loadingAndCatalogFailureStayDistinctAndRetryRetainsLastRealContent() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        var failing = false
        f.api.movie = { id, _ -> gate.await(); if (failing) throw TmdbException(TmdbError.Network); MovieDetailDto(id, title = "Retained film") }
        val owner = f.owner(backgroundScope)
        runCurrent()
        assertEquals(SectionState.Loading, owner.state.value.section)
        gate.complete(Unit); runCurrent()
        assertEquals("Retained film", owner.state.value.retained?.title)
        f.catalog.invalidateTitle(MediaType.Movie, 7); failing = true
        owner.retryDetail(); runCurrent()
        assertEquals(SectionState.Error(SectionErrorKind.Offline), owner.state.value.section)
        assertEquals("Retained film", owner.state.value.retained?.title)
        failing = false; owner.retryDetail(); runCurrent()
        assertTrue(owner.state.value.section is SectionState.Content)
        owner.close()
    }

    @Test fun firstSettingsFailureNeverDefaultsOrFetchesAndExplicitRecoveryUsesActualValues() = runTest {
        val f = Fixture().apply { failSettings = true; actual = Settings(language = "fr-FR") }
        val owner = f.owner(backgroundScope)
        runCurrent()
        assertTrue(f.api.calls.isEmpty())
        assertTrue(owner.state.value.settingsFailed)
        assertEquals(0, f.activeReaders)
        runCurrent(); assertEquals(1, f.subscriptions) // No hot failure loop.
        f.failSettings = false
        f.storage.value = null
        owner.retryReads(); runCurrent()
        assertEquals(1, f.upstreamRetries)
        assertTrue(f.api.calls.contains("movie:7:fr-FR"))
        assertTrue(owner.holder.state.value.detail is SectionState.Content)
        assertFalse(owner.state.value.settingsFailed)
        assertFalse(owner.state.value.freshnessUnverified)
        assertTrue(owner.state.value.section is SectionState.Content)
        owner.close()
    }

    @Test fun equalRecoveryRevokesEveryReadySnapshotUntilRealReplacementCompletes() = runTest {
        val f = Fixture()
        f.session.value = WebSessionState.Ready(null)
        f.library.value.value = LibraryState(true, false, false, null, emptySet())
        val owner = f.owner(backgroundScope)
        backgroundScope.launch { owner.holder.accountState.collect {} }
        runCurrent()
        val original = owner.state.value.producer.retained!!
        val gate = CompletableDeferred<Unit>()
        f.catalog.invalidateTitle(MediaType.Movie, 7)
        f.api.movie = { id, _ -> gate.await(); MovieDetailDto(id, title = "Fixture film", overview = "Real fixture overview") }
        f.failSettings = true; f.revisions.value++; runCurrent()
        assertTrue(owner.state.value.settingsFailed)
        assertEquals(original, owner.state.value.producer.retained)
        f.failSettings = false
        f.onRetry = { f.storage.value = null }
        val snapshots = mutableListOf<WebDetailReadState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.state.collect { snapshots += it } }
        owner.retryReads(); runCurrent()
        assertSame(owner.holder, owner.holder)
        assertEquals(2, f.subscriptions)
        assertEquals(1, f.upstreamRetries)
        assertTrue(snapshots.all { it.freshnessUnverified })
        assertEquals(original, owner.state.value.producer.retained)
        assertNotEquals(original.context, owner.state.value.producer.request)
        f.act(owner, LibraryKind.WATCHLIST)
        assertTrue(f.library.actions.isEmpty())
        gate.complete(Unit); runCurrent()
        assertFalse(owner.state.value.freshnessUnverified)
        val completed = owner.state.value.producer
        assertEquals(completed.request, completed.completion?.context)
        assertEquals(completed.request, completed.retained?.context)
        assertEquals(original.detail, completed.retained?.detail) // Equal DTO, genuinely new completion.
        f.act(owner, LibraryKind.WATCHLIST)
        assertEquals(1, f.library.actions.size)
        owner.close()
    }

    @Test fun blockedImmediateConstructionNeverSubscribesOrFetchesUntilRealRecovery() = runTest {
        val f = Fixture()
        f.storage.value = BrowserStorageFailure.Blocked
        f.session.value = WebSessionState.Ready(null)
        f.library.value.value = LibraryState(true, false, false, null, emptySet())
        val parent = Job(backgroundScope.coroutineContext[Job])
        val owner = f.owner(CoroutineScope(parent + UnconfinedTestDispatcher(testScheduler)))
        backgroundScope.launch { owner.holder.accountState.collect {} }
        assertEquals(0, f.subscriptions)
        assertTrue(f.api.calls.isEmpty())
        assertEquals(DetailSettingsRead.Unknown, owner.holder.state.value.settingsRead)
        f.act(owner, LibraryKind.WATCHLIST)
        assertTrue(f.library.actions.isEmpty())
        f.onRetry = { f.storage.value = null }
        owner.retryReads(); runCurrent()
        assertEquals(1, f.upstreamRetries)
        assertEquals(1, f.subscriptions)
        assertEquals(1, f.api.calls.size)
        assertNotNull(owner.state.value.producer.completedWebDetail(owner.validated.ref, f.generation.current))
        f.act(owner, LibraryKind.WATCHLIST)
        assertEquals(1, f.library.actions.size)
        owner.close(); parent.cancel()
    }

    @Test fun runtimeRecoveryCallbackCanCloseBeforeRawReaderRestarts() = runTest {
        val f = Fixture()
        val owner = f.owner(backgroundScope)
        runCurrent()
        f.onRetry = { owner.close() }
        owner.retryReads(); runCurrent()
        assertEquals(1, f.upstreamRetries)
        assertEquals(1, f.subscriptions)
        assertEquals(DetailSettingsRead.Unknown, owner.holder.state.value.settingsRead)
        var browsed = false
        owner.browse { browsed = true }
        assertFalse(browsed)
    }

    @Test fun cachedEqualCompletionAlsoRestoresAuthorityWithoutClaimingHttpFreshness() = runTest {
        val f = Fixture()
        val owner = f.owner(backgroundScope)
        runCurrent()
        val original = owner.state.value.producer.retained!!
        f.storage.value = BrowserStorageFailure.Blocked; runCurrent()
        assertEquals(DetailSettingsRead.Unknown, owner.holder.state.value.settingsRead)
        assertTrue(owner.state.value.freshnessUnverified)
        assertEquals(0, f.activeReaders)
        f.storage.value = null; runCurrent()
        assertEquals(2, f.subscriptions)
        assertEquals(1, f.api.calls.size) // CatalogRepository cache, not a second HTTP call.
        assertFalse(owner.state.value.freshnessUnverified)
        assertNotEquals(original.context, owner.state.value.producer.retained?.context)
        assertEquals(original.detail, owner.state.value.retained)
        owner.close()
    }

    @Test fun externalRecoveryUsesChangedLanguageAndRegionAndRetainsOriginalContextThroughError() = runTest {
        val f = Fixture()
        val owner = f.owner(backgroundScope)
        runCurrent()
        val original = owner.state.value.producer.retained!!
        val gate = CompletableDeferred<Unit>()
        f.api.movie = { _, _ -> gate.await(); throw TmdbException(TmdbError.Network) }
        f.failSettings = true; f.revisions.value++; runCurrent()
        f.actual = Settings(language = "es-ES", region = "ES"); f.failSettings = false
        f.storage.value = null; runCurrent()
        assertEquals(2, f.subscriptions)
        assertEquals("es-ES", owner.state.value.producer.request?.language)
        assertEquals("ES", owner.state.value.producer.request?.region)
        assertEquals(original, owner.state.value.producer.retained)
        assertTrue(owner.state.value.freshnessUnverified)
        gate.complete(Unit); runCurrent()
        assertEquals(SectionState.Error(SectionErrorKind.Offline), owner.state.value.section)
        assertEquals(original, owner.state.value.producer.retained)
        assertEquals(owner.state.value.producer.request, owner.state.value.producer.completion?.context)
        assertTrue(owner.state.value.freshnessUnverified)
        owner.close(); runCurrent()
        assertEquals(0, f.activeReaders)
        owner.retryReads(); f.storage.value = BrowserStorageFailure.Blocked; runCurrent(); f.storage.value = null; runCurrent()
        assertEquals(2, f.subscriptions)
        assertEquals(0, f.upstreamRetries)
    }

    @Test fun unknownRawSettingsNeverUsesDefaultsAndRegionOnlyChangeGetsANewCompletion() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.firstRaw = gate
        val owner = f.owner(backgroundScope)
        runCurrent()
        assertEquals(DetailSettingsRead.Unknown, owner.state.value.producer.settingsRead)
        assertTrue(f.api.calls.isEmpty())
        gate.complete(Unit); runCurrent()
        val original = owner.state.value.producer.retained!!
        f.actual = Settings(region = "GB"); f.revisions.value++; runCurrent()
        val updated = owner.state.value.producer.retained!!
        assertEquals("GB", updated.context.region)
        assertNotEquals(original.context, updated.context)
        assertEquals(updated.context, owner.state.value.producer.completion?.context)
        assertFalse(owner.state.value.freshnessUnverified)
        owner.close()
    }

    @Test fun idleAppGenerationNeedsRootEventAndClickRejectsOldRenderedContext() = runTest {
        val f = Fixture()
        f.session.value = WebSessionState.Ready(null)
        f.library.value.value = LibraryState(true, false, false, null, emptySet())
        val owner = f.owner(backgroundScope)
        backgroundScope.launch { owner.holder.accountState.collect {} }
        runCurrent()
        val rendered = owner.state.value.producer.request!!
        val ready = f.session.value as WebSessionState.Ready
        f.generation.bump()
        owner.act(LibraryKind.WATCHLIST, rendered, ready)
        assertTrue(f.library.actions.isEmpty())
        assertEquals(rendered, owner.holder.state.value.request) // GenerationCounter is not observable.
        owner.contextChanged(); runCurrent()
        assertEquals(f.generation.current, owner.state.value.producer.request?.appGeneration)
        owner.act(LibraryKind.WATCHLIST, rendered, ready)
        assertTrue(f.library.actions.isEmpty())
        f.act(owner, LibraryKind.WATCHLIST)
        assertEquals(1, f.library.actions.size)
        owner.close()
    }

    @Test fun staleRenderedOpaqueSessionAndSupersededRequestAreRejected() = runTest {
        val f = Fixture()
        val first = WebSessionState.Ready(AuthSession(AccountObjectId("account"), "Fixture", 9007199254740992L))
        val second = WebSessionState.Ready(AuthSession(AccountObjectId("account"), "Fixture", 9007199254740993L))
        f.session.value = first
        f.library.value.value = LibraryState(true, false, false, null, emptySet())
        val owner = f.owner(backgroundScope)
        backgroundScope.launch { owner.holder.accountState.collect {} }
        runCurrent()
        val rendered = owner.state.value.producer.request!!
        f.session.value = second
        owner.act(LibraryKind.WATCHLIST, rendered, first)
        assertTrue(f.library.actions.isEmpty())
        owner.retryDetail(); runCurrent()
        owner.act(LibraryKind.WATCHLIST, rendered, second)
        assertTrue(f.library.actions.isEmpty())
        owner.close()
    }

    @Test fun cancellationResistantOldPrimaryCannotReplaceChangedContextOrDisposedState() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.api.movie = { id, language ->
            if (language == "en-US") withContext(NonCancellable) { gate.await() }
            MovieDetailDto(id, title = language, tagline = "Present", overview = "Present",
                videos = VideosDto(listOf(VideoDto(key = "aB_09-xYz12", site = "YouTube", type = "Trailer"))))
        }
        val owner = f.owner(backgroundScope)
        runCurrent()
        f.actual = Settings(language = "fr-FR"); f.revisions.value++; runCurrent()
        assertEquals("fr-FR", owner.state.value.retained?.title)
        val replacement = owner.state.value.producer.retained
        owner.close()
        val closed = owner.state.value
        gate.complete(Unit); runCurrent()
        assertEquals(replacement, owner.holder.state.value.retained)
        assertEquals(closed, owner.state.value)
        assertEquals(DetailSettingsRead.Unknown, owner.holder.state.value.settingsRead)
    }

    @Test fun reentrantCloseOnRevocationPreventsRuntimeRestart() = runTest {
        val f = Fixture()
        val owner = f.owner(backgroundScope)
        runCurrent()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            owner.state.collect { if (it.freshnessUnverified) owner.close() }
        }
        owner.retryReads(); runCurrent()
        assertEquals(0, f.upstreamRetries)
        assertEquals(1, f.subscriptions)
        assertEquals(DetailSettingsRead.Unknown, owner.holder.state.value.settingsRead)
    }

    @Test fun parentCancellationMakesSynchronousMethodsInert() = runTest {
        val f = Fixture()
        val parent = Job(backgroundScope.coroutineContext[Job])
        val owner = f.owner(CoroutineScope(backgroundScope.coroutineContext + parent))
        runCurrent()
        parent.cancel()
        owner.retryReads(); owner.retryDetail(); owner.contextChanged()
        f.act(owner, LibraryKind.WATCHLIST); runCurrent()
        assertEquals(0, f.upstreamRetries)
        assertEquals(1, f.subscriptions)
        assertTrue(f.library.actions.isEmpty())
        owner.close()
    }

    @Test fun disposedOwnerCancelsSettingsAccountAndCatalogAndCannotPublishLateResult() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        var canceled = false
        f.api.movie = { id, _ -> try { gate.await() } catch (failure: CancellationException) { canceled = true; throw failure }; MovieDetailDto(id) }
        val owner = f.owner(backgroundScope)
        backgroundScope.launch { owner.holder.accountState.collect {} }
        runCurrent()
        owner.close(); runCurrent()
        assertTrue(canceled)
        assertEquals(0, f.activeReaders)
        gate.complete(Unit); owner.retryDetail(); owner.retryReads(); f.act(owner, LibraryKind.WATCHLIST); runCurrent()
        assertEquals(SectionState.Loading, owner.state.value.section)
        assertEquals(1, f.api.calls.size)
        assertTrue(f.library.actions.isEmpty())
    }

    @Test fun accountUnknownPendingAndSessionUnknownNeverMutateButVerifiedGuestUsesActualInjectedActions() = runTest {
        val f = Fixture()
        val owner = f.owner(backgroundScope)
        backgroundScope.launch { owner.holder.accountState.collect {} }
        runCurrent()
        f.session.value = WebSessionState.Ready(null)
        f.act(owner, LibraryKind.WATCHLIST)
        assertTrue(f.library.actions.isEmpty())
        f.library.value.value = LibraryState(true, true, false, 7.5, setOf(LibraryKind.FAVOURITE)); runCurrent()
        f.act(owner, LibraryKind.FAVOURITE)
        assertTrue(f.library.actions.isEmpty())
        f.act(owner, LibraryKind.WATCHLIST); f.act(owner, LibraryKind.RATING)
        assertEquals(listOf(LibraryKind.WATCHLIST, LibraryKind.RATING), f.library.actions.map { it.first })
        assertTrue(f.library.actions.all { it.second.ref == MediaRef(MediaType.Movie, 7) && it.second.title == "Fixture film" })
        f.session.value = WebSessionState.Checking
        f.act(owner, LibraryKind.WATCHLIST)
        f.session.value = WebSessionState.Failed(TmdbError.Network)
        f.act(owner, LibraryKind.WATCHLIST)
        f.session.value = WebSessionState.Ready(null); f.storage.value = BrowserStorageFailure.Blocked
        f.act(owner, LibraryKind.WATCHLIST)
        assertEquals(2, f.library.actions.size)
        owner.close()
    }

    @Test fun mismatchedReturnedTitleIdentityIsNeverPublishedOrActionable() = runTest {
        val f = Fixture()
        f.api.movie = { _, _ -> MovieDetailDto(99, title = "Wrong title") }
        val owner = f.owner(backgroundScope)
        runCurrent()
        assertEquals(SectionState.Error(SectionErrorKind.Generic), owner.state.value.section)
        assertNull(owner.state.value.retained)
        owner.close()
    }

    companion object {
        internal fun entry(route: WebRoute = WebRoute.Detail("movie", 7), values: Map<String, String> = emptyMap()) = WebEntry(route, values.toMutableMap())
    }
}
