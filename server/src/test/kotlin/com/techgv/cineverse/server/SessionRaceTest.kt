package com.techgv.cineverse.server

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Interleavings of sign-in, logout and late upstream answers (includes the L2 review probes). */
class SessionRaceTest {
    @Test
    fun logoutInvalidatesPendingApproval() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val b = browser()
            b.signIn(fake, "A")
            val (attempt, token) = b.start()
            fake.approve(token, "B")
            assertEquals(HttpStatusCode.OK, b.send(HttpMethod.Post, "/api/auth/logout").status)
            val callback = b.callback(attempt)
            assertFalse(callback.status == HttpStatusCode.Found, "Old approval must not create a new session after logout")
            assertEquals(HttpStatusCode.Conflict, callback.status)
            assertEquals("""{"error":"attempt_superseded"}""", callback.bodyAsText())
            assertFalse(b.session().signedIn)
            assertEquals(1, fake.calls(HttpMethod.Post, "/4/auth/access_token"), "B's request token is never exchanged")
        }
    }

    @Test
    fun logoutWinsOverAlreadyRunningCallback() {
        val fake = FakeTmdb()
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.overrides["GET /3/account"] = {
            reached.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            Reply(200, """{"id":101,"username":"alice","name":"Alice"}""")
        }
        serverTest(fake) {
            val callbackTab = browser()
            val (attempt, token) = callbackTab.start()
            fake.approve(token, "A")
            val logoutTab = browser().also { it.cookies.putAll(callbackTab.cookies) }
            coroutineScope {
                val callback = async(Dispatchers.IO) { callbackTab.callback(attempt) }
                try {
                    check(reached.await(10, TimeUnit.SECONDS))
                    // Local logout is not held up by the callback's upstream call.
                    assertEquals(HttpStatusCode.OK, logoutTab.send(HttpMethod.Post, "/api/auth/logout").status)
                } finally { release.countDown() }
                val response = callback.await()
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertEquals("""{"error":"attempt_superseded"}""", response.bodyAsText())
            }
            assertFalse(callbackTab.session().signedIn, "Callback finished AFTER logout and reinstated sign-in")
            assertFalse(SESSION_COOKIE in callbackTab.cookies)
            // The tokens obtained for the superseded attempt are revoked at TMDB.
            assertEquals("Bearer UAT-A", fake.last(HttpMethod.Delete, "/4/auth/access_token").authorization)
            assertEquals("SID-A", fake.last(HttpMethod.Delete, "/3/authentication/session").json["session_id"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun cancelEndsPendingSignInButKeepsTheSession() {
        val fake = FakeTmdb()
        serverTest(fake) {
            val browser = browser()
            val a = browser.signIn(fake, "A")
            val (attempt, token) = browser.start()
            fake.approve(token, "B")
            assertEquals(HttpStatusCode.NoContent, browser.send(HttpMethod.Post, "/api/auth/cancel?attempt=$attempt").status)
            assertEquals(HttpStatusCode.Conflict, browser.callback(attempt).status)
            assertEquals(a, browser.session(), "cancel is not a sign-out")
            assertEquals(HttpStatusCode.Forbidden, browser.send(HttpMethod.Post, "/api/auth/cancel", csrf = "wrong").status)
            // A new attempt after the cancel works.
            assertEquals("AOID-B", browser.signIn(fake, "B").accountObjectId)
        }
    }
}
