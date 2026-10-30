package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.web.ProxyTmdbApi
import com.techgv.cineverse.data.web.WebProxyException
import com.techgv.cineverse.data.web.WebProxyHttp
import com.techgv.cineverse.data.web.webFailure
import com.techgv.cineverse.domain.Hero
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
import kotlin.test.*

class SearchReadContractTest {
    private class Fixture(val proxy: Boolean) {
        var body = "{}"
        var onFetch: () -> Unit = {}
        val requests = mutableListOf<HttpRequestData>()
        private val engine = MockEngine { request ->
            requests += request
            onFetch()
            respond(body)
        }
        val client = if (proxy) HttpClient(engine) else tmdbHttpClient(engine, Transport.Direct(ReadToken("search-read")))
        val api: TmdbApi = if (proxy) ProxyTmdbApi(WebProxyHttp(client, { error("Public GET must not read CSRF") })) else KtorTmdbApi(client)
        val prefix = if (proxy) "/api/tmdb/" else "/3/"
    }

    @Test fun everyFacetUsesItsRealEndpointAndForwardsExactParametersWithReadTokenOnly() = runTest {
        for (proxy in listOf(false, true)) {
            val f = Fixture(proxy)
            for ((facet, path) in SearchFacet.entries.zip(listOf("multi", "movie", "tv", "person"))) {
                for ((query, language, adult, page) in listOf(
                    listOf("  Café & 東京 ?/#  ", "hi-IN", "true", "500"),
                    listOf("x".repeat(SEARCH_QUERY_MAX_LENGTH), "en", "false", "1"),
                )) {
                    f.api.search(facet, query, language, adult.toBoolean(), page.toInt())
                    val r = f.requests.last()
                    assertEquals(f.prefix + "search/$path", r.url.encodedPath)
                    assertEquals(HttpMethod.Get, r.method)
                    assertEquals(setOf("query", "language", "include_adult", "page"), r.url.parameters.names())
                    assertEquals(query, r.url.parameters["query"])
                    assertEquals(language, r.url.parameters["language"])
                    assertEquals(adult, r.url.parameters["include_adult"])
                    assertEquals(page, r.url.parameters["page"])
                    assertEquals(if (proxy) null else "Bearer search-read", r.headers[HttpHeaders.Authorization])
                    assertNull(r.headers[HttpHeaders.Cookie])
                    assertNull(r.headers["X-CV-CSRF"])
                    if (!proxy) assertEquals(TMDB_HOST, r.url.host)
                }
            }
            f.client.close()
        }
    }

    @Test fun typedSearchRejectsInvalidInputBeforeTransportOrCache() = runTest {
        for (proxy in listOf(false, true)) {
            val f = Fixture(proxy)
            var settings = Settings()
            val repo = CatalogRepository(f.api, GenerationCounter(), { settings })
            for (facet in SearchFacet.entries) {
                for (query in listOf("", " \t\n", "x".repeat(201))) {
                    assertFailsWith<IllegalArgumentException> { f.api.search(facet, query, "en-US", false, 1) }
                    assertFailsWith<IllegalArgumentException> { repo.search(facet, query) }
                }
                for (page in listOf(-1, 0, 501, Int.MAX_VALUE)) {
                    assertFailsWith<IllegalArgumentException> { f.api.search(facet, "x", "en-US", false, page) }
                    assertFailsWith<IllegalArgumentException> { repo.search(facet, "x", page) }
                }
                for (language in listOf("", "EN", "en-us", "en-US&session_id=x")) {
                    settings = settings.copy(language = language)
                    assertFailsWith<IllegalArgumentException> { f.api.search(facet, "x", language, false, 1) }
                    assertFailsWith<IllegalArgumentException> { repo.search(facet, "x") }
                }
                settings = Settings()
            }
            assertTrue(f.requests.isEmpty())
            f.client.close()
        }
    }

