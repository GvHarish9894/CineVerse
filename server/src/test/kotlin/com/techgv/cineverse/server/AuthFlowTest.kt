package com.techgv.cineverse.server

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthFlowTest {
    @Test
    fun signInHappyPath() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val browser = browser()
            assertEquals(SIGNED_OUT, browser.session())
            val csrf = browser.lastSetCookies.single { it.name == CSRF_COOKIE }
            assertFalse(csrf.httpOnly)
            assertEquals("/", csrf.path)
            assertEquals("Strict", csrf.extensions["SameSite"])

            val (attemptId, requestToken) = browser.start()
            val redirectTo = fake.last(HttpMethod.Post, "/4/auth/request_token").json["redirect_to"]!!.jsonPrimitive.content
            assertEquals("$ORIGIN/api/auth/callback?attempt=$attemptId", redirectTo)
            assertEquals("Bearer $READ_TOKEN", fake.last(HttpMethod.Post, "/4/auth/request_token").authorization)
            val attemptCookie = browser.lastSetCookies.single { it.name == ATTEMPT_COOKIE }
            assertTrue(attemptCookie.httpOnly && attemptCookie.secure)
            assertEquals("/api/auth", attemptCookie.path)
            assertEquals(900, attemptCookie.maxAge)
            assertNotEquals(attemptId, attemptCookie.value, "binding secret is not the id that travels in the URL")

            fake.approve(requestToken, "A")
            val callback = browser.callback(attemptId)
            assertEquals(HttpStatusCode.Found, callback.status)
            assertEquals("$ORIGIN/?signedIn=1", callback.headers[HttpHeaders.Location])
            assertTrue(callback.headers.getAll(HttpHeaders.SetCookie)!!.none { "\$x-enc" in it }, "standard cookie attributes only")
            val sessionCookie = browser.lastSetCookies.single { it.name == SESSION_COOKIE }
            assertTrue(sessionCookie.httpOnly && sessionCookie.secure)
            assertEquals("Lax", sessionCookie.extensions["SameSite"])
            assertEquals("/api", sessionCookie.path)
            assertEquals(30 * 24 * 3600, sessionCookie.maxAge)
            assertTrue(sessionCookie.value.length >= 43, "opaque 256-bit id")
            assertNotEquals(csrf.value, browser.cookies[CSRF_COOKIE], "CSRF token rotates on sign-in")
            assertFalse(ATTEMPT_COOKIE in browser.cookies, "attempt cookie cleared")

            // Exchange → convert → account, each with the right credential.
            assertEquals(requestToken, fake.last(HttpMethod.Post, "/4/auth/access_token").json["request_token"]!!.jsonPrimitive.content)
            assertEquals("UAT-A", fake.last(HttpMethod.Post, "/3/authentication/session/convert/4").json["access_token"]!!.jsonPrimitive.content)
            assertEquals("SID-A", fake.last(HttpMethod.Get, "/3/account").url.parameters["session_id"])

            val sessionJson = browser.get("/api/session").bodyAsText()
            // sessionGeneration: an opaque 64-bit value as a decimal string (JS-safe).
            assertTrue(
                Regex("""\{"signedIn":true,"accountObjectId":"AOID-A","displayName":"Alice","sessionGeneration":"-?\d+"}""").matches(sessionJson),
                sessionJson,
            )
            listOf("UAT-A", "SID-A", sessionCookie.value, READ_TOKEN).forEach { assertFalse(sessionJson.contains(it)) }

            // The attempt is single-use.
            assertEquals(HttpStatusCode.Gone, browser.callback(attemptId).status)
        }
    }

    @Test
    fun pendingApprovalKeepsTheAttempt() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val browser = browser()
            val (attemptId, requestToken) = browser.start()
            val pending = browser.callback(attemptId)
            assertEquals(HttpStatusCode.Conflict, pending.status)
            assertEquals("""{"error":"not_approved","status_code":41}""", pending.bodyAsText())
            assertEquals(SIGNED_OUT, browser.session())

            fake.approve(requestToken, "B")
            assertEquals(HttpStatusCode.Found, browser.callback(attemptId).status)
            assertEquals("bob", browser.session().displayName) // blank name → username
        }
    }

    @Test
    fun terminalExchangeOutcomes() {
        val cases = listOf(
            tmdbError(401, 33) to (HttpStatusCode.Gone to "request_token_expired"),
            tmdbError(401, 7) to (HttpStatusCode.InternalServerError to "app_credential"),
            tmdbError(401, 10) to (HttpStatusCode.InternalServerError to "app_credential"),
            tmdbError(401, 17) to (HttpStatusCode.Forbidden to "denied"),
            Reply(400, "garbage") to (HttpStatusCode.Forbidden to "denied"),
        )
        for ((reply, expected) in cases) {
            val fake = FakeTmdb()
            serverTest(fake) {
                fake.overrides["POST /4/auth/access_token"] = { reply }
                val browser = browser()
                val (attemptId, _) = browser.start()
                val response = browser.callback(attemptId)
                assertEquals(expected.first, response.status, expected.second)
                assertTrue(response.bodyAsText().startsWith("""{"error":"${expected.second}""""), response.bodyAsText())
                assertFalse(SESSION_COOKIE in browser.cookies)
                assertEquals(SIGNED_OUT, browser.session())
                // Terminal: the attempt is gone, TMDB is not asked again.
                assertEquals(HttpStatusCode.Gone, browser.callback(attemptId).status)
                assertEquals(1, fake.calls(HttpMethod.Post, "/4/auth/access_token"))
            }
        }
    }

    @Test
    fun attemptsExpireAfter15Minutes() {
        val fake = FakeTmdb()
        val clock = TestClock()
        serverTest(fake, clock = clock) {
            val browser = browser()
            val (attemptId, requestToken) = browser.start()
            fake.approve(requestToken, "A")
            clock.advanceMinutes(15)
            assertEquals(HttpStatusCode.Gone, browser.callback(attemptId).status)
            assertEquals(0, fake.calls(HttpMethod.Post, "/4/auth/access_token"))
        }
    }

    @Test
    fun callbackIsBoundToTheBrowserThatStartedTheAttempt() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val attacker = browser()
            val victim = browser()
            val (attemptId, requestToken) = attacker.start()
            fake.approve(requestToken, "A") // attacker approves with their own account
            victim.get("/api/session")

            val noCookie = victim.callback(attemptId)
            assertEquals(HttpStatusCode.Forbidden, noCookie.status)
            assertEquals("""{"error":"attempt_mismatch"}""", noCookie.bodyAsText())
            victim.cookies[ATTEMPT_COOKIE] = "forged"
            assertEquals(HttpStatusCode.Forbidden, victim.callback(attemptId).status)
            victim.start() // the victim's own attempt cookie does not match the attacker's attempt either
            assertEquals(HttpStatusCode.Forbidden, victim.callback(attemptId).status)
            assertEquals(SIGNED_OUT, victim.session())
            assertEquals(0, fake.calls(HttpMethod.Post, "/4/auth/access_token"))
            assertEquals(HttpStatusCode.Gone, victim.callback("unknown").status)

            // The legitimate browser can still complete its attempt.
            assertEquals(HttpStatusCode.Found, attacker.callback(attemptId).status)
        }
    }

    @Test
    fun failureAfterTheExchangePersistsNothingAndRevokes() {
        val fake = FakeTmdb()
        serverTest(fake) {
            fake.overrides["GET /3/account"] = { throw IOException("down") }
            val browser = browser()
            val (attemptId, requestToken) = browser.start()
            fake.approve(requestToken, "A")
            assertEquals(HttpStatusCode.GatewayTimeout, browser.callback(attemptId).status)
            assertFalse(SESSION_COOKIE in browser.cookies)
            assertEquals(SIGNED_OUT, browser.session())
            assertEquals("Bearer UAT-A", fake.last(HttpMethod.Delete, "/4/auth/access_token").authorization)
            assertEquals("SID-A", fake.last(HttpMethod.Delete, "/3/authentication/session").json["session_id"]!!.jsonPrimitive.content)
        }
        val convertFails = FakeTmdb()
        serverTest(convertFails) {
            convertFails.overrides["POST /3/authentication/session/convert/4"] = { tmdbError(500, 11) }
            val browser = browser()
            val (attemptId, requestToken) = browser.start()
            convertFails.approve(requestToken, "A")
            val response = browser.callback(attemptId)
            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertEquals("""{"error":"sign_in_failed","status_code":11}""", response.bodyAsText())
            assertEquals(1, convertFails.calls(HttpMethod.Delete, "/4/auth/access_token"))
            assertEquals(0, convertFails.calls(HttpMethod.Delete, "/3/authentication/session"))
            assertEquals(SIGNED_OUT, browser.session())
        }
    }

    @Test
    fun signInRotatesTheSession() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val browser = browser()
            val first = browser.signIn(fake, "A")
            val oldCookie = browser.cookies.getValue(SESSION_COOKIE)
            val second = browser.signIn(fake, "B")
            val newCookie = browser.cookies.getValue(SESSION_COOKIE)
            assertNotEquals(oldCookie, newCookie)
            assertNotEquals(first.sessionGeneration, second.sessionGeneration)
            assertEquals("AOID-B", second.accountObjectId)
            // The replaced session is dead server-side and its TMDB credentials were revoked.
            browser.cookies[SESSION_COOKIE] = oldCookie
            assertEquals(HttpStatusCode.Unauthorized, browser.get("/api/account/movie/watchlist").status)
            assertEquals("Bearer UAT-A", fake.last(HttpMethod.Delete, "/4/auth/access_token").authorization)
        }
    }

    @Test
    fun sessionsExpireAfterSessionTtl() {
        val fake = FakeTmdb()
        val clock = TestClock()
        serverTest(fake, clock = clock) {
            val browser = browser()
            browser.signIn(fake, "A")
            clock.advanceMinutes(30L * 24 * 60)
            assertEquals(SIGNED_OUT, browser.session())
            // No deletion: the browser drops the cookie itself, its Max-Age is the session TTL.
            assertTrue(browser.lastSetCookies.none { it.name == SESSION_COOKIE })
        }
    }

    @Test
    fun logoutClearsLocallyAndRevokesRemotely() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val browser = browser()
            browser.signIn(fake, "A")
            val cookie = browser.cookies.getValue(SESSION_COOKIE)
            val response = browser.send(HttpMethod.Post, "/api/auth/logout")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(SIGNED_OUT, kotlinx.serialization.json.Json.decodeFromString<AuthSession>(response.bodyAsText()))
            assertEquals(0, browser.lastSetCookies.single { it.name == SESSION_COOKIE }.maxAge)
            assertEquals("Bearer UAT-A", fake.last(HttpMethod.Delete, "/4/auth/access_token").authorization)
            assertEquals("UAT-A", fake.last(HttpMethod.Delete, "/4/auth/access_token").json["access_token"]!!.jsonPrimitive.content)
            val deleteSession = fake.last(HttpMethod.Delete, "/3/authentication/session")
            assertEquals("Bearer $READ_TOKEN", deleteSession.authorization)
            assertEquals("SID-A", deleteSession.json["session_id"]!!.jsonPrimitive.content)
            // A replayed cookie no longer works.
            browser.cookies[SESSION_COOKIE] = cookie
            assertEquals(SIGNED_OUT, browser.session())
        }
    }

    @Test
    fun logoutClearsEvenIfRevocationFails() {
        val fake = FakeTmdb()
        serverTest(fake) {
            fake.overrides["DELETE /4/auth/access_token"] = { throw IOException("down") }
            fake.overrides["DELETE /3/authentication/session"] = { tmdbError(500, 11) }
            val browser = browser()
            browser.signIn(fake, "A")
            val cookie = browser.cookies.getValue(SESSION_COOKIE)
            assertEquals(HttpStatusCode.OK, browser.send(HttpMethod.Post, "/api/auth/logout").status)
            assertFalse(SESSION_COOKIE in browser.cookies)
            browser.cookies[SESSION_COOKIE] = cookie
            assertEquals(SIGNED_OUT, browser.session())
            assertEquals(HttpStatusCode.Unauthorized, browser.get("/api/account/movie/watchlist").status)
        }
    }

    @Test
    fun startAndLogoutRequireCsrf() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val browser = browser()
            browser.get("/api/session")
            assertEquals(HttpStatusCode.Forbidden, browser.send(HttpMethod.Post, "/api/auth/start", origin = null).status)
            assertEquals(HttpStatusCode.Forbidden, browser.send(HttpMethod.Post, "/api/auth/start", csrf = "wrong").status)
            assertEquals(HttpStatusCode.Forbidden, browser.send(HttpMethod.Post, "/api/auth/logout", origin = "https://evil.example").status)
            assertEquals(0, fake.calls(HttpMethod.Post, "/4/auth/request_token"))
            assertNotNull(browser.cookies[CSRF_COOKIE])
        }
    }

    /** Generations are unique per [AuthStore] instance (i.e. per server run), not just within one. */
    @Test
    fun twoAuthStoresNeverShareAGeneration() {
        fun generations(): Set<Long> {
            val store = AuthStore(testConfig()) { 1_000_000L }
            var binding: String? = null
            val generations = List(10_000) {
                val attempt = store.newAttempt("RT", randomId(), binding, null).also { binding = it.binding }
                store.commitSignIn(attempt, AccessTokenDto("UAT-A", "AOID-A"), "SID-A", AccountDto(101, "alice"), null)!!.session.generation
            }
            return generations.toSet().also { assertEquals(10_000, it.size) }
        }
        val first = generations()
        assertTrue(first.intersect(generations()).isEmpty())
        assertFalse(0L in first, "0 is the signed-out generation")
    }
}
