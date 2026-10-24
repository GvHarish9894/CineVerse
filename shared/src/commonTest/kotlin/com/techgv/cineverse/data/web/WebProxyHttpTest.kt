package com.techgv.cineverse.data.web

import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.util.GenerationCounter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlin.test.*

internal fun sessionJson(account: String = "A", generation: String = "9007199254740993") =
    """{"signedIn":true,"accountObjectId":"$account","displayName":"$account","sessionGeneration":"$generation"}"""
internal const val GUEST = """{"signedIn":false,"accountObjectId":null,"displayName":null,"sessionGeneration":"0"}"""
internal fun mockHttp(handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData): WebProxyHttp =
    WebProxyHttp(HttpClient(MockEngine(handler)), { "csrf-current" })

class WebProxyHttpTest {
    @Test fun signedLongStringsAreExactAndNumericOrMalformedRevisionsAreRejected() {
        for (revision in listOf(Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString(), "0", "9007199254740993", "-9007199254740993")) {
            assertEquals(revision.toLong(), decodeWebSession(sessionJson(generation = revision))!!.sessionGeneration)
        }
        assertNull(decodeWebSession(GUEST))
        for (revision in listOf("1.0", "1e3", "+1", "01", "", "9223372036854775808", "-9223372036854775809", " 1")) {
            assertFailsWith<SerializationException> { decodeWebSession(sessionJson(generation = revision)) }
        }
        for (numeric in listOf("0", "9007199254740993", "null", "true")) {
            assertFailsWith<SerializationException> { decodeWebSession(sessionJson().replace("\"9007199254740993\"", numeric)) }
        }
        assertFailsWith<SerializationException> { decodeWebSession(sessionJson().replace("\"A\",\"displayName\"", "null,\"displayName\"")) }
    }

    @Test fun catalogUsesV3SubpathsAndEndpointSpecificQueriesWithoutCredentials() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = ProxyTmdbApi(mockHttp { request ->
            requests += request
            val body = when {
                request.url.encodedPath.endsWith("/1") -> """{"id":1}"""
                request.url.encodedPath.contains("/season/") -> """{"id":1,"season_number":2}"""
                else -> "{}"
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        api.configuration()
        api.genres(MediaType.Movie, "en-US")
        api.trending(TrendingWindow.Week, "en-US", 2)
        api.movieList(MovieList.Upcoming, "en-US", "IN", 3)
        api.tvList(TvList.Popular, "en-US", "UTC", 4)
        api.tvList(TvList.AiringToday, "en-US", "Asia/Kolkata", 1)
        api.popularPeople("en-US", 1)
        api.movieDetail(1, "en-US", "credits,videos", null)
        api.tvDetail(1, "en-US", "aggregate_credits", null)
        api.videos(MediaType.Tv, 1, "en-US")
        api.season(1, 2, "en-US")
        api.person(1, "en-US")
        api.searchMulti("a & b", "en-US", false, 2)
        assertEquals(listOf("configuration", "genre/movie/list", "trending/all/week", "movie/upcoming", "tv/popular", "tv/airing_today", "person/popular", "movie/1", "tv/1", "tv/1/videos", "tv/1/season/2", "person/1", "search/multi").map { "/api/tmdb/$it" }, requests.map { it.url.encodedPath })
        assertEquals(setOf("language", "region", "page"), requests[3].url.parameters.names())
        assertFalse("timezone" in requests[4].url.parameters)
        assertEquals("Asia/Kolkata", requests[5].url.parameters["timezone"])
        assertEquals("a & b", requests.last().url.parameters["query"])
        assertEquals("false", requests.last().url.parameters["include_adult"])
        for (r in requests) {
            assertEquals(HttpMethod.Get, r.method)
            assertNull(r.headers[HttpHeaders.Authorization]); assertNull(r.headers[HttpHeaders.Cookie]); assertNull(r.headers["X-CV-CSRF"])
            assertFalse("session_id" in r.url.parameters)
        }
        assertFailsWith<UnsupportedOperationException> { api.movieDetail(1, "en-US", "credits", "SECRET") }
        assertFailsWith<UnsupportedOperationException> { api.createRequestToken(RequestTokenBody("https://example.com")) }
        assertFailsWith<UnsupportedOperationException> { api.account("SECRET") }
        assertEquals(13, requests.size)
    }

    @Test fun sanitizedErrorTableAndEmptyRateLimitPreserveWriteCertainty() {
        val codes = listOf("not_allowed", "not_found", "app_credential", "rate_limited", "upstream_error", "upstream_unavailable", "not_approved", "request_token_expired", "denied", "attempt_expired", "attempt_mismatch", "attempt_superseded", "sign_in_failed", "not_signed_in", "session_expired", "csrf_origin", "csrf_token", "missing_session_binding", "stale_session", "invalid_body", "body_too_large", "json_required", "upstream_busy")
        for (code in codes) assertEquals(code, webFailure(400, """{"error":"$code","message":"SECRET"}""", null, true).code)
        val empty = webFailure(429, "", "7", true)
        assertEquals(TmdbError.RateLimited(7000), empty.error)
        assertEquals(WebWriteOutcome.DefinitelyUnsent, empty.writeOutcome)
        assertEquals(WebWriteOutcome.DefinitelyUnsent, webFailure(503, """{"error":"upstream_busy"}""", "1", true).writeOutcome)
        assertEquals(WebWriteOutcome.PossiblyLanded, webFailure(504, """{"error":"upstream_unavailable"}""", null, true).writeOutcome)
        assertEquals(WebWriteOutcome.PossiblyLanded, webFailure(502, """{"error":"upstream_error"}""", null, true).writeOutcome)
        assertEquals(TmdbError.AppCredential, webFailure(500, """{"error":"app_credential"}""", null, false).error)
        assertEquals(TmdbError.SessionExpired, webFailure(401, """{"error":"session_expired","sessionGeneration":"-9007199254740993","accountObjectId":"A"}""", null, false).error)
        val unsafe = webFailure(500, """{"error":"SECRET","status_message":"SECRET"}""", "-1", true)
        assertEquals("unknown_error", unsafe.code)
        assertNull(unsafe.retryAfterMillis)
        assertFalse(unsafe.toString().contains("SECRET")); assertNull(unsafe.cause)
    }

    @Test fun catalogRepositoryPreservesProxyExceptionIncludingRepeated429() = runTest {
        var count = 0
        val api = ProxyTmdbApi(mockHttp {
            count++
            respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "2"))
        })
        val catalog = CatalogRepository(api, GenerationCounter(), { Settings() })
        val failure = assertFailsWith<WebProxyException> { catalog.configuration() }
        assertEquals(2, count)
        assertEquals("rate_limited", failure.code); assertEquals(429, failure.httpStatus)
        assertEquals(2000, failure.retryAfterMillis); assertEquals(WebWriteOutcome.NotApplicable, failure.writeOutcome)
        val exact = webFailure(503, """{"error":"upstream_busy"}""", "1", true)
        assertSame(exact, assertFailsWith<WebProxyException> { tmdbCall(TmdbOperation.Catalog) { throw exact } })
    }

