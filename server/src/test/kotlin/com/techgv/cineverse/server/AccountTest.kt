package com.techgv.cineverse.server

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountTest {
    private val watchlistBody = """{"media_type":"movie","media_id":550,"watchlist":true}"""

    @Test
    fun twoAccountsAreIsolated() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val alice = browser()
            val bob = browser()
            val a = alice.signIn(fake, "A")
            val b = bob.signIn(fake, "B")
            assertEquals("AOID-A", a.accountObjectId)
            assertEquals("AOID-B", b.accountObjectId)

            repeat(2) {
                val aList = alice.get("/api/account/tv/favorites?page=1&sort_by=created_at.desc&language=en-US")
                assertEquals(HttpStatusCode.OK, aList.status)
                assertTrue(aList.bodyAsText().contains("\"owner\":\"A\""))
                val aRequest = fake.requests.last()
                assertEquals("/4/account/AOID-A/tv/favorites", aRequest.path)
                assertEquals("Bearer UAT-A", aRequest.authorization)
                assertEquals("created_at.desc", aRequest.url.parameters["sort_by"])

                val bList = bob.get("/api/account/tv/favorites")
                assertTrue(bList.bodyAsText().contains("\"owner\":\"B\""))
                assertEquals("Bearer UAT-B", fake.requests.last().authorization)
            }
            assertTrue(alice.get("/api/account/states/tv/1399").bodyAsText().contains("\"owner\":\"A\""))
            assertEquals("SID-A", fake.requests.last().url.parameters["session_id"])
            assertTrue(bob.get("/api/account/states/tv/1399").bodyAsText().contains("\"owner\":\"B\""))

            // Writes go to each account with its own session.
            assertEquals(HttpStatusCode.Created, alice.send(HttpMethod.Post, "/api/account/watchlist", watchlistBody, session = a).status)
            val aWrite = fake.last(HttpMethod.Post, "/3/account/101/watchlist")
            assertEquals("SID-A", aWrite.url.parameters["session_id"])
            assertEquals("Bearer $READ_TOKEN", aWrite.authorization)
            assertEquals(HttpStatusCode.Created, bob.send(HttpMethod.Post, "/api/account/watchlist", watchlistBody, session = b).status)
            assertEquals("SID-B", fake.last(HttpMethod.Post, "/3/account/202/watchlist").url.parameters["session_id"])

            // Alice's cookie with Bob's identity binding is stale, not a way into Bob's account.
            val crossed = alice.send(HttpMethod.Post, "/api/account/watchlist", watchlistBody, session = b)
            assertEquals(HttpStatusCode.Conflict, crossed.status)
            assertEquals(1, fake.calls(HttpMethod.Post, "/3/account/202/watchlist"))
        }
    }

    @Test
    fun mutationsForwardOnlyValidatedFields() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val alice = browser()
            val a = alice.signIn(fake, "A")
            alice.send(HttpMethod.Post, "/api/account/favorite", """{"media_type":"tv","media_id":1399,"favorite":false}""", session = a)
            assertEquals(
                """{"media_type":"tv","media_id":1399,"favorite":false}""",
                fake.last(HttpMethod.Post, "/3/account/101/favorite").body,
            )
            assertEquals(HttpStatusCode.Created, alice.send(HttpMethod.Post, "/api/account/rating/movie/550", """{"value":7.5}""", session = a).status)
            assertEquals("""{"value":7.5}""", fake.last(HttpMethod.Post, "/3/movie/550/rating").body)
            assertEquals(HttpStatusCode.OK, alice.send(HttpMethod.Delete, "/api/account/rating/tv/1399", session = a).status)
            assertEquals("SID-A", fake.last(HttpMethod.Delete, "/3/tv/1399/rating").url.parameters["session_id"])

            val before = fake.requests.size
            val bad = listOf(
                "/api/account/rating/movie/550" to """{"value":10.5}""",
                "/api/account/rating/movie/550" to """{"value":0.3}""",
                "/api/account/watchlist" to """{"media_type":"person","media_id":1,"watchlist":true}""",
                "/api/account/watchlist" to """{"media_type":"movie","media_id":-1,"watchlist":true}""",
                "/api/account/watchlist" to """{"media_type":"movie","media_id":1,"watchlist":true,"session_id":"x"}""",
                "/api/account/watchlist" to "not json",
            )
            for ((path, body) in bad) {
                assertEquals(HttpStatusCode.BadRequest, alice.send(HttpMethod.Post, path, body, session = a).status, body)
            }
            val big = """{"media_type":"movie","media_id":1,"watchlist":true,"x":"${"a".repeat(2_000)}"}"""
            assertEquals(HttpStatusCode.PayloadTooLarge, alice.send(HttpMethod.Post, "/api/account/watchlist", big, session = a).status)
            val form = alice.send(
                HttpMethod.Post, "/api/account/watchlist", session = a,
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            )
            assertEquals(HttpStatusCode.UnsupportedMediaType, form.status)
            assertEquals(HttpStatusCode.NotFound, alice.send(HttpMethod.Post, "/api/account/rating/person/1", """{"value":7}""", session = a).status)
            assertEquals(HttpStatusCode.NotFound, alice.get("/api/account/movie/lists").status)
            assertEquals(HttpStatusCode.NotFound, alice.get("/api/account/movie/watchlist?sort_by=title.asc").status)
            assertEquals(HttpStatusCode.NotFound, alice.get("/api/account/movie/watchlist?session_id=SID-B").status)
            assertEquals(before, fake.requests.size, "nothing invalid reaches TMDB")
        }
    }

    @Test
    fun staleOrMissingSessionBindingIsRejected() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val alice = browser()
            val a = alice.signIn(fake, "A")
            val stale = alice.send(HttpMethod.Post, "/api/account/watchlist", watchlistBody, session = a.copy(sessionGeneration = a.sessionGeneration + 1))
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertEquals("""{"error":"stale_session"}""", stale.bodyAsText())
            val otherAccount = alice.send(HttpMethod.Post, "/api/account/watchlist", watchlistBody, session = a.copy(accountObjectId = "AOID-B"))
            assertEquals(HttpStatusCode.Conflict, otherAccount.status)
            assertEquals(HttpStatusCode.BadRequest, alice.send(HttpMethod.Post, "/api/account/watchlist", watchlistBody).status)

            // Sign out → sign in again to the same account: the old generation is stale.
            alice.send(HttpMethod.Post, "/api/auth/logout")
            val again = alice.signIn(fake, "A")
            assertEquals(a.accountObjectId, again.accountObjectId)
            assertEquals(HttpStatusCode.Conflict, alice.send(HttpMethod.Delete, "/api/account/rating/movie/550", session = a).status)
            assertEquals(0, fake.calls(HttpMethod.Post, "/3/account/101/watchlist"))
            assertEquals(0, fake.calls(HttpMethod.Delete, "/3/movie/550/rating"))
        }
    }

    @Test
    fun csrfIsEnforcedOnEveryMutation() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val alice = browser()
            val a = alice.signIn(fake, "A")
            val mutations = listOf(
                Triple(HttpMethod.Post, "/api/account/watchlist", watchlistBody),
                Triple(HttpMethod.Post, "/api/account/favorite", """{"media_type":"movie","media_id":550,"favorite":true}"""),
                Triple(HttpMethod.Post, "/api/account/rating/movie/550", """{"value":8}"""),
                Triple(HttpMethod.Delete, "/api/account/rating/movie/550", null),
            )
            for ((method, path, body) in mutations) {
                val missingOrigin = alice.send(method, path, body, origin = null, session = a)
                assertEquals(HttpStatusCode.Forbidden, missingOrigin.status, path)
                assertEquals("""{"error":"csrf_origin"}""", missingOrigin.bodyAsText())
                assertEquals(HttpStatusCode.Forbidden, alice.send(method, path, body, origin = "https://evil.example", session = a).status)
                assertEquals(HttpStatusCode.Forbidden, alice.send(method, path, body, origin = "null", session = a).status)
                val crossSite = alice.send(method, path, body, session = a, headers = mapOf("Sec-Fetch-Site" to "cross-site"))
                assertEquals(HttpStatusCode.Forbidden, crossSite.status)
                val wrongToken = alice.send(method, path, body, csrf = "wrong", session = a)
                assertEquals(HttpStatusCode.Forbidden, wrongToken.status)
                assertEquals("""{"error":"csrf_token"}""", wrongToken.bodyAsText())
            }
            // No CSRF cookie at all (e.g. a forged form post from another site) → rejected.
            alice.cookies.remove(CSRF_COOKIE)
            assertEquals(HttpStatusCode.Forbidden, alice.send(HttpMethod.Post, "/api/account/watchlist", watchlistBody, csrf = "", session = a).status)
            assertTrue(fake.requests.none { it.method != HttpMethod.Get && it.path.startsWith("/3/account/101") })
            assertTrue(fake.requests.none { it.path.endsWith("/rating") })
        }
    }

    @Test
    fun tmdbSessionErrorsSignOutLocally() {
        for (code in listOf(3, 14, 17, 37)) {
            val fake = FakeTmdb()
            serverTest(fake) {
                val alice = browser()
                val a = alice.signIn(fake, "A")
                fake.overrides["POST /3/account/101/watchlist"] = { tmdbError(401, code) }
                val response = alice.send(HttpMethod.Post, "/api/account/watchlist", watchlistBody, session = a)
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertTrue(response.bodyAsText().startsWith("""{"error":"session_expired""""))
                assertEquals(0, alice.lastSetCookies.single { it.name == SESSION_COOKIE }.maxAge)
                assertEquals(SIGNED_OUT, alice.session())
            }
        }
        val fake = FakeTmdb()
        serverTest(fake) {
            val alice = browser()
            alice.signIn(fake, "A")
            val cookie = alice.cookies.getValue(SESSION_COOKIE)
            fake.overrides["GET /4/account/AOID-A/movie/rated"] = { tmdbError(401, 35) }
            val response = alice.get("/api/account/movie/rated")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("""{"error":"session_expired","status_code":35}""", response.bodyAsText())
            assertFalse(SESSION_COOKIE in alice.cookies)
            // The server-side session is gone too: replaying the cookie gives session_expired.
            alice.cookies[SESSION_COOKIE] = cookie
            val replay = alice.get("/api/account/movie/rated")
            assertEquals("""{"error":"session_expired"}""", replay.bodyAsText())
            assertEquals(0, alice.lastSetCookies.single { it.name == SESSION_COOKIE }.maxAge)
        }
    }

    @Test
    fun appCredentialErrorsNeverSignOut() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val alice = browser()
            val a = alice.signIn(fake, "A")
            fake.overrides["GET /3/movie/550/account_states"] = { tmdbError(401, 7) }
            assertEquals(HttpStatusCode.InternalServerError, alice.get("/api/account/states/movie/550").status)
            assertEquals(a, alice.session())
        }
    }

    @Test
    fun notSignedInAndUnknownCookie() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val guest = browser()
            guest.get("/api/session")
            val response = guest.get("/api/account/movie/watchlist")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("""{"error":"not_signed_in"}""", response.bodyAsText())
            assertEquals(HttpStatusCode.Unauthorized, guest.send(HttpMethod.Delete, "/api/account/rating/movie/1").status)
            guest.cookies[SESSION_COOKIE] = "forged"
            assertEquals("""{"error":"session_expired"}""", guest.get("/api/account/movie/watchlist").bodyAsText())
            assertFalse(SESSION_COOKIE in guest.cookies)
            assertTrue(fake.requests.none { it.path.contains("account") })
        }
    }

    @Test
    fun mutationsAreNeverRetried() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val alice = browser()
            val a = alice.signIn(fake, "A")
            fake.overrides["POST /3/account/101/watchlist"] = { tmdbError(503, 11) }
            assertEquals(HttpStatusCode.BadGateway, alice.send(HttpMethod.Post, "/api/account/watchlist", watchlistBody, session = a).status)
            fake.overrides["POST /3/movie/550/rating"] = { throw IOException("timeout") }
            assertEquals(HttpStatusCode.GatewayTimeout, alice.send(HttpMethod.Post, "/api/account/rating/movie/550", """{"value":5}""", session = a).status)
            assertEquals(1, fake.calls(HttpMethod.Post, "/3/account/101/watchlist"))
            assertEquals(1, fake.calls(HttpMethod.Post, "/3/movie/550/rating"))
            assertEquals(a, alice.session(), "upstream failure is not a sign-out")
            assertEquals("SID-A", fake.last(HttpMethod.Post, "/3/movie/550/rating").url.parameters["session_id"])
        }
    }
}
