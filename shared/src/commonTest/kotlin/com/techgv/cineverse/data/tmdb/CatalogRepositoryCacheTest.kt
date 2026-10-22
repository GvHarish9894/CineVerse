package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.util.GenerationCounter
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** §3.4 keys, TTLs, LRU and generation rules of the catalog, over the real Ktor client (MockEngine). */
class CatalogRepositoryCacheTest {
    private var onFetch: () -> Unit = {}
    private val tmdb = MockTmdb { request ->
        onFetch()
        val path = request.url.encodedPath
        when {
            path.startsWith("/3/movie/") && path.count { it == '/' } == 3 && path.last().isDigit() ->
                ok("""{"id":${path.substringAfterLast('/')},"title":"title@${request.url.parameters["language"]}"}""")
            path.startsWith("/3/genre/") -> ok("""{"genres":[{"id":16,"name":""},{"id":18,"name":"Drama"}]}""")
            path.startsWith("/3/person/") -> ok("""{"id":${path.substringAfterLast('/')}}""")
            else -> ok("""{"page":${request.url.parameters["page"] ?: 1}}""")
        }
    }
    private val calls get() = tmdb.requests.map { it.pathAndQuery }

    private var settings = Settings()
    private var now = 0L
    private val generation = GenerationCounter()
    private val repo = CatalogRepository(
        tmdb.api, generation, settings = { settings }, timezone = { "Asia/Kolkata" },
        cache = TmdbCache(generation, maxEntries = 4, now = { now }),
    )

    private fun detail(id: Int, language: String = "en-US") = "/3/movie/$id?language=$language&append_to_response=$MOVIE_APPEND"

    @Test
    fun keyIncludesLanguageRegionAndPage() = runTest {
        repo.movieList(MovieList.Popular)
        repo.movieList(MovieList.Popular)
        assertEquals(1, calls.size)
        settings = settings.copy(language = "hi-IN")
        repo.movieList(MovieList.Popular)
        settings = settings.copy(region = "US")
        repo.movieList(MovieList.Popular)
        repo.movieList(MovieList.Popular, page = 2)
        assertEquals(
            listOf(
                "/3/movie/popular?language=en-US&region=IN&page=1",
                "/3/movie/popular?language=hi-IN&region=IN&page=1",
                "/3/movie/popular?language=hi-IN&region=US&page=1",
                "/3/movie/popular?language=hi-IN&region=US&page=2",
            ),
            calls,
        )
    }

    @Test
    fun timezoneOnlyForAiringTodayAndOnTheAir() = runTest {
        repo.tvList(TvList.Popular)
        repo.tvList(TvList.AiringToday)
        repo.tvList(TvList.OnTheAir)
        assertEquals(listOf(null, "Asia/Kolkata", "Asia/Kolkata"), tmdb.requests.map { it.url.parameters["timezone"] })
    }

    @Test
    fun ttlListsTenMinutesDetailsOneHourConfigurationForever() = runTest {
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
        assertEquals(1, calls.count { it == "/3/configuration" })
        assertEquals(2, calls.count { it.startsWith("/3/movie/550") })
    }

    @Test
    fun lruEvictsTheLeastRecentlyUsedEntry() = runTest {
        (1..4).forEach { repo.movieDetail(it) }
        repo.movieDetail(1) // touch 1
        repo.movieDetail(5) // evicts 2
        tmdb.requests.clear()
        repo.movieDetail(1)
        repo.movieDetail(2)
        assertEquals(listOf(detail(2)), calls)
    }

    @Test
    fun aResponseFromAnOlderGenerationIsNotCached() = runTest {
        onFetch = { repo.onAccountChanged() } // sign-in / sign-out lands while the request is in flight
        repo.movieList(MovieList.Popular)
        onFetch = {}
        repo.movieList(MovieList.Popular)
        assertEquals(2, calls.size)
        repo.movieList(MovieList.Popular)
        assertEquals(2, calls.size)
    }

    @Test
    fun explicitLanguageIsCachedSeparatelyAndLeavesSettingsAlone() = runTest {
        settings = settings.copy(language = "hi-IN")
        repo.movieDetail(550)
        repo.movieDetail(550, language = "en-US")
        repo.person(31)
        repo.person(31, language = "en-US")
        repo.movieDetail(550)
        repo.movieDetail(550, language = "en-US")
        repo.person(31)
        repo.person(31, language = "en-US")
        assertEquals(
            listOf(
                detail(550, "hi-IN"), detail(550, "en-US"),
                "/3/person/31?language=hi-IN&append_to_response=combined_credits",
                "/3/person/31?language=en-US&append_to_response=combined_credits",
            ),
            calls,
        )
        assertEquals("hi-IN", settings.language)
        assertEquals("title@hi-IN", repo.movieDetail(550).title)
    }

    @Test
    fun settingsChangeClearsEverythingButConfiguration() = runTest {
        repo.configuration()
        repo.movieList(MovieList.Popular)
        repo.onContentSettingsChanged()
        tmdb.requests.clear()
        repo.configuration()
        repo.movieList(MovieList.Popular)
        assertEquals(listOf("/3/movie/popular?language=en-US&region=IN&page=1"), calls)
    }

    @Test
    fun titleInvalidationKeepsTheGenerationAndOtherTitles() = runTest {
        repo.movieDetail(550)
        repo.videos(MediaType.Movie, 550)
        repo.movieDetail(551)
        val before = generation.current
        repo.invalidateTitle(MediaType.Movie, 550)
        assertEquals(before, generation.current)
        tmdb.requests.clear()
        repo.movieDetail(550)
        repo.videos(MediaType.Movie, 550)
        repo.movieDetail(551)
        assertEquals(listOf(detail(550), "/3/movie/550/videos?language=en-US"), calls)
    }

    @Test
    fun genreNamesDropBlankUntranslatedNames() = runTest {
        assertEquals(mapOf(18 to "Drama"), repo.genreNames())
    }

    @Test
    fun imageBaseUrlFallsBackToTheConstantThenUsesTheLoadedConfiguration() = runTest {
        assertEquals(DEFAULT_IMAGE_BASE_URL, repo.imageBaseUrl())
        tmdb.handler = { ok("""{"images":{"secure_base_url":"https://img.example/"}}""") }
        repo.configuration()
        assertEquals("https://img.example/", repo.imageBaseUrl())
    }

    @Test
    fun failuresAreNotCached() = runTest {
        tmdb.handler = { ok("""{"status_code":34}""", HttpStatusCode.NotFound) }
        assertEquals(TmdbError.NotFound, runCatching { repo.movieDetail(1) }.exceptionOrNull().let { (it as TmdbException).error })
        tmdb.handler = { ok("""{"id":1}""") }
        repo.movieDetail(1)
        assertEquals(2, calls.size)
    }

    private companion object {
        const val MOVIE_APPEND = "credits%2Cvideos%2Crecommendations%2Creviews%2Crelease_dates"
    }
}