    @Test fun categoryProvenanceEnablesExistingHeroMappersWithoutGuessingFromNames() = runTest {
        for (proxy in listOf(false, true)) {
            val f = Fixture(proxy)
            f.body = """{"page":2,"total_pages":9,"total_results":173,"results":[{"id":7,"title":"Movie","name":"TV or person","release_date":"2024-01-01","first_air_date":"2025-02-02","known_for":[{"id":8,"media_type":"movie","title":"Known"}]}]}"""
            for (facet in SearchFacet.entries) {
                val page = f.api.search(facet, "x", "en-US", false, 2)
                assertEquals(2, page.page); assertEquals(9, page.totalPages); assertEquals(173, page.totalResults)
                assertEquals(if (facet == SearchFacet.Multi) null else facet.path, page.results.single().mediaType)
                val hero = page.results.single().toHero()
                when (facet) {
                    SearchFacet.Multi -> assertNull(hero)
                    SearchFacet.Movie -> {
                        val title = assertIs<Hero.OfTitle>(hero).title
                        assertEquals(MediaType.Movie, title.mediaType)
                        assertEquals("Movie", title.title); assertEquals("2024-01-01", title.releaseDate)
                    }
                    SearchFacet.Tv -> {
                        val title = assertIs<Hero.OfTitle>(hero).title
                        assertEquals(MediaType.Tv, title.mediaType)
                        assertEquals("TV or person", title.title); assertEquals("2025-02-02", title.releaseDate)
                    }
                    SearchFacet.Person -> {
                        val person = assertIs<Hero.OfPerson>(hero).person
                        assertEquals("TV or person", person.name)
                        assertEquals("Known", person.knownFor.single().title)
                    }
                }
            }
            f.body = """{"results":[{"id":1,"media_type":"person"},{"id":2,"media_type":"unknown"},{"id":3,"media_type":"tv"}]}"""
            for (facet in SearchFacet.entries) {
                val page = f.api.search(facet, "x", "en-US", false, 1)
                assertEquals(listOf("person", "unknown", "tv"), page.results.map { it.mediaType })
                assertEquals(0, page.totalResults) // Existing DTO default, not a proven upstream zero.
                assertEquals(0, page.totalPages)
                assertIs<Hero.OfPerson>(page.results[0].toHero())
                assertNull(page.results[1].toHero())
                assertEquals(MediaType.Tv, assertIs<Hero.OfTitle>(page.results[2].toHero()).title.mediaType)
            }
            f.client.close()
        }
    }

    @Test fun typedMultiAndLegacyMultiReuseTheExactCacheInBothDirections() = runTest {
        for (proxy in listOf(false, true)) for (legacyFirst in listOf(false, true)) {
            val f = Fixture(proxy)
            val repo = CatalogRepository(f.api, GenerationCounter(), { Settings() })
            val first = if (legacyFirst) repo.searchMulti("raw query") else repo.search(SearchFacet.Multi, "raw query")
            val second = if (legacyFirst) repo.search(SearchFacet.Multi, "raw query") else repo.searchMulti("raw query")
            assertSame(first, second)
            assertEquals(1, f.requests.size)
            assertEquals(f.prefix + "search/multi", f.requests.single().url.encodedPath)
            f.client.close()
        }
    }