    @Test fun missingCsrfRejectsBeforeDispatchAndDoesNotExposeCookieErrors() = runTest {
        var calls = 0
        val http = WebProxyHttp(HttpClient(MockEngine { calls++; respond("{}") }), { null })
        val missing = assertFailsWith<WebProxyException> { http.call("/api/auth/logout", method = HttpMethod.Post) }
        assertEquals("csrf_token", missing.code); assertEquals(WebWriteOutcome.DefinitelyUnsent, missing.writeOutcome)
        assertEquals(0, calls)
    }

    @Test fun nativeClassificationAndCancellationRemainCompatible() = runTest {
        assertEquals(TmdbError.Network, assertFailsWith<TmdbException> { tmdbCall(TmdbOperation.Catalog) { throw IOException("offline") } }.error)
        assertEquals(TmdbError.SessionExpired, assertFailsWith<TmdbException> { tmdbCall(TmdbOperation.Session) { throw TmdbHttpException(401, 3, null) } }.error)
        assertEquals(TmdbError.AppCredential, assertFailsWith<TmdbException> { tmdbCall(TmdbOperation.Catalog) { throw TmdbHttpException(401, null, null) } }.error)
        val cancel = CancellationException("cancelled")
        assertSame(cancel, assertFailsWith<CancellationException> { tmdbCall(TmdbOperation.Catalog) { throw cancel } })
        val http = mockHttp { throw cancel }
        assertFailsWith<CancellationException> { http.call("/api/session") }
        val transport = assertFailsWith<WebProxyException> { mockHttp { throw IOException("SECRET") }.call("/api/auth/logout", method = HttpMethod.Post) }
        assertEquals(WebWriteOutcome.PossiblyLanded, transport.writeOutcome)
        assertFalse(transport.toString().contains("SECRET")); assertNull(transport.cause)
    }
}
