package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.web.ProxyTmdbApi
import com.techgv.cineverse.data.web.WebProxyException
import com.techgv.cineverse.data.web.WebProxyHttp
import com.techgv.cineverse.data.web.webFailure
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.util.GenerationCounter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlin.test.*

/** The same portable endpoint/normalization checks exercise native and proxy adapters on every target. */
class CatalogExtensionsTest {
    private class Fixture(val proxy: Boolean) {
        var body = "{}"
        var onFetch: () -> Unit = {}
        val requests = mutableListOf<HttpRequestData>()
        private val engine = MockEngine { request ->
            requests += request
            onFetch()
            respond(body)
        }
        val client = if (proxy) HttpClient(engine) else tmdbHttpClient(engine, Transport.Direct(ReadToken("catalog-read")))
        val api: TmdbApi = if (proxy) ProxyTmdbApi(WebProxyHttp(client, { error("GET must not read CSRF") })) else KtorTmdbApi(client)
        val prefix = if (proxy) "/api/tmdb/" else "/3/"
    }

    @Test fun discoverEverySortAndMediaUsesOnlySupportedCanonicalParameters() = runTest {
        for (proxy in listOf(false, true)) {
            val f = Fixture(proxy)
            for (media in MediaType.entries) {
                val expectedSorts = if (media == MediaType.Movie)
                    listOf("popularity.desc", "vote_average.desc", "primary_release_date.desc", "title.asc")
                else listOf("popularity.desc", "vote_average.desc", "first_air_date.desc", "name.asc")
                for ((sort, expected) in DiscoverSort.entries.zip(expectedSorts)) {
                    f.api.discover(media, listOf(18, 16, 18, 999999), sort, "hi-IN", "IN", 500)
                    val r = f.requests.last()
                    assertEquals(f.prefix + "discover/$media", r.url.encodedPath)
                    assertEquals(HttpMethod.Get, r.method)
                    assertEquals(expected, r.url.parameters["sort_by"])
                    assertEquals("16,18,999999", r.url.parameters["with_genres"])
                    assertEquals("hi-IN", r.url.parameters["language"])
                    assertEquals("500", r.url.parameters["page"])
                    val keys = setOf("language", "page", "with_genres", "sort_by") + if (media == MediaType.Movie) setOf("region") else emptySet()
                    assertEquals(keys, r.url.parameters.names())
                    assertEquals(if (media == MediaType.Movie) "IN" else null, r.url.parameters["region"])
                    assertEquals(if (proxy) null else "Bearer catalog-read", r.headers[HttpHeaders.Authorization])
                    assertNull(r.headers[HttpHeaders.Cookie])
                    assertNull(r.headers["X-CV-CSRF"])
                    if (!proxy) assertEquals(TMDB_HOST, r.url.host)
                }
            }
            f.api.discover(MediaType.Movie, emptyList(), DiscoverSort.Popularity, "en", null, 1)
            assertEquals(setOf("language", "page", "sort_by"), f.requests.last().url.parameters.names())
            f.client.close()
        }
    }

    @Test fun discoverValidatesBoundaryBeforeDispatch() = runTest {
        for (proxy in listOf(false, true)) {
            val f = Fixture(proxy)
            suspend fun discover(ids: List<Int> = emptyList(), language: String = "en-US", region: String? = "IN", page: Int = 1) =
                f.api.discover(MediaType.Movie, ids, DiscoverSort.Popularity, language, region, page)
            for (page in listOf(0, -1, 501, Int.MAX_VALUE)) assertFailsWith<IllegalArgumentException> { discover(page = page) }
            for (ids in listOf(listOf(0), listOf(-1), listOf(1000000), (1..21).toList()))
                assertFailsWith<IllegalArgumentException> { discover(ids) }
            for (language in listOf("", "EN", "en-us", "en-US&session_id=x"))
                assertFailsWith<IllegalArgumentException> { discover(language = language) }
            for (region in listOf("", "in", "USA")) assertFailsWith<IllegalArgumentException> { discover(region = region) }
            for (id in listOf(0, -1, Int.MIN_VALUE)) assertFailsWith<IllegalArgumentException> { f.api.keywords(MediaType.Movie, id) }
            assertTrue(f.requests.isEmpty())
            discover((1..20).toList() + (1..20).toList()) // limit counts unique IDs
            f.api.discover(MediaType.Tv, emptyList(), DiscoverSort.Title, "en-US", "ignored", 1)
            assertNull(f.requests.last().url.parameters["region"])
            f.client.close()
        }
    }

