package com.techgv.cineverse.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals

class CacheTest {
    @Test
    fun cacheKeyIncludesEveryQueryParameter() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val variants = listOf(
                "/api/tmdb/movie/now_playing",
                "/api/tmdb/movie/now_playing?language=en-US",
                "/api/tmdb/movie/now_playing?language=hi-IN",
                "/api/tmdb/movie/now_playing?language=hi-IN&region=IN",
                "/api/tmdb/movie/now_playing?language=hi-IN&region=US",
                "/api/tmdb/movie/now_playing?language=hi-IN&region=US&page=2",
            )
            variants.forEach { client.get(it) }
            assertEquals(variants.size, fake.calls(HttpMethod.Get, "/3/movie/now_playing"))
            // Same parameters in another order: canonical key → cache hit, identical body.
            val first = client.get("/api/tmdb/movie/now_playing?page=2&region=US&language=hi-IN").bodyAsText()
            assertEquals(variants.size, fake.calls(HttpMethod.Get, "/3/movie/now_playing"))
            assertEquals(first, client.get("/api/tmdb/movie/now_playing?language=hi-IN&region=US&page=2").bodyAsText())

            listOf("Asia/Kolkata", "Europe/London").forEach { client.get("/api/tmdb/tv/airing_today?timezone=$it") }
            assertEquals(2, fake.calls(HttpMethod.Get, "/3/tv/airing_today"))
            listOf("false", "true").forEach { client.get("/api/tmdb/search/multi?query=x&include_adult=$it") }
            client.get("/api/tmdb/search/multi?query=y&include_adult=false")
            assertEquals(3, fake.calls(HttpMethod.Get, "/3/search/multi"))
            client.get("/api/tmdb/movie/550?append_to_response=credits")
            client.get("/api/tmdb/movie/550?append_to_response=videos")
            assertEquals(2, fake.calls(HttpMethod.Get, "/3/movie/550"))
        }
    }

    @Test
    fun ttlPerEndpointClassAndPublicCacheControl() {
        val fake = FakeTmdb()
        val clock = TestClock()
        serverTest(fake, clock = clock) {
            assertEquals("public, max-age=600", client.get("/api/tmdb/trending/all/week").headers[HttpHeaders.CacheControl])
            assertEquals("public, max-age=3600", client.get("/api/tmdb/movie/550").headers[HttpHeaders.CacheControl])
            assertEquals("public, max-age=86400", client.get("/api/tmdb/configuration").headers[HttpHeaders.CacheControl])
            assertEquals("public, max-age=86400", client.get("/api/tmdb/genre/tv/list").headers[HttpHeaders.CacheControl])

            clock.advanceMinutes(9)
            listOf("/api/tmdb/trending/all/week", "/api/tmdb/movie/550", "/api/tmdb/configuration").forEach { client.get(it) }
            assertEquals(1, fake.calls(HttpMethod.Get, "/3/trending/all/week"))

            clock.advanceMinutes(2) // 11 min: lists expire, details don't
            listOf("/api/tmdb/trending/all/week", "/api/tmdb/movie/550").forEach { client.get(it) }
            assertEquals(2, fake.calls(HttpMethod.Get, "/3/trending/all/week"))
            assertEquals(1, fake.calls(HttpMethod.Get, "/3/movie/550"))

            clock.advanceMinutes(50) // 61 min: details expire, configuration/genres live for the process
            listOf("/api/tmdb/movie/550", "/api/tmdb/configuration", "/api/tmdb/genre/tv/list").forEach { client.get(it) }
            assertEquals(2, fake.calls(HttpMethod.Get, "/3/movie/550"))
            clock.advanceMinutes(60L * 24 * 365)
            client.get("/api/tmdb/configuration")
            assertEquals(1, fake.calls(HttpMethod.Get, "/3/configuration"))
            assertEquals(1, fake.calls(HttpMethod.Get, "/3/genre/tv/list"))
        }
    }

    @Test
    fun privateResponsesAreNeverCachedNorPublic() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val alice = browser()
            alice.signIn(fake, "A")
            repeat(2) {
                val list = alice.get("/api/account/movie/watchlist?page=1")
                assertEquals("private, no-store", list.headers[HttpHeaders.CacheControl])
                val states = alice.get("/api/account/states/movie/550")
                assertEquals("private, no-store", states.headers[HttpHeaders.CacheControl])
                assertEquals("private, no-store", alice.get("/api/session").headers[HttpHeaders.CacheControl])
            }
            assertEquals(2, fake.calls(HttpMethod.Get, "/4/account/AOID-A/movie/watchlist"))
            assertEquals(2, fake.calls(HttpMethod.Get, "/3/movie/550/account_states"))

            // A public detail fetch afterwards is a different key and carries no account data.
            val detail = alice.get("/api/tmdb/movie/550")
            assertEquals("public, max-age=3600", detail.headers[HttpHeaders.CacheControl])
            assertEquals(null, fake.last(HttpMethod.Get, "/3/movie/550").url.parameters["session_id"])
            val anonymous = client.get("/api/tmdb/movie/550").bodyAsText()
            assertEquals(detail.bodyAsText(), anonymous)
            assertEquals(1, fake.calls(HttpMethod.Get, "/3/movie/550"))
        }
    }
}
