package com.techgv.cineverse.server

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.*
import kotlin.test.*

class AccountDefaultsTest {
    private val path = "/api/account/defaults"

    @Test fun retainedVerifiedMetadataIsPrivateBoundAndSecretFreeWithoutNewUpstreamWork() {
        val fake = FakeTmdb()
        fake.overrides["GET /3/account"] = {
            Reply(200, """{"id":101,"username":"alice","name":"Alice","iso_639_1":"ta","iso_3166_1":"IN","include_adult":false,"access_token":"SECRET","avatar":{"secret":"SECRET"}}""")
        }
        serverTest(fake) {
            val tab = browser()
            val a = tab.signIn(fake, "A")
            val before = fake.requests.size
            repeat(2) {
                val response = tab.send(HttpMethod.Get, path, origin = null, session = a)
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])
                assertEquals("nosniff", response.headers["X-Content-Type-Options"])
                assertTrue(tab.lastSetCookies.none { it.name == SESSION_COOKIE })
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(setOf("accountObjectId", "sessionGeneration", "language", "region", "includeAdult"), body.keys)
                assertEquals(JsonPrimitive("AOID-A"), body["accountObjectId"])
                assertEquals(JsonPrimitive(a.sessionGeneration.toString()), body["sessionGeneration"])
                assertEquals(JsonPrimitive("ta"), body["language"])
                assertEquals(JsonPrimitive("IN"), body["region"])
                assertEquals(JsonPrimitive(false), body["includeAdult"])
                for (secret in listOf("SECRET", READ_TOKEN, "UAT-A", "SID-A", tab.cookies.getValue(SESSION_COOKIE))) {
                    assertFalse(response.bodyAsText().contains(secret))
                }
            }
            assertEquals(before, fake.requests.size, "metadata is retained during the existing verification, not fetched again")
        }
    }

    @Test fun absentPartialAndExplicitFalseRemainDistinct() {
        for ((fields, expected) in listOf(
            "" to """{"language":null,"region":null,"includeAdult":null}""",
            ",\"iso_639_1\":\"hi\"" to """{"language":"hi","region":null,"includeAdult":null}""",
            ",\"include_adult\":false" to """{"language":null,"region":null,"includeAdult":false}""",
            ",\"iso_639_1\":null,\"iso_3166_1\":\"FR\",\"include_adult\":true" to """{"language":null,"region":"FR","includeAdult":true}""",
        )) {
            val fake = FakeTmdb()
            fake.overrides["GET /3/account"] = { Reply(200, """{"id":101,"username":"alice"$fields}""") }
            serverTest(fake) {
                val tab = browser()
                tab.signIn(fake, "A")
                val body = Json.parseToJsonElement(tab.get(path).bodyAsText()).jsonObject
                assertEquals(Json.parseToJsonElement(expected).jsonObject,
                    JsonObject(body.filterKeys { it in setOf("language", "region", "includeAdult") }))
            }
        }
    }

    @Test fun noSessionMissingBindingMismatchAndRotatedGenerationNeverRetarget() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val tab = browser()
            val guest = tab.send(HttpMethod.Get, path)
            assertEquals(HttpStatusCode.Unauthorized, guest.status)
            assertEquals("""{"error":"not_signed_in"}""", guest.bodyAsText())
            val a = tab.signIn(fake, "A")
            for (headers in listOf(
                emptyMap(),
                mapOf(EXPECTED_ACCOUNT_HEADER to a.accountObjectId!!),
                mapOf(EXPECTED_GENERATION_HEADER to a.sessionGeneration.toString()),
                mapOf(EXPECTED_ACCOUNT_HEADER to a.accountObjectId!!, EXPECTED_GENERATION_HEADER to "9223372036854775808"),
            )) {
                val missing = tab.send(HttpMethod.Get, path, headers = headers)
                assertEquals(HttpStatusCode.BadRequest, missing.status)
                assertEquals("""{"error":"missing_session_binding"}""", missing.bodyAsText())
                assertEquals("private, no-store", missing.headers[HttpHeaders.CacheControl])
            }
            val b = tab.signIn(fake, "B")
            val before = fake.requests.size
            for (stale in listOf(a, b.copy(accountObjectId = "AOID-A"), b.copy(sessionGeneration = Long.MIN_VALUE), b.copy(sessionGeneration = Long.MAX_VALUE))) {
                val response = tab.send(HttpMethod.Get, path, session = stale)
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertEquals("""{"error":"stale_session"}""", response.bodyAsText())
                assertTrue(tab.lastSetCookies.none { it.name == SESSION_COOKIE })
            }
            assertEquals(before, fake.requests.size)
            val again = tab.signIn(fake, "B")
            assertEquals(HttpStatusCode.Conflict, tab.send(HttpMethod.Get, path, session = b).status)
            assertEquals(HttpStatusCode.OK, tab.send(HttpMethod.Get, path, session = again).status)
        }
    }

    @Test fun expiredAndUnknownSessionsLeaveCookiesAloneOnlyLogoutDeletes() {
        val fake = FakeTmdb()
        val clock = TestClock()
        serverTest(fake, clock = clock) {
            val tab = browser()
            val a = tab.signIn(fake, "A")
            val cookie = tab.cookies.getValue(SESSION_COOKIE)
            clock.advanceMinutes(31 * 24 * 60)
            repeat(2) {
                val response = tab.send(HttpMethod.Get, path, session = a)
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertEquals("""{"error":"session_expired"}""", response.bodyAsText())
                assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])
                assertEquals(cookie, tab.cookies[SESSION_COOKIE])
                assertTrue(tab.lastSetCookies.none { it.name == SESSION_COOKIE })
            }
            tab.cookies[SESSION_COOKIE] = "unknown"
            assertEquals(HttpStatusCode.Unauthorized, tab.get(path).status)
            assertTrue(tab.lastSetCookies.none { it.name == SESSION_COOKIE })
            assertEquals(HttpStatusCode.OK, tab.send(HttpMethod.Post, "/api/auth/logout").status)
            assertTrue(tab.lastSetCookies.any { it.name == SESSION_COOKIE && it.maxAge == 0 })
        }
    }

    @Test fun retainedDefaultsShareAccountReadBudgetAndRejectQueries() {
        val fake = FakeTmdb()
        serverTest(fake, testConfig(accountReadsPerMinute = 3)) {
            val tab = browser()
            tab.signIn(fake, "A")
            assertEquals(HttpStatusCode.NotFound, tab.get("$path?language=en").status)
            assertEquals(HttpStatusCode.OK, tab.get(path).status)
            assertEquals(HttpStatusCode.OK, tab.get("/api/account/states/movie/550").status)
            val limited = tab.get(path)
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertNotNull(limited.headers[HttpHeaders.RetryAfter])
            assertEquals("private, no-store", limited.headers[HttpHeaders.CacheControl])
            assertTrue(tab.lastSetCookies.none { it.name == SESSION_COOKIE })
            assertEquals(1, fake.calls(HttpMethod.Get, "/3/account"))
        }
    }
}