    @Test fun keywordsNormalizeBothShapesAndPreserveNullEmptyAndNames() = runTest {
        for (proxy in listOf(false, true)) {
            val f = Fixture(proxy)
            for (media in MediaType.entries) {
                val field = if (media == MediaType.Movie) "keywords" else "results"
                f.body = """{"id":2147483647,"$field":[{"id":73,"name":"  Café & 東京  "},{"id":99,"name":""}]}"""
                assertEquals(KeywordsDto(Int.MAX_VALUE, listOf(KeywordDto(73, "  Café & 東京  "), KeywordDto(99, ""))), f.api.keywords(media, Int.MAX_VALUE))
                val r = f.requests.last()
                assertEquals(f.prefix + "$media/2147483647/keywords", r.url.encodedPath)
                assertTrue(r.url.parameters.isEmpty())
                assertEquals(HttpMethod.Get, r.method)
                assertEquals(if (proxy) null else "Bearer catalog-read", r.headers[HttpHeaders.Authorization])
                assertNull(r.headers[HttpHeaders.Cookie]); assertNull(r.headers["X-CV-CSRF"])
                f.body = """{"id":1,"$field":[]}"""
                assertEquals(emptyList(), f.api.keywords(media, 1).keywords)
                f.body = """{"id":1,"$field":null}"""
                assertNull(f.api.keywords(media, 1).keywords)
            }
            f.client.close()
        }
    }

    @Test fun keywordsRejectMissingWrongShapeAndMalformedItems() = runTest {
        for (proxy in listOf(false, true)) {
            val f = Fixture(proxy)
            for (media in MediaType.entries) {
                val field = if (media == MediaType.Movie) "keywords" else "results"
                val wrong = if (media == MediaType.Movie) "results" else "keywords"
                for (body in listOf(
                    "{}", """{"id":1}""", """{"id":1,"$wrong":[]}""", """{"$field":[]}""",
                    """{"id":null,"$field":[]}""", """{"id":1,"$field":{}}""",
                    """{"id":1,"$field":[null]}""", """{"id":1,"$field":[{}]}""",
                    """{"id":1,"$field":[{"id":1}]}""", """{"id":1,"$field":[{"name":"x"}]}""",
                    """{"id":1,"$field":[{"id":1,"name":null}]}""",
                    """{"id":1,"$field":[{"id":"bad","name":"x"}]}""",
                    """{"id":1,"$field":[{"id":true,"name":"x"}]}""",
                    """{"id":1,"$field":[{"id":1,"name":{}}]}""", "not json",
                )) {
                    f.body = body
                    assertFailsWith<SerializationException>("$proxy/$media: $body") { f.api.keywords(media, 1) }
                }
            }
            f.client.close()
        }
    }

    @Test fun discoverCacheSeparatesAllDimensionsButCanonicalizesGenresAndIgnoresAdult() = runTest {
        val f = Fixture(false)
        var settings = Settings()
        val repo = CatalogRepository(f.api, GenerationCounter(), { settings })
        repo.discover(MediaType.Movie, listOf(18, 16, 18))
        repo.discover(MediaType.Movie, listOf(16, 18))
        settings = settings.copy(includeAdult = !settings.includeAdult)
        repo.discover(MediaType.Movie, listOf(18, 16))
        assertEquals(1, f.requests.size)
        repo.discover(MediaType.Movie, listOf(16))
        repo.discover(MediaType.Movie, listOf(18, 16), page = 2)
        for (sort in DiscoverSort.entries.drop(1)) repo.discover(MediaType.Movie, listOf(18, 16), sort)
        settings = settings.copy(language = "hi-IN")
        repo.discover(MediaType.Movie, listOf(18, 16))
        settings = settings.copy(region = "US")
        repo.discover(MediaType.Movie, listOf(18, 16))
        repo.discover(MediaType.Tv, listOf(18, 16))
        settings = settings.copy(region = "GB")
        repo.discover(MediaType.Tv, listOf(16, 18))
        assertEquals(9, f.requests.size)
        assertNull(f.requests.last().url.parameters["region"])
        f.client.close()
    }

