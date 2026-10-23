package com.techgv.cineverse.server

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runInterruptible
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.http.content.OutgoingContent
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.testing.testApplication
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
                val read = async(Dispatchers.IO) { oldTab.get("/api/account/movie/rated", session = a) }
                try {
                    check(reached.await(10, TimeUnit.SECONDS))
                    assertEquals("AOID-B", activeTab.signIn(fake, "B").accountObjectId)
                } finally { release.countDown() }
                val response = read.await()
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                // Tagged with the ended session so the client can tell it is not the one it shows now.
                assertEquals(
                    """{"error":"session_expired","status_code":35,"sessionGeneration":"${a.sessionGeneration}","accountObjectId":"AOID-A"}""",
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
    fun logoutDuringPostCommitRevocationEndsTheBrowserSession() {
        val fake = FakeTmdb()
        val revokeStarted = CountDownLatch(1)
        val finishRevoke = CountDownLatch(1)
        serverTest(fake) {
            val callbackTab = browser()
            callbackTab.signIn(fake, "A")
            val (attempt, token) = callbackTab.start()
            fake.approve(token, "B")
            val logoutTab = browser().also { it.cookies.putAll(callbackTab.cookies) }
            fake.overrides["DELETE /4/auth/access_token"] = { r ->
                if (r.authorization == "Bearer UAT-A") {
                    revokeStarted.countDown()
                    check(finishRevoke.await(10, TimeUnit.SECONDS))
                }
                Reply(200, """{"success":true}""")
            }
            coroutineScope {
                val callback = async(Dispatchers.IO) { callbackTab.callback(attempt) }
                try {
                    // B is committed and A is being revoked; B's cookie has not reached the browser yet.
                    check(revokeStarted.await(10, TimeUnit.SECONDS))
                    assertEquals(HttpStatusCode.OK, logoutTab.send(HttpMethod.Post, "/api/auth/logout").status)
                } finally { finishRevoke.countDown() }
                val response = callback.await()
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertEquals("""{"error":"attempt_superseded"}""", response.bodyAsText())
                assertTrue(callbackTab.lastSetCookies.none { it.name == SESSION_COOKIE }, "B's cookie is never published")
            }
            assertFalse(callbackTab.session().signedIn,
                "Logout completed while callback was still revoking A, but callback then published a live B session")
            // B's record is gone (logout ended it) and its tokens are revoked at TMDB.
            assertEquals(1, fake.requests.count { it.method == HttpMethod.Delete && it.authorization == "Bearer UAT-B" })
            assertTrue(fake.requests.any { it.method == HttpMethod.Delete && it.body?.contains("SID-B") == true })
        }
    }

    @Test
    fun expiryDeletionAlreadyPreparedCannotDeleteLaterSignIn() {
        val fake = FakeTmdb()
        fake.overrides["GET /4/account/AOID-A/movie/rated"] = { tmdbError(401, 35) }
        val responsePrepared = CompletableDeferred<Unit>()
        val deliverResponse = CompletableDeferred<Unit>()
        val delayOldResponse = createClientPlugin("DelayExpiryDelivery") {
            onResponse { response ->
                if (response.call.request.url.encodedPath == "/api/account/movie/rated") {
                    responsePrepared.complete(Unit)
                    deliverResponse.await()
                }
            }
        }
        serverTest(fake) {
            val activeTab = browser()
            val a = activeTab.signIn(fake, "A")
            val delayedClient = createClient {
                followRedirects = false
                install(delayOldResponse)
            }
            val oldTab = Browser(delayedClient).also { it.cookies.putAll(activeTab.cookies) }
            coroutineScope {
                val oldRead = async(Dispatchers.IO) { oldTab.get("/api/account/movie/rated", session = a) }
                try {
                    responsePrepared.await()
                    assertEquals("AOID-B", activeTab.signIn(fake, "B").accountObjectId)
                } finally { deliverResponse.complete(Unit) }
                assertEquals(HttpStatusCode.Unauthorized, oldRead.await().status)
            }
            assertTrue(oldTab.lastSetCookies.none { it.name == SESSION_COOKIE && it.maxAge == 0 },
                "A's failure removed a live record first, but its delayed Set-Cookie still deletes B's fresh browser cookie")
            assertEquals("AOID-B", activeTab.session().accountObjectId)
        }
    }

    @Test
    fun syncKeepsTheCookieOfANewerSessionOfTheBrowser() {
        val fake = FakeTmdb()
        serverTest(fake) {
            // A ends at TMDB; before any sync this browser signs in as B. A sync request that still carried A
            // (sent before B's cookie arrived) must not delete B's cookie.
            val tab = browser()
            tab.signIn(fake, "A")
            val staleTab = browser().also { it.cookies.putAll(tab.cookies) }
            fake.overrides["GET /4/account/AOID-A/movie/rated"] = { tmdbError(401, 35) }
            assertEquals(HttpStatusCode.Unauthorized, tab.get("/api/account/movie/rated").status)
            assertEquals("AOID-B", tab.signIn(fake, "B").accountObjectId)
            assertEquals(SIGNED_OUT, staleTab.session())
            assertTrue(staleTab.lastSetCookies.none { it.name == SESSION_COOKIE })
            assertEquals("AOID-B", tab.session().accountObjectId)
        }
    }

    @Test
    fun staleCookieOnSessionSyncIsSignedOutWithoutSetCookie() {
        val fake = FakeTmdb()
        val clock = TestClock()
        serverTest(fake, clock = clock) {
            // TMDB ends A: neither the 401 nor the follow-up sync touches the cookie.
            val tab = browser()
            tab.signIn(fake, "A")
            val staleA = tab.cookies.getValue(SESSION_COOKIE)
            fake.overrides["GET /4/account/AOID-A/movie/rated"] = { tmdbError(401, 35) }
            assertEquals(HttpStatusCode.Unauthorized, tab.get("/api/account/movie/rated").status)
            val sync = tab.get("/api/session")
            assertEquals(SIGNED_OUT, Json.decodeFromString<AuthSession>(sync.bodyAsText()))
            assertEquals("private, no-store", sync.headers[HttpHeaders.CacheControl])
            assertTrue(tab.lastSetCookies.none { it.name == SESSION_COOKIE })
            assertEquals(staleA, tab.cookies[SESSION_COOKIE], "the harmless stale cookie stays")
            // The next sign-in overwrites it.
            assertEquals("AOID-B", tab.signIn(fake, "B").accountObjectId)
            assertTrue(tab.cookies.getValue(SESSION_COOKIE) != staleA)

            // Forged/unknown and TTL-expired cookies: signed out, no Set-Cookie either.
            val forged = browser().also { it.cookies[SESSION_COOKIE] = "forged" }
            assertEquals(SIGNED_OUT, forged.session())
            assertTrue(forged.lastSetCookies.none { it.name == SESSION_COOKIE })
            clock.advanceMinutes(30L * 24 * 60)
            assertEquals(SIGNED_OUT, tab.session())
            assertTrue(tab.lastSetCookies.none { it.name == SESSION_COOKIE })
        }
    }

    @Test
    fun syncDeletionAlreadyPreparedCannotDeleteLaterSignIn() {
        val fake = FakeTmdb()
        fake.overrides["GET /4/account/AOID-A/movie/rated"] = { tmdbError(401, 35) }
        val prepared = CompletableDeferred<Unit>()
        val deliver = CompletableDeferred<Unit>()
        val delaySync = createClientPlugin("DelaySessionSyncDelivery") {
            onResponse { response ->
                if (response.call.request.url.encodedPath == "/api/session") {
                    prepared.complete(Unit)
                    deliver.await()
                }
            }
        }
        serverTest(fake) {
            val activeTab = browser()
            activeTab.signIn(fake, "A")
            assertEquals(HttpStatusCode.Unauthorized, activeTab.get("/api/account/movie/rated").status)
            val delayedClient = createClient {
                followRedirects = false
                install(delaySync)
            }
            val oldTab = Browser(delayedClient).also { it.cookies.putAll(activeTab.cookies) }
            coroutineScope {
                val sync = async(Dispatchers.IO) { oldTab.get("/api/session") }
                try {
                    prepared.await()
                    assertEquals("AOID-B", activeTab.signIn(fake, "B").accountObjectId)
                } finally { deliver.complete(Unit) }
                assertEquals(HttpStatusCode.OK, sync.await().status)
            }
            assertTrue(oldTab.lastSetCookies.none { it.name == SESSION_COOKIE && it.maxAge == 0 },
                "A sync prepared before B signs in must not deliver a deletion of B's new cookie")
            assertEquals("AOID-B", activeTab.session().accountObjectId)
        }
    }

    /** Every registered route except `POST /api/auth/logout`, hit with an expired or unknown cookie, never deletes `cv_session`. */
    @Test
    fun onlyLogoutDeletesTheSessionCookie() {
        val fake = FakeTmdb()
        val clock = TestClock()
        lateinit var routes: List<RoutingNode>
        testApplication {
            application {
                cineVerse(testConfig(), fake.engine, clock::now)
                routes = plugin(RoutingRoot).getAllRoutes()
            }
            startApplication()
            val samples = mapOf("media" to "movie", "list" to "watchlist", "id" to "550", "path" to "configuration")
            val requests = routes.flatMap { route ->
                val chain = generateSequence(route) { it.parent }.toList().asReversed()
                val path = chain.mapNotNull { node ->
                    when (val selector = node.selector) {
                        is PathSegmentConstantRouteSelector -> selector.value
                        is PathSegmentParameterRouteSelector -> samples.getValue(selector.name)
                        is PathSegmentTailcardRouteSelector -> samples[selector.name] ?: "unknown"
                        else -> null
                    }
                }.joinToString("/", prefix = "/")
                val method = chain.firstNotNullOfOrNull { (it.selector as? HttpMethodRouteSelector)?.method }
                (method?.let(::listOf) ?: listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Delete)).map { it to path }
            }.distinct()
            val logout = HttpMethod.Post to "/api/auth/logout"
            assertTrue(logout in requests && (HttpMethod.Get to "/api/session") in requests, requests.toString())

            val expiredTab = browser()
            val expired = expiredTab.signIn(fake, "A")
            clock.advanceMinutes(31L * 24 * 60)
            val unknownTab = browser().also { it.get("/api/session"); it.cookies[SESSION_COOKIE] = randomId() }
            for (tab in listOf(expiredTab, unknownTab)) {
                for ((method, path) in requests - logout) {
                    tab.send(method, path, json = "{}".takeIf { method != HttpMethod.Get }, session = expired)
                    assertTrue(
                        tab.lastSetCookies.none { it.name == SESSION_COOKIE && it.maxAge == 0 },
                        "$method $path must not delete cv_session",
                    )
                }
                assertEquals(HttpStatusCode.OK, tab.send(logout.first, logout.second).status)
                assertTrue(tab.lastSetCookies.any { it.name == SESSION_COOKIE && it.maxAge == 0 }, "logout deletes cv_session")
            }
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
