package com.techgv.cineverse.ui.web

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
        val failures = mutableListOf<StorageException>()
        val rawSettings = flow {
            subscriptions++
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
            rawSettings, session, storage, { upstreamRetries++ }, { failures += it; storage.value = BrowserStorageFailure.Blocked }, library, scope)
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

    @Test fun firstSettingsFailureNeverDefaultsOrFetchesAndExplicitRecoveryUsesActualValuesButStaysUnverified() = runTest {
        val f = Fixture().apply { failSettings = true; actual = Settings(language = "fr-FR") }
        val owner = f.owner(backgroundScope)
        runCurrent()
        assertTrue(f.api.calls.isEmpty())
        assertTrue(owner.state.value.settingsFailed)
        assertEquals(0, f.activeReaders)
        runCurrent(); assertEquals(1, f.subscriptions) // No hot failure loop.
        f.failSettings = false
        owner.retryReads(); runCurrent()
        assertEquals(1, f.upstreamRetries)
        assertTrue(f.api.calls.contains("movie:7:fr-FR"))
        assertTrue(owner.holder.state.value.detail is SectionState.Content)
        assertFalse(owner.state.value.settingsFailed)
        assertTrue(owner.state.value.freshnessUnverified)
        assertEquals(SectionState.Loading, owner.state.value.section) // No provenance fabricated from the recovered DTO.
        owner.close()
    }

    @Test fun equalValueReadRecoveryExplicitlyRetriesSameHolderButNeverClaimsRefreshProvenance() = runTest {
        val f = Fixture()
        var failing = true
        f.api.movie = { id, _ -> if (failing) throw TmdbException(TmdbError.Network); MovieDetailDto(id, title = "Recovered internally") }
        val owner = f.owner(backgroundScope)
        val holder = owner.holder
        runCurrent()
        assertEquals(SectionState.Error(SectionErrorKind.Offline), owner.state.value.section)
        f.failSettings = true; f.revisions.value++; runCurrent()
        failing = false; f.failSettings = false
        owner.retryReads(); runCurrent()
        assertSame(holder, owner.holder)
        assertEquals(2, f.subscriptions)
        assertEquals(2, f.api.calls.size)
        assertEquals(1, f.upstreamRetries)
        assertTrue(holder.state.value.detail is SectionState.Content)
        assertTrue(owner.state.value.freshnessUnverified)
        assertEquals(SectionState.Error(SectionErrorKind.Offline), owner.state.value.section)
        owner.close()
    }

    @Test fun externalRuntimeRecoveryResubscribesWithActualNewValuesAndKeepsFrozenLastRealDetail() = runTest {
        val f = Fixture()
        f.api.movie = { id, language -> MovieDetailDto(id, title = "Film $language") }
        val owner = f.owner(backgroundScope)
        runCurrent()
        assertEquals("Film en-US", owner.state.value.retained?.title)
        f.failSettings = true; f.revisions.value++; runCurrent()
        f.actual = Settings(language = "es-ES"); f.failSettings = false
        f.storage.value = null; runCurrent()
        assertEquals(2, f.subscriptions)
        assertTrue(f.api.calls.contains("movie:7:es-ES"))
        assertEquals("Film en-US", owner.state.value.retained?.title)
        assertTrue(owner.state.value.freshnessUnverified)
        owner.close(); runCurrent()
        assertEquals(0, f.activeReaders)
        val subscriptions = f.subscriptions
        owner.retryReads(); f.storage.value = BrowserStorageFailure.Blocked; runCurrent(); f.storage.value = null; runCurrent()
        assertEquals(subscriptions, f.subscriptions)
        assertEquals(0, f.upstreamRetries)
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
        gate.complete(Unit); owner.retryDetail(); owner.retryReads(); owner.act(LibraryKind.WATCHLIST); runCurrent()
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
        owner.act(LibraryKind.WATCHLIST)
        assertTrue(f.library.actions.isEmpty())
        f.library.value.value = LibraryState(true, true, false, 7.5, setOf(LibraryKind.FAVOURITE)); runCurrent()
        owner.act(LibraryKind.FAVOURITE)
        assertTrue(f.library.actions.isEmpty())
        owner.act(LibraryKind.WATCHLIST); owner.act(LibraryKind.RATING)
        assertEquals(listOf(LibraryKind.WATCHLIST, LibraryKind.RATING), f.library.actions.map { it.first })
        assertTrue(f.library.actions.all { it.second.ref == MediaRef(MediaType.Movie, 7) && it.second.title == "Fixture film" })
        f.session.value = WebSessionState.Checking
        owner.act(LibraryKind.WATCHLIST)
        f.session.value = WebSessionState.Failed(TmdbError.Network)
        owner.act(LibraryKind.WATCHLIST)
        f.session.value = WebSessionState.Ready(null); f.storage.value = BrowserStorageFailure.Blocked
        owner.act(LibraryKind.WATCHLIST)
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
