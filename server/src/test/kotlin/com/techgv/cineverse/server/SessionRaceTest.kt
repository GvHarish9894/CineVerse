package com.techgv.cineverse.server

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runInterruptible
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.http.content.OutgoingContent
import io.ktor.http.ContentType
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

    @Test
    fun expiredResponseFromOldSessionCannotClearNewSessionCookie() {
        val fake = FakeTmdb()
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.overrides["GET /4/account/AOID-A/movie/rated"] = {
            reached.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            tmdbError(401, 35)
        }
        serverTest(fake) {
            val activeTab = browser()
            val a = activeTab.signIn(fake, "A")
            val oldTab = browser().also { it.cookies.putAll(activeTab.cookies) }
            coroutineScope {
                val read = async(Dispatchers.IO) { oldTab.get("/api/account/movie/rated") }
                try {
                    check(reached.await(10, TimeUnit.SECONDS))
                    assertEquals("AOID-B", activeTab.signIn(fake, "B").accountObjectId)
                } finally { release.countDown() }
                val response = read.await()
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                // Tagged with the ended session so the client can tell it is not the one it shows now.
                assertEquals(
                    """{"error":"session_expired","status_code":35,"sessionGeneration":${a.sessionGeneration},"accountObjectId":"AOID-A"}""",
                    response.bodyAsText(),
                )
            }
            assertTrue(oldTab.lastSetCookies.none { it.name == SESSION_COOKIE && it.maxAge == 0 },
                "Late A response sends Set-Cookie that removes already-current B cookie in the shared browser jar")
            assertEquals("AOID-B", activeTab.session().accountObjectId)
            assertEquals(HttpStatusCode.OK, activeTab.get("/api/account/movie/rated").status)
        }
    }

    @Test
    fun writeRevalidatesTheSessionAfterItsBodyArrives() {
        val interruptions = mapOf<String, suspend (Browser, FakeTmdb, TestClock) -> Unit>(
            "logout" to { tab, _, _ -> assertEquals(HttpStatusCode.OK, tab.send(HttpMethod.Post, "/api/auth/logout").status) },
            "expiry" to { _, _, clock -> clock.advanceMinutes(30L * 24 * 60) },
            "sign-in as B" to { tab, fake, _ -> tab.signIn(fake, "B") },
        )
        for ((name, interrupt) in interruptions) {
            val fake = FakeTmdb()
            val clock = TestClock()
            serverTest(fake, clock = clock) {
                val writer = browser()
                val a = writer.signIn(fake, "A")
                val otherTab = browser().also { it.cookies.putAll(writer.cookies) }
                val sessionChecked = CountDownLatch(1)
                val bodyRelease = CountDownLatch(1)
                val slowBody = object : OutgoingContent.WriteChannelContent() {
                    override val contentType = ContentType.Application.Json
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        runInterruptible(Dispatchers.IO) { check(bodyRelease.await(10, TimeUnit.SECONDS)) }
                        channel.writeStringUtf8("""{"media_type":"movie","media_id":550,"watchlist":true}""")
                    }
                }
                coroutineScope {
                    clock.onRead = { sessionChecked.countDown() } // the write's session lookup, before its body
                    val write = async(Dispatchers.IO) {
                        writer.send(HttpMethod.Post, "/api/account/watchlist", session = a, content = slowBody)
                    }
                    try {
                        check(sessionChecked.await(10, TimeUnit.SECONDS))
                        clock.onRead = null
                        interrupt(otherTab, fake, clock)
                    } finally { bodyRelease.countDown() }
                    val response = write.await()
                    assertEquals(HttpStatusCode.Conflict, response.status, name)
                    assertEquals("""{"error":"stale_session"}""", response.bodyAsText(), name)
                }
                assertTrue(fake.requests.none { it.path.endsWith("/watchlist") && it.method == HttpMethod.Post }, name)
            }
        }
    }
}
