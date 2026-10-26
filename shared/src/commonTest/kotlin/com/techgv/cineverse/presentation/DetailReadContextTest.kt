package com.techgv.cineverse.presentation

import androidx.lifecycle.ViewModelStore
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.LibraryActions
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Primary completion is request correlation, not HTTP freshness, storage recovery or account authorization. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailReadContextTest {
    private val dispatcher = StandardTestDispatcher()
    private val generation = GenerationCounter()
    private val calls = mutableListOf<String>()
    private var failures = 0
    private var movie: suspend (String) -> MovieDetailDto = { MovieDetailDto(550, title = "Title", tagline = "Tagline", overview = "Overview") }
    private var tv: suspend (String) -> TvDetailDto = { TvDetailDto(550, name = "TV", tagline = "Tagline", overview = "Overview") }
    private var videos: suspend () -> VideosDto = { VideosDto(listOf(VideoDto(key = "yt", site = "YouTube", type = "Trailer"))) }
    private val api = object : UnusedTmdbApi() {
        override suspend fun movieDetail(id: Int, language: String, append: String, sessionId: String?): MovieDetailDto {
            assertEquals(550, id); assertNull(sessionId)
            calls += "movie/$language"
            return movie(language)
        }
        override suspend fun tvDetail(id: Int, language: String, append: String, sessionId: String?): TvDetailDto {
            assertEquals(550, id); assertNull(sessionId)
            calls += "tv/$language"
            return tv(language)
        }
        override suspend fun videos(media: MediaType, id: Int, language: String): VideosDto {
            calls += "videos/$media/$language"
            return videos()
        }
    }
    private val repo = catalog(api, generation, MutableStateFlow(Settings()))

    /** A cold raw reader: each recovery must really subscribe again, not replay a bridge value. */
    private class RawReader {
        val subscriptions = mutableListOf<Channel<Result<Settings>>>()
        val flow: Flow<Settings> = flow {
            val channel = Channel<Result<Settings>>(Channel.UNLIMITED)
            subscriptions += channel
            for (value in channel) emit(value.getOrThrow())
        }
        fun value(settings: Settings = Settings()) { check(subscriptions.last().trySend(Result.success(settings)).isSuccess) }
        fun fail() { check(subscriptions.last().trySend(Result.failure(StorageException("fixture"))).isSuccess) }
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.holder(
        settings: Flow<Settings> = flowOf(Settings()),
        scope: CoroutineScope? = backgroundScope,
        media: MediaType = MediaType.Movie,
        library: LibraryActions? = null,
        onFailure: (StorageException) -> Unit = { failures++ },
    ) = DetailStateHolder(MediaRef(media, 550), repo, settings, generation, library, scope, onFailure)

    private fun DetailUiState.proven(): Boolean {
        val context = request ?: return false
        return settingsRead == DetailSettingsRead.Ready && completion?.context == context &&
            completion?.result is SectionState.Content && retained?.context == context && generation.isCurrent(context.appGeneration)
    }

    @Test fun manualLegacyContentAndDefaultConstructionAreUnknownNotProof() {
        val detail = MovieDetailDto(550).toDetail("IN")
        val manual = DetailUiState(SectionState.Content(detail))
        assertEquals(DetailSettingsRead.Unknown, manual.settingsRead)
        assertNull(manual.request); assertNull(manual.completion); assertNull(manual.retained)
        assertFalse(manual.proven())
        assertEquals(SectionState.Loading, DetailUiState().detail)
        val context = DetailReadContext(detail.ref, "en-US", "IN", 0, 0)
        assertFailsWith<IllegalArgumentException> { DetailReadCompletion(context, SectionState.Loading) }
        assertFailsWith<IllegalArgumentException> { DetailReadCompletion(context, SectionState.Empty) }
    }

    @Test fun unknownAndFirstRawFailureNeverFetchAndCatalogRetryCannotRestartReader() = runTest(dispatcher) {
        val raw = RawReader()
        val holder = holder(raw.flow)
        runCurrent()
        holder.retry(); runCurrent()
        assertEquals(DetailSettingsRead.Unknown, holder.state.value.settingsRead)
        assertTrue(calls.isEmpty())
        raw.fail(); runCurrent()
        assertEquals(DetailSettingsRead.Failed, holder.state.value.settingsRead)
        assertNull(holder.state.value.completion)
        holder.retry(); runCurrent()
        assertTrue(calls.isEmpty()); assertEquals(1, raw.subscriptions.size); assertEquals(1, failures)
        holder.retryStorage(); runCurrent()
        assertEquals(DetailSettingsRead.Unknown, holder.state.value.settingsRead)
        assertEquals(2, raw.subscriptions.size)
        assertTrue(calls.isEmpty())
        raw.value(); runCurrent()
        assertTrue(holder.state.value.proven())
    }

    @Test fun cachedEqualSuccessHasNewCompletionEvenAfterRawFlowCompletesNormally() = runTest(dispatcher) {
        val holder = holder(); runCurrent()
        val first = holder.state.value
        holder.retry(); runCurrent()
        val second = holder.state.value
        assertEquals(first.detail, second.detail)
        assertNotEquals(first.request, second.request)
        assertEquals(second.request, second.completion?.context)
        assertEquals(second.request, second.retained?.context)
        assertTrue(second.proven())
        assertEquals(listOf("movie/en-US"), calls) // Genuine cache hit, not a claim of HTTP revalidation.
    }

    @Test fun pendingEqualRetryKeepsOldCompletionAndRetainedContextUntilAtomicSuccess() = runTest(dispatcher) {
        val holder = holder(); runCurrent()
        val first = holder.state.value
        val gate = CompletableDeferred<Unit>()
        movie = { gate.await(); MovieDetailDto(550, title = "Title", tagline = "Tagline", overview = "Overview") }
        repo.invalidateTitle(MediaType.Movie, 550)
        holder.retry(); runCurrent()
        val pending = holder.state.value
        assertNotEquals(first.request, pending.request)
        assertEquals(first.completion, pending.completion); assertEquals(first.retained, pending.retained)
        assertFalse(pending.proven())
        gate.complete(Unit); runCurrent()
        val completed = holder.state.value
        assertEquals(first.detail, completed.detail)
        assertEquals(pending.request, completed.completion?.context)
        assertEquals(completed.request, completed.retained?.context)
        assertTrue(completed.proven())
    }

    @Test fun primaryCatalogErrorCompletesButRetainsOriginalRealSuccessAndRetryRecovers() = runTest(dispatcher) {
        val holder = holder(); runCurrent()
        val first = holder.state.value
        repo.invalidateTitle(MediaType.Movie, 550)
        movie = { throw TmdbException(TmdbError.Network) }
        holder.retry(); runCurrent()
        val failed = holder.state.value
        assertEquals(SectionState.Error(SectionErrorKind.Offline), failed.detail)
        assertEquals(failed.detail, failed.completion?.result)
        assertEquals(failed.request, failed.completion?.context)
        assertEquals(first.retained, failed.retained)
        assertFalse(failed.proven())
        movie = { MovieDetailDto(550, title = "Title", tagline = "Tagline", overview = "Overview") }
        holder.retry(); runCurrent()
        assertTrue(holder.state.value.proven()); assertEquals(first.detail, holder.state.value.detail)
    }

    @Test fun rawFailureDuringPrimaryDropsLateSuccess() = rawFailureDuringPhase(0, false)
    @Test fun rawFailureDuringPrimaryDropsLateCatalogError() = rawFailureDuringPhase(0, true)
    @Test fun rawFailureDuringEnglishTextDropsLateSuccessAndStopsVideos() = rawFailureDuringPhase(1, false)
    @Test fun rawFailureDuringEnglishTextDropsLateOptionalErrorAndStopsVideos() = rawFailureDuringPhase(1, true)
    @Test fun rawFailureDuringEnglishVideosDropsLateSuccess() = rawFailureDuringPhase(2, false)
    @Test fun rawFailureDuringEnglishVideosDropsLateOptionalError() = rawFailureDuringPhase(2, true)

    private fun rawFailureDuringPhase(phase: Int, fail: Boolean) = runTest(dispatcher) {
        val raw = RawReader()
        val gate = CompletableDeferred<Unit>()
        suspend fun blocked() = withContext(NonCancellable) {
            gate.await()
            if (fail) throw TmdbException(TmdbError.Network)
        }
        movie = { language ->
            if ((phase == 0 && language == "hi-IN") || (phase == 1 && language == ENGLISH)) blocked()
            MovieDetailDto(550, title = language, tagline = if (language == ENGLISH) "English" else "", overview = "Local")
        }
        videos = { if (phase == 2) blocked(); VideosDto(listOf(VideoDto(key = "late", site = "YouTube"))) }
        val holder = holder(raw.flow); runCurrent()
        if (phase == 0) { raw.value(); runCurrent() }
        val previous = holder.state.value.retained
        raw.value(Settings(language = "hi-IN")); runCurrent()
        assertEquals(if (phase <= 1) 2 else 3, calls.size)
        raw.fail(); runCurrent()
        val failed = holder.state.value
        val callCount = calls.size
        assertEquals(DetailSettingsRead.Failed, failed.settingsRead)
        assertEquals(1, failures)
        assertFalse(failed.proven())
        if (phase > 0) {
            assertEquals(failed.request, failed.retained?.context)
            assertEquals("", failed.retained?.detail?.tagline)
        } else {
            assertNotNull(previous)
            assertEquals(previous, failed.retained)
            assertNotEquals(failed.request, failed.retained?.context)
        }
        generation.bump(); holder.retry()
        gate.complete(Unit); runCurrent()
        assertEquals(failed, holder.state.value)
        assertEquals(callCount, calls.size); assertEquals(1, failures)
    }

    @Test fun replacementPrimaryStartsBeforeOldUncooperativePrimaryIsReleased() = replacementBeforeRelease(0)
    @Test fun replacementStartsBeforeOldUncooperativeEnglishTextIsReleased() = replacementBeforeRelease(1)
    @Test fun replacementStartsBeforeOldUncooperativeEnglishVideosIsReleased() = replacementBeforeRelease(2)

    private fun replacementBeforeRelease(phase: Int) = runTest(dispatcher) {
        val settings = MutableStateFlow(Settings(language = "hi-IN"))
        val gate = CompletableDeferred<Unit>()
        movie = { language ->
            if ((phase == 0 && language == "hi-IN") || (phase == 1 && language == ENGLISH)) {
                withContext(NonCancellable) { gate.await() }
            }
            MovieDetailDto(550, title = language, tagline = if (phase == 1 && language == "hi-IN") "" else "Text", overview = "Text",
                videos = if (language == "es-ES") VideosDto(listOf(VideoDto(key = "new", site = "YouTube"))) else null)
        }
        videos = { if (phase == 2) withContext(NonCancellable) { gate.await() }; VideosDto() }
        val holder = holder(settings); runCurrent()
        settings.value = Settings(language = "es-ES"); runCurrent()
        assertTrue("movie/es-ES" in calls, "replacement must not join old I/O")
        val fresh = holder.state.value
        assertTrue(fresh.proven()); assertEquals("es-ES", fresh.retained?.detail?.title)
        val count = calls.size
        gate.complete(Unit); runCurrent()
        assertEquals(fresh, holder.state.value); assertEquals(count, calls.size)
    }

    @Test fun equalFirstRecoveryResubscribesAndDoesNotRetagRetainedUntilPrimaryCompletes() = recovery(Settings())
    @Test fun changedFirstRecoveryResubscribesAndUsesNewLanguageAndRegion() = recovery(Settings(language = "es-ES", region = "US"))

    private fun recovery(recovered: Settings) = runTest(dispatcher) {
        val raw = RawReader()
        val holder = holder(raw.flow); runCurrent(); raw.value(); runCurrent()
        val first = holder.state.value
        raw.fail(); runCurrent()
        holder.retryStorage(); runCurrent()
        assertEquals(2, raw.subscriptions.size)
        assertEquals(DetailSettingsRead.Unknown, holder.state.value.settingsRead)
        val gate = CompletableDeferred<Unit>()
        repo.invalidateTitle(MediaType.Movie, 550)
        movie = { gate.await(); MovieDetailDto(550, title = "Title", tagline = "Tagline", overview = "Overview", videos = VideosDto(listOf(VideoDto(key = "yt", site = "YouTube")))) }
        raw.value(recovered); runCurrent()
        val pending = holder.state.value
        assertEquals(DetailSettingsRead.Ready, pending.settingsRead)
        assertEquals(recovered.language, pending.request?.language); assertEquals(recovered.region, pending.request?.region)
        assertNotEquals(first.request, pending.request)
        assertEquals(first.retained, pending.retained); assertEquals(first.completion, pending.completion)
        assertFalse(pending.proven())
        gate.complete(Unit); runCurrent()
        assertTrue(holder.state.value.proven())
    }

    @Test fun oldReaderEpochLateFailureIsNotReportedAfterRecovery() = oldReaderAfterRetry(fail = true)
    @Test fun oldReaderEpochLateEmissionDoesNotReplaceRecoveryTuple() = oldReaderAfterRetry(fail = false)

    private fun oldReaderAfterRetry(fail: Boolean) = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var subscriptions = 0
        // A deliberately non-cooperative raw Flow bypasses flow{}'s cancellation check on emit.
        val raw = object : Flow<Settings> {
            override suspend fun collect(collector: FlowCollector<Settings>) {
                val first = ++subscriptions == 1
                if (first) withContext(NonCancellable) {
                    gate.await()
                    if (fail) throw StorageException("obsolete")
                    collector.emit(Settings(language = "hi-IN"))
                } else collector.emit(Settings())
            }
        }
        val holder = holder(raw); runCurrent()
        holder.retryStorage(); runCurrent()
        assertEquals(2, subscriptions)
        val recovered = holder.state.value
        assertTrue(recovered.proven())
        gate.complete(Unit); runCurrent()
        assertEquals(recovered, holder.state.value); assertEquals(0, failures)
        assertEquals(listOf("movie/en-US"), calls)
    }

    @Test fun cancelIsTerminalForCatalogRetryAndOnlyExplicitStorageRetryReactivates() = runTest(dispatcher) {
        val raw = RawReader()
        val holder = holder(raw.flow); runCurrent(); raw.value(); runCurrent()
        val first = holder.state.value
        holder.cancelReads()
        assertEquals(DetailSettingsRead.Unknown, holder.state.value.settingsRead)
        assertEquals(first.retained, holder.state.value.retained)
        generation.bump(); holder.retry(); runCurrent()
        assertEquals(1, calls.size)
        holder.retryStorage(); runCurrent()
        assertEquals(2, raw.subscriptions.size)
        assertEquals(1, calls.size)
        raw.value(); runCurrent()
        assertTrue(holder.state.value.proven())
    }

    @Test fun explicitCancelBlocksLatePrimarySuccess() = cancelledPhase(0, false, false)
    @Test fun explicitCancelBlocksLateEnglishTextError() = cancelledPhase(1, true, false)
    @Test fun passedScopeCancellationBlocksLateEnglishVideoSuccess() = cancelledPhase(2, false, true)
    @Test fun passedScopeCancellationBlocksLatePrimaryError() = cancelledPhase(0, true, true)
    @Test fun passedScopeCancellationBlocksLateEnglishTextSuccess() = cancelledPhase(1, false, true)
    @Test fun explicitCancelBlocksLateEnglishVideoError() = cancelledPhase(2, true, false)

    private fun cancelledPhase(phase: Int, fail: Boolean, cancelScope: Boolean) = runTest(dispatcher) {
        val owner = Job(backgroundScope.coroutineContext[Job])
        val gate = CompletableDeferred<Unit>()
        suspend fun blocked() = withContext(NonCancellable) {
            gate.await()
            if (fail) throw TmdbException(TmdbError.Network)
        }
        movie = { language ->
            if ((phase == 0 && language == "hi-IN") || (phase == 1 && language == ENGLISH)) blocked()
            MovieDetailDto(550, tagline = if (phase == 1) "" else "Text", overview = "Text")
        }
        videos = { if (phase == 2) blocked(); VideosDto() }
        val holder = holder(flowOf(Settings(language = "hi-IN")), CoroutineScope(backgroundScope.coroutineContext + owner))
        runCurrent()
        if (cancelScope) owner.cancel() else holder.cancelReads()
        val before = holder.state.value
        val count = calls.size
        generation.bump(); gate.complete(Unit); runCurrent()
        if (cancelScope) { holder.retryStorage(); holder.retry(); runCurrent() }
        assertEquals(before, holder.state.value); assertEquals(count, calls.size); assertEquals(0, failures)
    }

    @Test fun defaultViewModelScopeCancellationBlocksLateSuccess() = defaultScopeCancelled(false)
    @Test fun defaultViewModelScopeCancellationBlocksLateCatalogError() = defaultScopeCancelled(true)

    private fun defaultScopeCancelled(fail: Boolean) = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        movie = {
            withContext(NonCancellable) { gate.await(); if (fail) throw TmdbException(TmdbError.Network) }
            MovieDetailDto(550)
        }
        val holder = holder(scope = null)
        val store = ViewModelStore().also { it.put("detail", holder) }
        runCurrent()
        val before = holder.state.value
        store.clear(); generation.bump(); gate.complete(Unit); runCurrent()
        assertEquals(before, holder.state.value); assertEquals(1, calls.size)
    }

    @Test fun ownerCancellationDropsLateRawStorageFailureAndDoesNotNotify() = runTest(dispatcher) {
        val owner = Job(backgroundScope.coroutineContext[Job])
        val gate = CompletableDeferred<Unit>()
        val raw = flow<Settings> { withContext(NonCancellable) { gate.await(); throw StorageException("late") } }
        val holder = holder(raw, CoroutineScope(backgroundScope.coroutineContext + owner)); runCurrent()
        val before = holder.state.value
        owner.cancel(); gate.complete(Unit); runCurrent()
        assertEquals(before, holder.state.value); assertEquals(0, failures)
    }

    @Test fun localizedTvThenEnglishTextThenVideosKeepOneContextAndLabels() = runTest(dispatcher) {
        val english = CompletableDeferred<Unit>()
        val video = CompletableDeferred<Unit>()
        tv = { language ->
            if (language == ENGLISH) english.await()
            TvDetailDto(550, name = "TV", tagline = if (language == ENGLISH) "English" else "", overview = "Local")
        }
        videos = { video.await(); VideosDto(listOf(VideoDto(key = "yt", site = "YouTube"))) }
        val holder = holder(flowOf(Settings(language = "hi-IN")), media = MediaType.Tv); runCurrent()
        val localized = holder.state.value
        assertTrue(localized.proven()); assertEquals(MediaType.Tv, localized.request?.ref?.mediaType)
        assertEquals("", localized.retained?.detail?.tagline)
        assertEquals(listOf("tv/hi-IN", "tv/en-US"), calls)
        english.complete(Unit); runCurrent()
        assertEquals(listOf("tv/hi-IN", "tv/en-US", "videos/tv/en-US"), calls)
        video.complete(Unit); runCurrent()
        val enriched = holder.state.value
        assertEquals(localized.request, enriched.request); assertEquals(localized.request, enriched.completion?.context)
        assertEquals("English", enriched.retained?.detail?.tagline)
        assertEquals(true, enriched.retained?.detail?.taglineInEnglish)
        assertEquals("Local", enriched.retained?.detail?.overview)
        assertEquals(listOf("yt"), enriched.retained?.detail?.videos?.map { it.key })
    }

    @Test fun optionalTmdbFailuresPreservePrimaryCompletionAndStillTryVideosInOrder() = runTest(dispatcher) {
        movie = { language ->
            if (language == ENGLISH) throw TmdbException(TmdbError.Network)
            MovieDetailDto(550, tagline = "", overview = "Local")
        }
        videos = { throw TmdbException(TmdbError.Network) }
        val holder = holder(flowOf(Settings(language = "hi-IN"))); runCurrent()
        assertTrue(holder.state.value.proven())
        assertEquals("", holder.state.value.retained?.detail?.tagline)
        assertEquals(listOf("movie/hi-IN", "movie/en-US", "videos/movie/en-US"), calls)
    }

    @Test fun regionOnlyChangeRemapsMovieAndUnrelatedPreferenceDoesNotReload() = regionChange(MediaType.Movie)
    @Test fun regionOnlyChangeRemapsTvAndUnrelatedPreferenceDoesNotReload() = regionChange(MediaType.Tv)

    private fun regionChange(media: MediaType) = runTest(dispatcher) {
        movie = { MovieDetailDto(550, releaseDates = ReleaseDatesDto(listOf(
            ReleaseDateCountryDto("IN", listOf(ReleaseDateDto("U", 3))),
            ReleaseDateCountryDto("US", listOf(ReleaseDateDto("R", 3))),
        ))) }
        tv = { TvDetailDto(550, contentRatings = ContentRatingsDto(listOf(ContentRatingDto("IN", "U"), ContentRatingDto("US", "R")))) }
        val settings = MutableStateFlow(Settings())
        val holder = holder(settings, media = media); runCurrent()
        val first = holder.state.value
        assertEquals("U", first.retained?.detail?.certification)
        settings.value = settings.value.copy(sounds = true); runCurrent()
        assertEquals(first, holder.state.value)
        settings.value = settings.value.copy(region = "US"); runCurrent()
        assertEquals("R", holder.state.value.retained?.detail?.certification)
        assertNotEquals(first.request, holder.state.value.request)
        assertTrue(holder.state.value.proven()); assertEquals(1, calls.size)
    }

    @Test fun accountSubscriptionRemainsIndependentDuringStorageFailureAndReadCancellation() = runTest(dispatcher) {
        val raw = RawReader()
        val account = MutableStateFlow(LibraryState.Unknown)
        val library = object : LibraryActions {
            override fun state(ref: MediaRef): Flow<LibraryState> = account
            override fun toggleWatchlist(title: Title) = Unit
            override fun toggleFavourite(title: Title) = Unit
            override fun openRate(title: Title) = Unit
        }
        val holder = holder(raw.flow, library = library)
        assertEquals(LibraryState.Unknown, holder.accountState.value)
        backgroundScope.launch { holder.accountState.collect {} }; runCurrent()
        raw.fail(); runCurrent(); holder.cancelReads()
        val known = LibraryState(true, true, false, null, emptySet())
        account.value = known; runCurrent()
        assertEquals(known, holder.accountState.value); assertTrue(calls.isEmpty())
    }

    @Test fun activeAppGenerationMismatchReloadsVerifiedTupleAndIdleBumpNeedsOwnerEvent() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        movie = { gate.await(); MovieDetailDto(550, title = "Title") }
        val holder = holder(); runCurrent()
        val old = holder.state.value.request
        generation.bump(); gate.complete(Unit); runCurrent()
        assertEquals(2, calls.size); assertNotEquals(old, holder.state.value.request)
        assertTrue(holder.state.value.proven())
        val completed = holder.state.value
        generation.bump(); runCurrent()
        assertEquals(completed, holder.state.value) // Snapshot counter is not an idle observable.
        assertFalse(holder.state.value.proven())
        holder.retry(); runCurrent() // Root forwards its event; click-time authority also compares snapshots.
        assertTrue(holder.state.value.proven())
        holder.cancelReads(); generation.bump(); holder.retry(); runCurrent()
        assertEquals(DetailSettingsRead.Unknown, holder.state.value.settingsRead)
    }

    @Test fun synchronousReadyObserverCanCancelBeforeAnyCatalogIo() = runTest(dispatcher) {
        val raw = RawReader()
        val holder = holder(raw.flow)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.state.collect { if (it.settingsRead == DetailSettingsRead.Ready) holder.cancelReads() }
        }
        runCurrent(); raw.value(); runCurrent()
        assertEquals(DetailSettingsRead.Unknown, holder.state.value.settingsRead); assertTrue(calls.isEmpty())
    }

    @Test fun synchronousPrimaryObserverCanCancelBeforeEnglishIo() = runTest(dispatcher) {
        val raw = RawReader()
        movie = { MovieDetailDto(550) }
        val holder = holder(raw.flow)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.state.collect { if (it.completion != null) holder.cancelReads() }
        }
        runCurrent(); raw.value(Settings(language = "hi-IN")); runCurrent()
        assertEquals(listOf("movie/hi-IN"), calls)
        assertEquals(DetailSettingsRead.Unknown, holder.state.value.settingsRead)
    }

    @Test fun synchronousFailedObserverCanRevokeBeforeFailureCallback() = runTest(dispatcher) {
        val raw = RawReader()
        val holder = holder(raw.flow)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.state.collect { if (it.settingsRead == DetailSettingsRead.Failed) holder.cancelReads() }
        }
        runCurrent(); raw.fail(); runCurrent()
        assertEquals(DetailSettingsRead.Unknown, holder.state.value.settingsRead); assertEquals(0, failures)
    }

    @Test fun failureCallbackCanSynchronouslyRetryStorageWithoutLateFailedPublication() = runTest(dispatcher) {
        val raw = RawReader()
        lateinit var holder: DetailStateHolder
        holder = holder(raw.flow, onFailure = { failures++; holder.retryStorage() })
        runCurrent(); raw.fail(); runCurrent()
        assertEquals(1, failures); assertEquals(2, raw.subscriptions.size)
        assertEquals(DetailSettingsRead.Unknown, holder.state.value.settingsRead)
        raw.value(); runCurrent()
        assertTrue(holder.state.value.proven())
    }

    @Test fun synchronousRequestObserverCanCancelBeforePrimaryIo() = runTest(dispatcher) {
        val raw = RawReader()
        val holder = holder(raw.flow)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.state.collect { if (it.request != null) holder.cancelReads() }
        }
        runCurrent(); raw.value(); runCurrent()
        assertEquals(DetailSettingsRead.Unknown, holder.state.value.settingsRead)
        assertTrue(calls.isEmpty()); assertNull(holder.state.value.completion)
    }

    @Test fun synchronousPrimaryObserverRetryCannotContinueObsoleteFallback() = runTest(dispatcher) {
        val raw = RawReader()
        movie = { MovieDetailDto(550) }
        val holder = holder(raw.flow)
        var retried = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.state.collect {
                if (it.completion != null && !retried) { retried = true; holder.retry() }
            }
        }
        runCurrent(); raw.value(Settings(language = "hi-IN")); runCurrent()
        assertTrue(retried); assertTrue(holder.state.value.proven())
        assertEquals(listOf("movie/hi-IN", "movie/en-US", "videos/movie/en-US"), calls)
    }

    @Test fun appGenerationBumpedByRequestObserverIsRecheckedBeforePrimaryIo() = runTest(dispatcher) {
        val raw = RawReader()
        val holder = holder(raw.flow)
        var bumped = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.state.collect {
                if (it.request != null && !bumped) { bumped = true; generation.bump() }
            }
        }
        runCurrent(); raw.value(); runCurrent()
        assertTrue(bumped); assertTrue(holder.state.value.proven())
        assertEquals(listOf("movie/en-US"), calls)
    }

    @Test fun activeAppMismatchAfterOptionalErrorReloadsWithoutObsoleteVideoWork() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var englishReads = 0
        movie = { language ->
            if (language == ENGLISH && ++englishReads == 1) {
                gate.await(); throw TmdbException(TmdbError.Network)
            }
            MovieDetailDto(550, tagline = if (language == ENGLISH) "English" else "", overview = "Local")
        }
        val holder = holder(flowOf(Settings(language = "hi-IN"))); runCurrent()
        val first = holder.state.value.request
        generation.bump(); gate.complete(Unit); runCurrent()
        assertTrue(holder.state.value.proven()); assertNotEquals(first, holder.state.value.request)
        assertEquals(listOf("movie/hi-IN", "movie/en-US", "movie/en-US", "videos/movie/en-US"), calls)
    }

    @Test fun rawProgrammerErrorIsNotConvertedToStorageFailure() = unexpectedError(raw = true, optional = false)
    @Test fun primaryProgrammerErrorIsNotConvertedToCatalogError() = unexpectedError(raw = false, optional = false)
    @Test fun optionalProgrammerErrorIsNotSilentlyIgnored() = unexpectedError(raw = false, optional = true)

    private fun unexpectedError(raw: Boolean, optional: Boolean) = runTest(dispatcher) {
        val failure = IllegalStateException("fixture")
        val uncaught = mutableListOf<Throwable>()
        val owner = SupervisorJob(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + owner + CoroutineExceptionHandler { _, error -> uncaught += error })
        movie = { language ->
            if (!optional || language == ENGLISH) throw failure
            MovieDetailDto(550)
        }
        val source = if (raw) flow<Settings> { throw failure } else flowOf(Settings(language = if (optional) "hi-IN" else ENGLISH))
        val holder = holder(source, scope); runCurrent()
        assertEquals(listOf<Throwable>(failure), uncaught); assertEquals(0, failures)
        assertNotEquals(DetailSettingsRead.Failed, holder.state.value.settingsRead)
        assertFalse(holder.state.value.detail is SectionState.Error)
        assertTrue(calls.none { it.startsWith("videos/") })
        owner.cancel()
    }

    @Test fun rawCancellationIsNotConvertedToStorageFailure() = runTest(dispatcher) {
        val holder = holder(flow { throw CancellationException("fixture") }); runCurrent()
        assertEquals(DetailSettingsRead.Unknown, holder.state.value.settingsRead)
        assertNull(holder.state.value.completion); assertTrue(calls.isEmpty()); assertEquals(0, failures)
    }

    @Test fun synchronousRequestObserverCanRestartRawReaderBeforePrimaryIo() = runTest(dispatcher) {
        val raw = RawReader()
        val holder = holder(raw.flow)
        var restarted = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.state.collect {
                if (it.request != null && !restarted) { restarted = true; holder.retryStorage() }
            }
        }
        runCurrent(); raw.value(); runCurrent()
        assertTrue(restarted); assertEquals(2, raw.subscriptions.size)
        assertEquals(DetailSettingsRead.Unknown, holder.state.value.settingsRead)
        assertTrue(calls.isEmpty()); assertNull(holder.state.value.completion)
        raw.value(); runCurrent()
        assertTrue(holder.state.value.proven()); assertEquals(listOf("movie/en-US"), calls)
    }

    @Test fun synchronousPrimaryObserverDisposesDefaultScopeBeforeEnglishIo() = runTest(dispatcher) {
        val raw = RawReader()
        movie = { MovieDetailDto(550) }
        val holder = holder(raw.flow, scope = null)
        val store = ViewModelStore().also { it.put("detail", holder) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.state.collect { if (it.completion != null) store.clear() }
        }
        runCurrent(); raw.value(Settings(language = "hi-IN")); runCurrent()
        assertEquals(listOf("movie/hi-IN"), calls)
        assertNotNull(holder.state.value.completion)
    }
}
