package com.techgv.cineverse.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class HardeningTest {
    @Test
    fun authRoutesAreRateLimitedPerClientIp() {
        val fake = FakeTmdb()
        serverTest(fake, testConfig(authPerMinute = 3, trustProxy = true)) {
            val first = browser()
            first.get("/api/session")
            val ip1 = mapOf("X-Forwarded-For" to "203.0.113.1")
            repeat(3) { assertEquals(HttpStatusCode.OK, first.send(HttpMethod.Post, "/api/auth/start", headers = ip1).status) }
            val limited = first.send(HttpMethod.Post, "/api/auth/start", headers = ip1)
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertTrue(limited.headers[HttpHeaders.RetryAfter] != null)
            assertEquals(3, fake.calls(HttpMethod.Post, "/4/auth/request_token"))
            // Another client IP has its own budget.
            val other = browser()
            other.get("/api/session")
            assertEquals(HttpStatusCode.OK, other.send(HttpMethod.Post, "/api/auth/start", headers = mapOf("X-Forwarded-For" to "203.0.113.2")).status)
        }
    }

    @Test
    fun mutationsAreRateLimitedPerSession() {
        val fake = FakeTmdb()
        serverTest(fake, testConfig(mutationPerMinute = 2)) {
            val alice = browser()
            val a = alice.signIn(fake, "A")
            val body = """{"media_type":"movie","media_id":550,"watchlist":true}"""
            repeat(2) { assertEquals(HttpStatusCode.Created, alice.send(HttpMethod.Post, "/api/account/watchlist", body, session = a).status) }
            assertEquals(HttpStatusCode.TooManyRequests, alice.send(HttpMethod.Post, "/api/account/watchlist", body, session = a).status)
            assertEquals(2, fake.calls(HttpMethod.Post, "/3/account/101/watchlist"))
            // Same IP, other session: the per-session budget is separate (per-IP budget is 2× larger).
            val bob = browser()
            val b = bob.signIn(fake, "B")
            assertEquals(HttpStatusCode.Created, bob.send(HttpMethod.Post, "/api/account/watchlist", body, session = b).status)
            // Catalog reads are not throttled by the mutation budget.
            assertEquals(HttpStatusCode.OK, alice.get("/api/tmdb/configuration").status)
        }
    }

    @Test
    fun securityHeadersAndSameOriginDefault() {
        serverTest {
            val api = client.get("/api/session") { header(HttpHeaders.Origin, "https://evil.example") }
            assertEquals("nosniff", api.headers["X-Content-Type-Options"])
            assertEquals("DENY", api.headers["X-Frame-Options"])
            assertEquals("no-referrer", api.headers["Referrer-Policy"])
            assertEquals("default-src 'none'; frame-ancestors 'none'", api.headers["Content-Security-Policy"])
            assertEquals("max-age=31536000", api.headers["Strict-Transport-Security"])
            assertNull(api.headers[HttpHeaders.AccessControlAllowOrigin], "no CORS by default")
            assertNull(api.headers[HttpHeaders.AccessControlAllowCredentials])
        }
    }

    @Test
    fun corsOnlyForTheConfiguredDevOrigin() {
        serverTest(config = testConfig(corsOrigin = "http://localhost:8081")) {
            val preflight = client.options("/api/account/watchlist") {
                header(HttpHeaders.Origin, "http://localhost:8081")
                header(HttpHeaders.AccessControlRequestMethod, "POST")
                header(HttpHeaders.AccessControlRequestHeaders, "content-type,x-cv-csrf,x-cv-expected-session-generation,x-cv-expected-account")
            }
            assertEquals(HttpStatusCode.OK, preflight.status)
            assertEquals("http://localhost:8081", preflight.headers[HttpHeaders.AccessControlAllowOrigin])
            assertEquals("true", preflight.headers[HttpHeaders.AccessControlAllowCredentials])
            val evil = client.get("/api/session") { header(HttpHeaders.Origin, "https://evil.example") }
            assertNull(evil.headers[HttpHeaders.AccessControlAllowOrigin])
        }
    }

    @Test
    fun webBundleServedUnderRootWithSpaFallback() {
        val dir = Files.createTempDirectory("web").toFile()
        dir.resolve("index.html").writeText("<html>cineverse</html>")
        dir.resolve("app.wasm").writeBytes(byteArrayOf(0, 97, 115, 109))
        serverTest(config = testConfig(staticDir = dir.path)) {
            assertEquals("<html>cineverse</html>", client.get("/").bodyAsText())
            assertEquals("<html>cineverse</html>", client.get("/movie/550").bodyAsText())
            val wasm = client.get("/app.wasm")
            assertEquals(HttpStatusCode.OK, wasm.status)
            assertEquals("application/wasm", wasm.headers[HttpHeaders.ContentType])
            assertEquals("strict-origin-when-cross-origin", wasm.headers["Referrer-Policy"])
            assertEquals("frame-ancestors 'none'", wasm.headers["Content-Security-Policy"])
            val unknownApi = client.get("/api/unknown")
            assertEquals(HttpStatusCode.NotFound, unknownApi.status)
            assertEquals("""{"error":"not_allowed"}""", unknownApi.bodyAsText())
            assertEquals("ok", client.get("/healthz").bodyAsText())
        }
    }

    @Test
    fun configurationIsValidated() {
        val env = mapOf("TMDB_READ_ACCESS_TOKEN" to READ_TOKEN)
        val local = ServerConfig.fromEnv(env)
        assertEquals("http://localhost:8080", local.publicOrigin)
        assertTrue(local.cookieSecure)
        assertFalse(local.toString().contains(READ_TOKEN))
        val custom = ServerConfig.fromEnv(
            env + mapOf("PUBLIC_ORIGIN" to "https://cv.example", "PORT" to "9000", "SESSION_TTL" to "12h", "COOKIE_SECURE" to "true"),
        )
        assertEquals(9000, custom.port)
        assertEquals(12.hours, custom.sessionTtl)
        assertEquals(false, ServerConfig.fromEnv(env + ("COOKIE_SECURE" to "false")).cookieSecure)

        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(emptyMap()) }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnv(env + mapOf("PUBLIC_ORIGIN" to "https://cv.example", "COOKIE_SECURE" to "false"))
        }
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(env + ("PUBLIC_ORIGIN" to "https://cv.example/")) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(env + ("PUBLIC_ORIGIN" to "cv.example")) }
        val error = assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(env + ("SESSION_TTL" to "-1h")) }
        assertFalse(error.message.orEmpty().contains(READ_TOKEN))
    }

    @Test
    fun insecureCookiesOnlyForLocalDev() {
        val fake = FakeTmdb()
        val config = ServerConfig(readToken = READ_TOKEN, publicOrigin = "http://localhost:8080", cookieSecure = false)
        serverTest(fake, config) {
            val browser = browser()
            browser.get("/api/session")
            assertFalse(browser.lastSetCookies.single().secure)
            assertNull(browser.get("/api/session").headers["Strict-Transport-Security"])
        }
    }
}