    @Test fun facetCachesSeparateQueryPageLanguageAdultButNotRegionAndExpireAtListTtl() = runTest {
        for (proxy in listOf(false, true)) {
            val f = Fixture(proxy)
            var settings = Settings()
            var now = 0L
            val generation = GenerationCounter()
            val repo = CatalogRepository(f.api, generation, { settings }, cache = TmdbCache(generation, now = { now }))
            for (facet in SearchFacet.entries) {
                repo.search(facet, "query")
                repo.search(facet, "query")
            }
            assertEquals(4, f.requests.size)
            settings = settings.copy(region = "US")
            for (facet in SearchFacet.entries) repo.search(facet, "query")
            assertEquals(4, f.requests.size)
            for (facet in SearchFacet.entries) {
                repo.search(facet, "query", 2)
                repo.search(facet, " query ") // No hidden trimming/case-folding of identity.
                repo.search(facet, "Query")
            }
            assertEquals(16, f.requests.size)
            settings = settings.copy(language = "ta-IN")
            for (facet in SearchFacet.entries) repo.search(facet, "query")
            settings = settings.copy(includeAdult = true)
            for (facet in SearchFacet.entries) repo.search(facet, "query")
            assertEquals(24, f.requests.size)
            now = TmdbCache.LIST_TTL - 1
            for (facet in SearchFacet.entries) repo.search(facet, "query")
            assertEquals(24, f.requests.size)
            now++
            for (facet in SearchFacet.entries) repo.search(facet, "query")
            assertEquals(28, f.requests.size)
            repo.onContentSettingsChanged()
            for (facet in SearchFacet.entries) repo.search(facet, "query")
            assertEquals(32, f.requests.size)
            f.client.close()
        }
    }

    @Test fun inFlightInvalidationCannotResurrectFacetSearch() = runTest {
        for (proxy in listOf(false, true)) for (facet in SearchFacet.entries) {
            val f = Fixture(proxy)
            val repo = CatalogRepository(f.api, GenerationCounter(), { Settings() })
            f.onFetch = { repo.onContentSettingsChanged() }
            repo.search(facet, "query")
            f.onFetch = {}
            repo.search(facet, "query")
            repo.search(facet, "query")
            assertEquals(2, f.requests.size)
            f.client.close()
        }
    }

    @Test fun existingCustomApisRetainMultiAndExplicitlyRejectUnsupportedFacets() = runTest {
        var calls = 0
        val exact = PageDto(results = listOf(MediaItemDto(1, mediaType = "tv")))
        val api = object : UnusedTmdbApi() {
            override suspend fun searchMulti(query: String, language: String, includeAdult: Boolean, page: Int): PageDto<MediaItemDto> {
                calls++
                return exact
            }
        }
        assertSame(exact, api.search(SearchFacet.Multi, "query", "en-US", false, 1))
        for (facet in SearchFacet.entries.drop(1))
            assertFailsWith<UnsupportedOperationException> { api.search(facet, "query", "en-US", false, 1) }
        assertEquals(1, calls)
        val repo = CatalogRepository(api, GenerationCounter(), { Settings() })
        assertSame(exact, repo.searchMulti("query"))
        assertSame(exact, repo.search(SearchFacet.Multi, "query"))
        assertEquals(2, calls)
    }

    @Test fun legacyMultiKeepsUnvalidatedInputAndUnmodifiedResponse() = runTest {
        for (proxy in listOf(false, true)) {
            val f = Fixture(proxy)
            f.body = """{"results":[{"id":1}]}"""
            val page = f.api.searchMulti("", "legacy-language", false, 0)
            assertNull(page.results.single().mediaType)
            assertEquals("", f.requests.single().url.parameters["query"])
            assertEquals("legacy-language", f.requests.single().url.parameters["language"])
            assertEquals("0", f.requests.single().url.parameters["page"])
            f.client.close()
        }
    }

    @Test fun facetErrorsAreNotCachedAndPreserveWebMetadataAndCancellation() = runTest {
        val exact = webFailure(503, """{"error":"upstream_busy"}""", "2", false)
        var failure: Exception = exact
        var calls = 0
        val api = object : UnusedTmdbApi() {
            override suspend fun search(facet: SearchFacet, query: String, language: String, includeAdult: Boolean, page: Int): PageDto<MediaItemDto> {
                calls++
                throw failure
            }
        }
        val repo = CatalogRepository(api, GenerationCounter(), { Settings() })
        repeat(2) { assertSame(exact, assertFailsWith<WebProxyException> { repo.search(SearchFacet.Person, "query") }) }
        assertEquals(2, calls)
        val cancelled = CancellationException("cancelled")
        failure = cancelled
        assertSame(cancelled, assertFailsWith<CancellationException> { repo.search(SearchFacet.Person, "query") })
    }
}
