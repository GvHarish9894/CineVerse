package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbRepositoryCacheTest {
    private val calls = mutableListOf<String>()
    private var onFetch: () -> Unit = {}

    private val api = object : TmdbApi by unusedTmdbApi {
        override suspend fun configuration() = ConfigurationDto().also { calls += "configuration" }
        override suspend fun movieList(list: MovieList, language: String, region: String, page: Int) =
            PageDto<MediaItemDto>(page = page).also { calls += "movie/$list/$language/$region/$page"; onFetch() }
        override suspend fun tvList(list: TvList, language: String, timezone: String?, page: Int) =
            PageDto<MediaItemDto>().also { calls += "tv/$list/$timezone" }
        override suspend fun movieDetail(id: Int, language: String, append: String, sessionId: String?) =
            MovieDetailDto(id).also { calls += "movie/$id/$append" }
        override suspend fun videos(media: MediaType, id: Int, language: String) = VideosDto().also { calls += "videos/$id" }
        override suspend fun accountList(
            bearer: UserBearer, accountObjectId: String, media: MediaType, list: AccountList, language: String, page: Int, sortBy: String,
        ) = PageDto<MediaItemDto>().also { calls += "account/$accountObjectId/$list" }
    }

    private var settings = Settings()
    private var now = 0L
    private val generation = GenerationCounter()
    private val repo = TmdbRepository(
        api, generation, settings = { settings }, timezone = { "Asia/Kolkata" },
        cache = TmdbCache(generation, maxEntries = 4, now = { now }),
    )
    private val account = Credentials(
        UserAccessToken("u"), AccountObjectId("aoid"), SessionId("s"), AccountId(1), "user", null,
    )

    @Test
    fun `key includes language, region and page`() = runTest {
        repo.movieList(MovieList.Popular)
        repo.movieList(MovieList.Popular)
        assertEquals(1, calls.size)
        settings = settings.copy(language = "hi-IN")
        repo.movieList(MovieList.Popular)
        settings = settings.copy(region = "US")
        repo.movieList(MovieList.Popular)
        repo.movieList(MovieList.Popular, page = 2)
        assertEquals(
            listOf("movie/popular/en-US/IN/1", "movie/popular/hi-IN/IN/1", "movie/popular/hi-IN/US/1", "movie/popular/hi-IN/US/2"),
            calls,
        )
    }

    @Test
    fun `timezone only for airing_today and on_the_air`() = runTest {
        repo.tvList(TvList.Popular)
        repo.tvList(TvList.AiringToday)
        repo.tvList(TvList.OnTheAir)
        assertEquals(listOf("tv/popular/null", "tv/airing_today/Asia/Kolkata", "tv/on_the_air/Asia/Kolkata"), calls)
    }

    @Test
    fun `ttl - lists 10 min, details 1 h, configuration forever`() = runTest {
        repo.movieList(MovieList.Popular)
        repo.movieDetail(550)
        repo.configuration()
        now = 10 * 60 * 1000L - 1
        repo.movieList(MovieList.Popular)
        assertEquals(3, calls.size)
        now = 10 * 60 * 1000L
        repo.movieList(MovieList.Popular)
        repo.movieDetail(550)
        assertEquals(4, calls.size)
        now = 60 * 60 * 1000L
        repo.movieDetail(550)
        repo.configuration()
        assertEquals(listOf("configuration"), calls.filter { it == "configuration" })
        assertEquals(2, calls.count { it.startsWith("movie/550") })
    }

    @Test
    fun `lru evicts the least recently used entry`() = runTest {
        (1..4).forEach { repo.movieDetail(it) }
        repo.movieDetail(1) // touch 1
        repo.movieDetail(5) // evicts 2
        calls.clear()
        repo.movieDetail(1)
        repo.movieDetail(2)
        assertEquals(listOf("movie/2/credits,videos,recommendations,reviews,release_dates"), calls)
    }

    @Test
    fun `a response from an older generation is not cached`() = runTest {
        onFetch = { repo.onAccountChanged() } // sign-in / sign-out lands while the request is in flight
        repo.movieList(MovieList.Popular)
        onFetch = {}
        repo.movieList(MovieList.Popular)
        assertEquals(2, calls.size)
        repo.movieList(MovieList.Popular)
        assertEquals(2, calls.size)
    }

    @Test
    fun `account change clears account data only and bumps the generation`() = runTest {
        repo.accountList(account, MediaType.Movie, AccountList.Watchlist)
        repo.movieDetail(550, account)
        repo.movieList(MovieList.Popular)
        val before = generation.current
        repo.onAccountChanged()
        assertNotEquals(before, generation.current)
        calls.clear()
        repo.accountList(account, MediaType.Movie, AccountList.Watchlist)
        repo.movieDetail(550, account)
        repo.movieList(MovieList.Popular)
        assertEquals(listOf("account/aoid/watchlist", "movie/550/credits,videos,recommendations,reviews,release_dates,account_states"), calls)
    }

    @Test
    fun `settings change clears everything but configuration`() = runTest {
        repo.configuration()
        repo.movieList(MovieList.Popular)
        repo.onContentSettingsChanged()
        calls.clear()
        repo.configuration()
        repo.movieList(MovieList.Popular)
        assertEquals(listOf("movie/popular/en-US/IN/1"), calls)
    }

    @Test
    fun `write invalidation hits the title and library lists`() = runTest {
        repo.movieDetail(550)
        repo.videos(MediaType.Movie, 550)
        repo.accountList(account, MediaType.Movie, AccountList.Rated)
        repo.movieDetail(551)
        val before = generation.current
        repo.invalidateTitle(MediaType.Movie, 550)
        assertEquals(before, generation.current)
        calls.clear()
        repo.movieDetail(550)
        repo.videos(MediaType.Movie, 550)
        repo.accountList(account, MediaType.Movie, AccountList.Rated)
        repo.movieDetail(551)
        assertEquals(3, calls.size)
        assertTrue(calls.none { it.startsWith("movie/551") })
    }

    @Test
    fun `image base url falls back to the constant`() {
        assertEquals(DEFAULT_IMAGE_BASE_URL, repo.imageBaseUrl())
    }
}
