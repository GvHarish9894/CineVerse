package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.*
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

/**
 * The real [LibraryActionsStateHolder] over the real [LibraryRepository] with fake sources. [writes] records the identity
 * each write actually reached the store with; [actions] the reported toggles and opened Rate prompts.
 */
internal class WebDetailActionsFake {
    val repositorySession = MutableStateFlow<AuthSession?>(null)
    val snapshot = MutableStateFlow(LibraryStateSnapshot(LibraryState.Unknown))
    var failAccountRead = false
    val refs = mutableListOf<MediaRef>()
    val accountState: (MediaRef) -> Flow<LibraryStateSnapshot> = { ref ->
        refs += ref
        flow {
            if (failAccountRead) throw BrowserStorageException(BrowserStorageFailure.Blocked)
            emitAll(snapshot)
        }
    }
    val writes = mutableListOf<String>()
    val actions = mutableListOf<Pair<LibraryKind, Title>>()
    val prompts = mutableListOf<RatePrompt>()
    private val rows = MutableStateFlow<List<GuestLibraryRow>>(emptyList())
    private val guest = object : GuestLibraryStore {
        override fun observeKind(kind: LibraryKind, media: MediaType) = rows.map { all -> all.filter { it.kind == kind && it.ref.mediaType == media } }
        override fun observeTitle(ref: MediaRef) = rows.map { all -> all.filter { it.ref == ref } }
        override suspend fun all() = rows.value
        override suspend fun upsert(row: GuestLibraryRow) {
            writes += "guest:${row.kind}/${row.ref.key}"
            rows.value = rows.value.filterNot { it.ref == row.ref && it.kind == row.kind } + row
        }
        override suspend fun delete(ref: MediaRef, kind: LibraryKind) {
            writes += "guest:$kind/${ref.key}"
            rows.value = rows.value.filterNot { it.ref == ref && it.kind == kind }
        }
    }
    private val account = object : AccountLibrarySource {
        override suspend fun page(session: AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage = error("No list reads")
        override fun invalidateLists() = Unit
        override suspend fun states(session: AuthSession, ref: MediaRef) = LibraryState(true, false, false, null, emptySet())
        override suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState) {
            writes += "${session.accountObjectId.value}#${session.sessionGeneration}:$kind/${ref.key}"
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val holder = LibraryActionsStateHolder(LibraryRepository(repositorySession, GuestLibrarySource(guest), account), scope, scope, { outcome ->
        if (outcome.result is LibraryWriteResult.Done) actions += when (outcome.write) {
            LibraryWrite.ToggleWatchlist -> LibraryKind.WATCHLIST
            LibraryWrite.ToggleFavourite -> LibraryKind.FAVOURITE
            else -> LibraryKind.RATING
        } to outcome.title
    })

    init {
        scope.launch { holder.ratePrompt.filterNotNull().collect { prompts += it; actions += LibraryKind.RATING to it.title } }
    }

    /** A producer snapshot carrying [origin] as its source proof. */
    fun known(state: LibraryState, origin: AuthSession?) { snapshot.value = LibraryStateSnapshot(state, sourceIdentityKnown = true, sourceIdentity = origin) }
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
            rawSettings, session, storage, { upstreamRetries++; onRetry() }, { failures += it; storage.value = BrowserStorageFailure.Blocked },
            library.holder, library.accountState, scope)
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
        f.library.known(LibraryState(true, false, false, null, emptySet()), null)
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
        f.library.known(LibraryState(true, false, false, null, emptySet()), null)
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
        f.library.known(LibraryState(true, false, false, null, emptySet()), null)
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
        f.library.known(LibraryState(true, false, false, null, emptySet()), first.session)
        f.library.repositorySession.value = first.session
        f.library.holder.onIdentity(first.session)
        val owner = f.owner(backgroundScope)
        backgroundScope.launch { owner.holder.accountState.collect {} }
        runCurrent()
        val rendered = owner.state.value.producer.request!!
        f.session.value = second
        owner.act(LibraryKind.WATCHLIST, rendered, first)
        assertTrue(f.library.actions.isEmpty())
        owner.retryDetail(); runCurrent()
        owner.act(LibraryKind.WATCHLIST, rendered, second)
        owner.act(LibraryKind.RATING, owner.state.value.producer.request, first)
        assertTrue(f.library.actions.isEmpty())
        assertTrue(f.library.writes.isEmpty())
        assertTrue(f.library.prompts.isEmpty())
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
        f.library.known(LibraryState(true, true, false, 7.5, setOf(LibraryKind.FAVOURITE)), null); runCurrent()
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

    @Test fun explicitOverloadsDispatchTheRenderedSessionNotTheHoldersForwardedIdentity() = runTest {
        val f = Fixture()
        val a = AuthSession(AccountObjectId("account-a"), "Fixture", 41L)
        f.session.value = WebSessionState.Ready(a)
        f.library.repositorySession.value = a
        f.library.known(LibraryState(true, false, false, null, emptySet()), a)
        val owner = f.owner(backgroundScope)
        runCurrent()
        // onIdentity was never forwarded: a legacy invocation-capture toggle would capture guest and be superseded.
        f.act(owner, LibraryKind.WATCHLIST); f.act(owner, LibraryKind.FAVOURITE)
        assertEquals(listOf("account-a#41:WATCHLIST/movie:7", "account-a#41:FAVOURITE/movie:7"), f.library.writes)
        assertEquals(listOf(LibraryKind.WATCHLIST, LibraryKind.FAVOURITE), f.library.actions.map { it.first })
        assertTrue(f.library.actions.all { it.second == (owner.state.value.section as SectionState.Content).value.toTitle() })
        owner.close()
    }

    @Test fun rateOpensThroughOpenRateWithTheRenderedSessionAndIsRejectedWhenUnknownOrPending() = runTest {
        val f = Fixture()
        val a = AuthSession(AccountObjectId("account-a"), "Fixture", 41L)
        f.session.value = WebSessionState.Ready(a)
        f.library.repositorySession.value = a
        f.library.holder.onIdentity(a)
        val owner = f.owner(backgroundScope)
        runCurrent()
        f.act(owner, LibraryKind.RATING)
        f.library.known(LibraryState(true, false, false, 6.0, setOf(LibraryKind.RATING)), a); runCurrent()
        f.act(owner, LibraryKind.RATING)
        assertTrue(f.library.prompts.isEmpty())
        f.library.known(LibraryState(true, false, false, 6.0, emptySet()), a); runCurrent()
        f.act(owner, LibraryKind.RATING)
        val prompt = f.library.prompts.single()
        assertEquals(a, prompt.identity)
        assertEquals(MediaRef(MediaType.Movie, 7), prompt.title.ref)
        assertTrue(f.library.writes.isEmpty())
        owner.close()
    }

    @Test fun accountSnapshotIsActionableOnlyForItsFullSourceIdentityAndNeverRelabelled() = runTest {
        val a = AuthSession(AccountObjectId("account-a"), "Fixture", 41L)
        val a2 = a.copy(sessionGeneration = 42L)
        val state = LibraryState(true, true, false, null, emptySet())
        fun origin(known: Boolean, identity: AuthSession?) = LibraryStateSnapshot(state, known, identity)
        assertEquals(state, origin(true, a).webDetailAccount(WebSessionState.Ready(a)))
        assertEquals(state, origin(true, null).webDetailAccount(WebSessionState.Ready(null)))
        assertEquals(LibraryState.Unknown, origin(true, null).webDetailAccount(WebSessionState.Ready(a))) // Guest data, signed-in render.
        assertEquals(LibraryState.Unknown, origin(true, a).webDetailAccount(WebSessionState.Ready(null)))
        assertEquals(LibraryState.Unknown, origin(true, a).webDetailAccount(WebSessionState.Ready(a2)))
        assertEquals(LibraryState.Unknown, origin(false, null).webDetailAccount(WebSessionState.Ready(null)))
        assertEquals(LibraryState.Unknown, origin(true, a).webDetailAccount(null))

        val f = Fixture()
        f.session.value = WebSessionState.Ready(a2)
        f.library.repositorySession.value = a2
        f.library.known(state, a) // The previous sign-in's snapshot.
        val owner = f.owner(backgroundScope)
        runCurrent()
        f.act(owner, LibraryKind.WATCHLIST)
        f.library.known(state, null); runCurrent()
        f.act(owner, LibraryKind.WATCHLIST)
        assertTrue(f.library.writes.isEmpty())
        assertEquals(LibraryStateSnapshot(state, true, null), owner.account.value) // Published as produced, never relabelled.
        f.library.known(state, a2); runCurrent()
        f.act(owner, LibraryKind.WATCHLIST)
        assertEquals(listOf("account-a#42:WATCHLIST/movie:7"), f.library.writes)
        owner.close()
    }

    @Test fun accountReadStorageFailureReportsMasksAndRecollectsOnlyOnExplicitRetry() = runTest {
        val f = Fixture()
        f.session.value = WebSessionState.Ready(null)
        f.library.failAccountRead = true
        f.library.known(LibraryState(true, false, false, null, emptySet()), null)
        val owner = f.owner(backgroundScope)
        runCurrent()
        assertEquals(1, f.failures.size)
        assertEquals(LibraryStateSnapshot(LibraryState.Unknown), owner.account.value)
        f.library.failAccountRead = false
        f.storage.value = null; runCurrent() // External recovery re-reads the catalog, never replays the account read.
        assertEquals(1, f.library.refs.size)
        f.act(owner, LibraryKind.WATCHLIST)
        assertTrue(f.library.writes.isEmpty())
        owner.retryReads(); runCurrent()
        assertEquals(2, f.library.refs.size)
        assertEquals(LibraryStateSnapshot(LibraryState(true, false, false, null, emptySet()), true, null), owner.account.value)
        f.act(owner, LibraryKind.WATCHLIST)
        assertEquals(listOf("guest:WATCHLIST/movie:7"), f.library.writes)
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