    @Test fun keywordCacheKeysUseMediaAndIdNotLanguageAndExpireAtDetailTtl() = runTest {
        val f = Fixture(false)
        f.body = """{"id":1,"keywords":[],"results":[]}"""
        var settings = Settings()
        var now = 0L
        val generation = GenerationCounter()
        val repo = CatalogRepository(f.api, generation, { settings }, cache = TmdbCache(generation, now = { now }))
        repo.keywords(MediaType.Movie, 1)
        settings = settings.copy(language = "hi-IN", region = "US")
        repo.keywords(MediaType.Movie, 1)
        repo.keywords(MediaType.Tv, 1)
        repo.keywords(MediaType.Movie, 2)
        assertEquals(3, f.requests.size)
        now = TmdbCache.DETAIL_TTL - 1
        repo.keywords(MediaType.Movie, 1)
        assertEquals(3, f.requests.size)
        now++
        repo.keywords(MediaType.Movie, 1)
        assertEquals(4, f.requests.size)
        repo.keywords(MediaType.Tv, 1) // refresh the expired TV entry before testing movie-only invalidation
        assertEquals(5, f.requests.size)
        repo.invalidateTitle(MediaType.Movie, 1)
        repo.keywords(MediaType.Movie, 1)
        repo.keywords(MediaType.Tv, 1)
        assertEquals(6, f.requests.size)
        f.client.close()
    }

    @Test fun discoverUsesListTtl() = runTest {
        val f = Fixture(false)
        var now = 0L
        val generation = GenerationCounter()
        val repo = CatalogRepository(f.api, generation, { Settings() }, cache = TmdbCache(generation, now = { now }))
        repo.discover(MediaType.Movie)
        now = TmdbCache.LIST_TTL - 1
        repo.discover(MediaType.Movie)
        assertEquals(1, f.requests.size)
        now++
        repo.discover(MediaType.Movie)
        assertEquals(2, f.requests.size)
        f.client.close()
    }

    @Test fun inFlightInvalidationCannotResurrectDiscoverOrKeywords() = runTest {
        for (proxy in listOf(false, true)) for (keywords in listOf(false, true)) for (generationChange in listOf(false, true)) {
            val f = Fixture(proxy)
            f.body = """{"id":1,"keywords":[]}"""
            val repo = CatalogRepository(f.api, GenerationCounter(), { Settings() })
            suspend fun load() { if (keywords) repo.keywords(MediaType.Movie, 1) else repo.discover(MediaType.Movie) }
            f.onFetch = { if (generationChange) repo.onContentSettingsChanged() else repo.invalidateTitle(MediaType.Movie, 1) }
            load()
            f.onFetch = {}
            load()
            load()
            assertEquals(2, f.requests.size)
            repo.onContentSettingsChanged()
            load()
            assertEquals(3, f.requests.size)
            f.client.close()
        }
    }

    @Test fun extensionErrorsAreNotCachedAndKeepSubtypeMetadataAndCancellation() = runTest {
        val exact = webFailure(503, """{"error":"upstream_busy"}""", "1", false)
        val cancel = CancellationException("cancelled")
        var failure: Exception = exact
        val api = object : UnusedTmdbApi() {
            override suspend fun discover(media: MediaType, genreIds: List<Int>, sort: DiscoverSort, language: String, region: String?, page: Int): PageDto<MediaItemDto> = throw failure
            override suspend fun keywords(media: MediaType, id: Int): KeywordsDto = throw failure
        }
        val repo = CatalogRepository(api, GenerationCounter(), { Settings() })
        repeat(2) {
            assertSame(exact, assertFailsWith<WebProxyException> { repo.discover(MediaType.Movie) })
            assertSame(exact, assertFailsWith<WebProxyException> { repo.keywords(MediaType.Tv, 1) })
        }
        failure = cancel
        assertSame(cancel, assertFailsWith<CancellationException> { repo.discover(MediaType.Movie) })
        assertSame(cancel, assertFailsWith<CancellationException> { repo.keywords(MediaType.Tv, 1) })
        val f = Fixture(false)
        val malformedRepo = CatalogRepository(f.api, GenerationCounter(), { Settings() })
        assertEquals(TmdbError.Malformed, assertFailsWith<TmdbException> { malformedRepo.keywords(MediaType.Movie, 1) }.error)
        f.body = """{"id":1,"keywords":[]}"""
        assertEquals(emptyList(), malformedRepo.keywords(MediaType.Movie, 1).keywords)
        assertEquals(2, f.requests.size)
        f.client.close()
    }
}
