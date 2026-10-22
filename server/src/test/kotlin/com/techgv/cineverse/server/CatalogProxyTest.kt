package com.techgv.cineverse.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogProxyTest {
    private val allowed = listOf(
        "/api/tmdb/configuration" to "/3/configuration",
        "/api/tmdb/genre/movie/list?language=hi-IN" to "/3/genre/movie/list",
        "/api/tmdb/genre/tv/list" to "/3/genre/tv/list",
        "/api/tmdb/trending/all/week?language=en-US&page=2" to "/3/trending/all/week",
        "/api/tmdb/trending/all/day" to "/3/trending/all/day",
        "/api/tmdb/movie/now_playing?region=IN&page=1&language=en-US" to "/3/movie/now_playing",
        "/api/tmdb/movie/top_rated" to "/3/movie/top_rated",
        "/api/tmdb/tv/airing_today?timezone=Asia/Kolkata" to "/3/tv/airing_today",
        "/api/tmdb/tv/on_the_air?timezone=America/Argentina/Buenos_Aires" to "/3/tv/on_the_air",
        "/api/tmdb/tv/popular?page=500" to "/3/tv/popular",
        "/api/tmdb/person/popular" to "/3/person/popular",
        "/api/tmdb/discover/movie?with_genres=28,53&sort_by=vote_average.desc&vote_count.gte=100&region=US" to "/3/discover/movie",
        "/api/tmdb/discover/tv?with_genres=18|35&sort_by=first_air_date.desc" to "/3/discover/tv",
        "/api/tmdb/search/multi?query=the%20matrix%20%26%20more&include_adult=false" to "/3/search/multi",
        "/api/tmdb/movie/550?append_to_response=credits,videos,recommendations,reviews,release_dates,keywords" to "/3/movie/550",
        "/api/tmdb/tv/1399?append_to_response=aggregate_credits,content_ratings,keywords" to "/3/tv/1399",
        "/api/tmdb/movie/550/videos?language=es-ES" to "/3/movie/550/videos",
        "/api/tmdb/tv/1399/videos" to "/3/tv/1399/videos",
        "/api/tmdb/movie/550/keywords" to "/3/movie/550/keywords",
        "/api/tmdb/tv/1399/season/0" to "/3/tv/1399/season/0",
        "/api/tmdb/person/287?append_to_response=combined_credits" to "/3/person/287",
    )

    @Test
    fun allowListedPathsAreForwardedWithTheReadTokenOnly() {
        val fake = FakeTmdb()
        serverTest(fake) {
            for ((path, upstream) in allowed) {
                val response = client.get(path) {
                    header(HttpHeaders.Authorization, "Bearer client-supplied")
                    header(HttpHeaders.Cookie, "cv_session=abc")
                }
                assertEquals(HttpStatusCode.OK, response.status, path)
                val recorded = fake.requests.last()
                assertEquals(upstream, recorded.path, path)
                assertEquals("https", recorded.url.protocol.name)
                assertEquals("api.themoviedb.org", recorded.url.host)
                assertEquals("Bearer $READ_TOKEN", recorded.authorization)
                assertEquals(null, recorded.cookie)
            }
            val search = fake.last(HttpMethod.Get, "/3/search/multi")
            assertEquals("the matrix & more", search.url.parameters["query"])
        }
    }

    @Test
    fun anythingElseIs404WithoutCallingTmdb() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val rejected = listOf(
                "/api/tmdb/account", // account data never through the catalog
                "/api/tmdb/movie/550/account_states",
                "/api/tmdb/movie/550/rating",
                "/api/tmdb/authentication/token/new",
                "/api/tmdb/3/movie/popular",
                "/api/tmdb/trending/movie/week",
                "/api/tmdb/movie/latest",
                "/api/tmdb/movie/abc",
                "/api/tmdb/movie/0",
                "/api/tmdb/movie/550/../../4/account",
                "/api/tmdb/movie/550%2F..%2F..%2Faccount",
                "/api/tmdb/https:%2F%2Fevil.example%2Fx",
                "/api/tmdb//evil.example/movie/popular",
                "/api/tmdb/",
                "/api/tmdb",
                "/api/tmdb/movie/popular?session_id=s1",
                "/api/tmdb/movie/popular?api_key=k",
                "/api/tmdb/movie/popular?access_token=t",
                "/api/tmdb/movie/popular?redirect_to=https://evil.example",
                "/api/tmdb/movie/popular?page=1&page=2",
                "/api/tmdb/movie/popular?page=0",
                "/api/tmdb/movie/popular?page=501",
                "/api/tmdb/movie/popular?language=english",
                "/api/tmdb/movie/popular?timezone=UTC", // timezone only on airing_today / on_the_air
                "/api/tmdb/movie/popular?include_adult=true", // include_adult only on search
                "/api/tmdb/movie/550?append_to_response=account_states",
                "/api/tmdb/movie/550?append_to_response=credits,credits",
                "/api/tmdb/movie/550?append_to_response=aggregate_credits",
                "/api/tmdb/discover/movie?sort_by=revenue.desc",
                "/api/tmdb/discover/tv?sort_by=title.asc",
                "/api/tmdb/discover/movie?with_genres=28;DROP",
                "/api/tmdb/search/multi?query=",
                "/api/tmdb/search/multi?query=" + "x".repeat(201),
                "/api/nothing-here",
            )
            for (path in rejected) {
                val response = client.get(path)
                assertEquals(HttpStatusCode.NotFound, response.status, path)
                assertEquals("""{"error":"not_allowed"}""", response.bodyAsText(), path)
            }
            for (method in listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Patch)) {
                val response = client.request("/api/tmdb/movie/popular") {
                    this.method = method
                    header(HttpHeaders.Origin, ORIGIN)
                }
                assertEquals(HttpStatusCode.NotFound, response.status, method.value)
            }
            assertTrue(fake.requests.isEmpty(), "TMDB must not be called: ${fake.requests.map { it.url }}")
        }
    }

    @Test
    fun upstreamErrorsAreSanitizedAndClassified() {
        val fake = FakeTmdb()
        serverTest(fake) {
            fake.overrides["GET /3/movie/9"] = { tmdbError(404, 34) }
            fake.overrides["GET /3/movie/7"] = { tmdbError(401, 7) }
            fake.overrides["GET /3/movie/8"] = { Reply(401, "not json") }
            fake.overrides["GET /3/movie/5"] = { Reply(503, "<html>upstream</html>") }
            fake.overrides["GET /3/movie/4"] = { Reply(429, "{}", mapOf("Retry-After" to "7")) }
            fake.overrides["GET /3/movie/3"] = { throw IOException("timeout") }

            suspend fun check(path: String, status: HttpStatusCode, body: String) {
                val response = client.get(path)
                assertEquals(status, response.status, path)
                assertEquals(body, response.bodyAsText(), path)
                assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl], path)
            }
            check("/api/tmdb/movie/9", HttpStatusCode.NotFound, """{"error":"not_found","status_code":34}""")
            check("/api/tmdb/movie/7", HttpStatusCode.InternalServerError, """{"error":"app_credential","status_code":7}""")
            check("/api/tmdb/movie/8", HttpStatusCode.InternalServerError, """{"error":"app_credential"}""")
            check("/api/tmdb/movie/5", HttpStatusCode.BadGateway, """{"error":"upstream_error"}""")
            check("/api/tmdb/movie/4", HttpStatusCode.TooManyRequests, """{"error":"rate_limited"}""")
            assertEquals("7", client.get("/api/tmdb/movie/4").headers[HttpHeaders.RetryAfter])
            check("/api/tmdb/movie/3", HttpStatusCode.GatewayTimeout, """{"error":"upstream_unavailable"}""")
            assertEquals(2, fake.calls(HttpMethod.Get, "/3/movie/3"), "one retry for an idempotent public GET")
            var failures = 0
            fake.overrides["GET /3/movie/2"] = { if (failures++ == 0) throw IOException("handshake") else Reply(200, "{}") }
            assertEquals(HttpStatusCode.OK, client.get("/api/tmdb/movie/2").status)
            // Errors are never cached: a second call reaches TMDB again.
            client.get("/api/tmdb/movie/9")
            assertEquals(2, fake.calls(HttpMethod.Get, "/3/movie/9"))
            assertFalse(client.get("/api/tmdb/movie/7").bodyAsText().contains("secret-free"))
        }
    }
}
