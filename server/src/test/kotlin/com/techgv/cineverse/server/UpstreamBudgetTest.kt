package com.techgv.cineverse.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** Upstream budget: per-IP catalog-miss bucket, account-read limit, single flight, global concurrency cap. */
class UpstreamBudgetTest {
    @Test
    fun catalogMissesAreBudgetedPerClientIp() {
        val fake = FakeTmdb()
        val clock = TestClock()
        serverTest(fake, testConfig(trustProxy = true), clock) {
            suspend fun get(path: String, ip: String = "203.0.113.1") = client.get(path) { header("X-Forwarded-For", ip) }

            // 30/min, burst 60: 60 unique misses pass, the 61st is refused before reaching TMDB.
            for (id in 1..60) assertEquals(HttpStatusCode.OK, get("/api/tmdb/movie/$id").status, "miss $id")
            val limited = get("/api/tmdb/movie/61")
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("""{"error":"rate_limited"}""", limited.bodyAsText())
            assertEquals("2", limited.headers[HttpHeaders.RetryAfter])
            assertEquals(60, fake.requests.size)
            // Cache hits cost nothing; another client IP has its own bucket.
            assertEquals(HttpStatusCode.OK, get("/api/tmdb/movie/1").status)
            assertEquals(HttpStatusCode.OK, get("/api/tmdb/movie/61", ip = "203.0.113.2").status)
            // The bucket refills at 30 per minute.
            clock.advanceMinutes(1)
            for (id in 62..91) assertEquals(HttpStatusCode.OK, get("/api/tmdb/movie/$id").status, "refilled miss $id")
            assertEquals(HttpStatusCode.TooManyRequests, get("/api/tmdb/movie/92").status)
        }
    }

    @Test
    fun accountReadsAreRateLimitedPerClientIp() {
        val fake = FakeTmdb()
        serverTest(fake, testConfig(accountReadsPerMinute = 3)) {
            val alice = browser()
            alice.signIn(fake, "A")
            assertEquals(HttpStatusCode.OK, alice.get("/api/account/movie/watchlist").status)
            assertEquals(HttpStatusCode.OK, alice.get("/api/account/states/movie/550").status)
            assertEquals(HttpStatusCode.OK, alice.get("/api/account/tv/rated").status)
            val limited = alice.get("/api/account/movie/watchlist")
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals(true, limited.headers[HttpHeaders.RetryAfter] != null)
            assertEquals(1, fake.calls(HttpMethod.Get, "/4/account/AOID-A/movie/watchlist"))
        }
    }

    @Test
    fun identicalConcurrentMissesShareOneUpstreamCall() {
        val fake = FakeTmdb()
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.overrides["GET /3/movie/550"] = {
            reached.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            Reply(200, """{"id":550}""")
        }
        serverTest(fake) {
            coroutineScope {
                val first = async(Dispatchers.IO) { client.get("/api/tmdb/movie/550") }
                check(reached.await(10, TimeUnit.SECONDS))
                val others = (1..7).map { async(Dispatchers.IO) { client.get("/api/tmdb/movie/550") } }
                try {
                    Thread.sleep(300) // without single flight the others would reach TMDB meanwhile
                    assertEquals(1, fake.calls(HttpMethod.Get, "/3/movie/550"))
                } finally { release.countDown() }
                (listOf(first.await()) + others.awaitAll()).forEach {
                    assertEquals(HttpStatusCode.OK, it.status)
                    assertEquals("""{"id":550}""", it.bodyAsText())
                }
            }
            assertEquals(1, fake.calls(HttpMethod.Get, "/3/movie/550"))
        }
    }

    @Test
    fun upstreamConcurrencyCapFailsFast() {
        val fake = FakeTmdb()
        val reached = CountDownLatch(2)
        val release = CountDownLatch(1)
        for (id in 1..2) {
            fake.overrides["GET /3/movie/$id"] = {
                reached.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                Reply(200, """{"id":$id}""")
            }
        }
        serverTest(fake, testConfig(upstreamConcurrency = 2)) {
            coroutineScope {
                val held = (1..2).map { id -> async(Dispatchers.IO) { client.get("/api/tmdb/movie/$id") } }
                try {
                    check(reached.await(10, TimeUnit.SECONDS))
                    val busy = client.get("/api/tmdb/movie/3")
                    assertEquals(HttpStatusCode.ServiceUnavailable, busy.status)
                    assertEquals("""{"error":"upstream_busy"}""", busy.bodyAsText())
                    assertEquals("1", busy.headers[HttpHeaders.RetryAfter])
                    assertEquals(0, fake.calls(HttpMethod.Get, "/3/movie/3"))
                } finally { release.countDown() }
                held.awaitAll().forEach { assertEquals(HttpStatusCode.OK, it.status) }
            }
            // Permits are returned; the refused request was not cached and now goes through.
            assertEquals(HttpStatusCode.OK, client.get("/api/tmdb/movie/3").status)
        }
    }
}
