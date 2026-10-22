package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** §3.4 account rules of the native [TmdbRepository] (catalog + account calls sharing one cache), over MockEngine. */
class TmdbRepositoryCacheTest {
    private val scope = TestScope(StandardTestDispatcher())
    private var detailGate: CompletableDeferred<Unit>? = null
    private var watchlisted = false
    private var statesError: Exception? = null

    private val tmdb = MockTmdb(dispatcher = StandardTestDispatcher(scope.testScheduler)) { request ->
        val path = request.url.encodedPath
        when {
            path.endsWith("/account_states") -> {
                statesError?.let { throw it }
                ok("""{"id":550,"watchlist":$watchlisted}""")
            }
            path.endsWith("/watchlist") && path.startsWith("/3/account/") -> {
                watchlisted = request.bodyText.contains("\"watchlist\":true")
                ok("""{"success":true}""")
            }
            path.startsWith("/3/movie/") && path.substringAfterLast('/').all { it.isDigit() } -> {
                detailGate?.await()
                ok("""{"id":${path.substringAfterLast('/')},"title":"title@${request.url.parameters["language"]}"}""")
            }
            else -> ok("""{"page":1}""")
        }
    }
    private val calls get() = tmdb.requests.map { it.url.encodedPath }

    private val generation = GenerationCounter()
    private val repo = TmdbRepository(tmdb.api, generation, settings = { Settings() }, timezone = { "Asia/Kolkata" })
    private val account = Credentials(UserAccessToken("u"), AccountObjectId("aoid"), SessionId("s"), AccountId(1), "user", null)

    @Test
    fun accountChangeClearsAccountDataOnlyAndBumpsTheGeneration() = scope.runTest {
        repo.accountList(account, MediaType.Movie, AccountList.Watchlist)
        repo.movieDetail(550)
        repo.accountStates(MediaType.Movie, 550, account)
        repo.movieList(MovieList.Popular)
        val before = generation.current
        repo.onAccountChanged()
        assertNotEquals(before, generation.current)
        tmdb.requests.clear()
        repo.accountList(account, MediaType.Movie, AccountList.Watchlist)
        repo.movieDetail(550)
        repo.accountStates(MediaType.Movie, 550, account)
        repo.movieList(MovieList.Popular)
        // Catalog detail stays cached; account_states are always fetched fresh.
        assertEquals(listOf("/4/account/aoid/movie/watchlist", "/3/movie/550/account_states"), calls)
    }

    @Test
    fun accountStatesAreNeverCachedWhileTheCatalogDetailIs() = scope.runTest {
        repo.movieDetail(550)
        assertEquals(false, repo.accountStates(MediaType.Movie, 550, account).watchlist)
        repo.setWatchlist(account, MediaType.Movie, 550, onWatchlist = true)
        repo.movieDetail(551) // unrelated entry stays
        tmdb.requests.clear()
        assertEquals("title@en-US", repo.movieDetail(550).title)
        assertEquals(true, repo.accountStates(MediaType.Movie, 550, account).watchlist)
        // The write invalidated the title once; later reads reuse the catalog entry, states are re-read.
        repo.movieDetail(550)
        repo.accountStates(MediaType.Movie, 550, account)
        assertEquals(listOf("/3/movie/550", "/3/movie/550/account_states", "/3/movie/550/account_states"), calls)
    }

    @Test
    fun detailSucceedsWhileAccountStatesFailTitleKeptStatesFailureSurfacedSeparately() = scope.runTest {
        statesError = IOException("offline")
        assertEquals("title@en-US", repo.movieDetail(550).title)
        val failure = runCatching { repo.accountStates(MediaType.Movie, 550, account) }.exceptionOrNull()
        assertEquals(TmdbError.Network, (failure as TmdbException).error)
    }

    /**
     * Proves only that the repository never caches / re-serves a response that overlapped a write. Callers
     * that already received the overlapping (pre-write) value must still apply their own generation checks.
     */
    @Test
    fun aGetInFlightAcrossAWriteIsNotCachedNoStaleCacheReuseAfterwards() = scope.runTest {
        detailGate = CompletableDeferred()
        val stale = async { repo.movieDetail(550) } // started before the write
        runCurrent()
        repo.setWatchlist(account, MediaType.Movie, 550, onWatchlist = true) // write + invalidateTitle
        detailGate!!.complete(Unit)
        stale.await()
        detailGate = null
        tmdb.requests.clear()
        repo.movieDetail(550)
        assertEquals(listOf("/3/movie/550"), calls) // refetched, not the pre-write response
        repo.movieDetail(550)
        assertEquals(1, calls.size) // the post-write response is cached
    }

    @Test
    fun writeInvalidationHitsTheTitleAndLibraryLists() = scope.runTest {
        repo.movieDetail(550)
        repo.videos(MediaType.Movie, 550)
        repo.accountList(account, MediaType.Movie, AccountList.Rated)
        repo.movieDetail(551)
        val before = generation.current
        repo.invalidateTitle(MediaType.Movie, 550)
        assertEquals(before, generation.current)
        tmdb.requests.clear()
        repo.movieDetail(550)
        repo.videos(MediaType.Movie, 550)
        repo.accountList(account, MediaType.Movie, AccountList.Rated)
        repo.movieDetail(551)
        assertEquals(3, calls.size)
        assertTrue(calls.none { it == "/3/movie/551" })
    }

    @Test
    fun sessionExpiryIsReportedWithTheCallsCredentials() = scope.runTest {
        val expired = mutableListOf<Credentials>()
        val failing = MockTmdb(dispatcher = StandardTestDispatcher(testScheduler)) {
            ok("""{"status_code":3}""", io.ktor.http.HttpStatusCode.Unauthorized)
        }
        val repo = TmdbRepository(failing.api, generation, settings = { Settings() }, onSessionExpired = { expired += it })
        runCatching { repo.accountStates(MediaType.Movie, 550, account) }
        runCatching { repo.movieDetail(550) } // catalog call: never a sign-out
        assertEquals(listOf(account), expired)
    }
}
