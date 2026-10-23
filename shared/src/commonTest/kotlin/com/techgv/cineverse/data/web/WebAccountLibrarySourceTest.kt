package com.techgv.cineverse.data.web

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class WebAccountLibrarySourceTest {
    private val ref = MediaRef(MediaType.Movie, 550)
    private fun source(http: WebProxyHttp, auth: WebAuthRepository) = WebAccountLibrarySource(http, auth) { Settings() }

    @Test fun accountRoutesCarryCapturedIdentityAndEveryMutationReadsCurrentCsrf() = runTest {
        var csrf = "one"
        val calls = mutableListOf<HttpRequestData>()
        val http = WebProxyHttp(HttpClient(MockEngine { request ->
            calls += request
            respond(when {
                request.url.encodedPath == "/api/session" -> sessionJson()
                request.method != HttpMethod.Get -> """{"success":true}"""
                request.url.encodedPath.contains("/states/") -> """{"watchlist":true,"favorite":false,"rated":{"value":7.5}}"""
                else -> """{"page":1,"total_pages":700,"results":[{"id":550,"title":"A","account_rating":{"value":7.5}}]}"""
            }, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }), { csrf })
        val auth = WebAuthRepository(http)
        val a = auth.revalidate()!!
        val library = source(http, auth)
        val page = library.page(a, LibraryKind.RATING, MediaType.Movie, 1)
        assertEquals(500, page.lastPage); assertEquals(7.5, page.entries.single().rating)
        assertEquals(7.5, library.states(a, ref).rating)
        val target = LibraryState(known = true, watchlist = true, favourite = false, rating = 8.5, pending = emptySet())
        library.write(a, ref, LibraryKind.WATCHLIST, target)
        csrf = "two"
        library.write(a, ref, LibraryKind.FAVOURITE, target)
        csrf = "three"
        library.write(a, ref, LibraryKind.RATING, target)
        csrf = "four"
        library.write(a, ref, LibraryKind.RATING, target.copy(rating = null))
        val account = calls.drop(1)
        assertEquals(listOf("/api/account/movie/rated", "/api/account/states/movie/550", "/api/account/watchlist", "/api/account/favorite", "/api/account/rating/movie/550", "/api/account/rating/movie/550"), account.map { it.url.encodedPath })
        assertEquals(setOf("page", "sort_by", "language"), account.first().url.parameters.names())
        assertTrue(account[1].url.parameters.isEmpty())
        assertEquals(listOf("one", "two", "three", "four"), account.drop(2).map { it.headers["X-CV-CSRF"] })
        assertEquals("""{"media_type":"movie","media_id":550,"watchlist":true}""", (account[2].body as TextContent).text)
        assertEquals("""{"media_type":"movie","media_id":550,"favorite":false}""", (account[3].body as TextContent).text)
        assertEquals("""{"value":8.5}""", (account[4].body as TextContent).text)
        assertEquals(HttpMethod.Delete, account[5].method)
        account.forEach {
            assertEquals(a.sessionGeneration.toString(), it.headers["X-CV-Expected-Session-Generation"])
            assertEquals("A", it.headers["X-CV-Expected-Account"])
            assertNull(it.headers[HttpHeaders.Authorization]); assertNull(it.headers[HttpHeaders.Cookie])
            assertFalse("session_id" in it.url.parameters)
        }
        assertEquals(4, library.libraryRevision.value)
    }

    @Test fun delayedAReadIsDroppedAfterBBecomesAuthoritative() = runTest {
        var wire = sessionJson()
        val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val http = mockHttp { r ->
            if (r.url.encodedPath != "/api/session") { reached.complete(Unit); release.await() }
            respond(if (r.url.encodedPath == "/api/session") wire else """{"watchlist":true}""")
        }
        val auth = WebAuthRepository(http); val a = auth.revalidate()!!
        val library = source(http, auth)
        val old = async { runCatching { library.states(a, ref) } }
        reached.await(); wire = sessionJson("B", "-9007199254740993"); auth.revalidate(); release.complete(Unit)
        val failure = old.await().exceptionOrNull() as WebProxyException
        assertEquals("stale_session", failure.code); assertEquals("B", auth.session.value!!.accountObjectId.value)
        assertFailsWith<WebProxyException> { library.write(a, ref, LibraryKind.WATCHLIST, LibraryState.Unknown.copy(watchlist = true)) }
    }

    @Test fun staleWriteRefetchesAndDropsWithoutRetargetingB() = runTest {
        var wire = sessionJson(); val writes = mutableListOf<HttpRequestData>()
        val http = mockHttp { r ->
            if (r.url.encodedPath == "/api/session") respond(wire)
            else { writes += r; wire = sessionJson("B", "2"); respond("""{"error":"stale_session"}""", HttpStatusCode.Conflict) }
        }
        val auth = WebAuthRepository(http); val a = auth.revalidate()!!
        val library = source(http, auth)
        val failure = assertFailsWith<WebProxyException> { library.write(a, ref, LibraryKind.WATCHLIST, LibraryState.Unknown.copy(watchlist = true)) }
        assertEquals(WebWriteOutcome.DefinitelyUnsent, failure.writeOutcome)
        assertEquals(1, writes.size); assertEquals("A", writes.single().headers["X-CV-Expected-Account"])
        assertEquals("B", auth.session.value!!.accountObjectId.value)
    }

    @Test fun tagged401RevalidatesButNeverLogsOutOrDestroysNewB() = runTest {
        var wire = sessionJson(); val paths = mutableListOf<String>()
        val http = mockHttp { r ->
            paths += r.url.encodedPath
            if (r.url.encodedPath == "/api/session") respond(wire)
            else { wire = sessionJson("B", "-3"); respond("""{"error":"session_expired","sessionGeneration":"9007199254740993","accountObjectId":"A"}""", HttpStatusCode.Unauthorized) }
        }
        val auth = WebAuthRepository(http); val a = auth.revalidate()!!
        assertFailsWith<WebProxyException> { source(http, auth).states(a, ref) }
        assertEquals("B", auth.session.value!!.accountObjectId.value)
        assertFalse(paths.any { it.contains("logout") })
    }

    @Test fun cancelledAndAmbiguousWritesInvalidateWithoutReplay() = runTest {
        val reached = CompletableDeferred<Unit>(); var writes = 0
        val http = mockHttp { r ->
            if (r.url.encodedPath == "/api/session") respond(sessionJson())
            else { writes++; reached.complete(Unit); awaitCancellation() }
        }
        val auth = WebAuthRepository(http); val a = auth.revalidate()!!; val library = source(http, auth)
        val write = launch { library.write(a, ref, LibraryKind.WATCHLIST, LibraryState.Unknown.copy(watchlist = true)) }
        reached.await(); write.cancelAndJoin()
        assertEquals(1, writes); assertEquals(1, library.libraryRevision.value)
        for ((status, code, certainty) in listOf(Triple(503, "upstream_busy", WebWriteOutcome.DefinitelyUnsent), Triple(504, "upstream_unavailable", WebWriteOutcome.PossiblyLanded))) {
            var count = 0
            val failedHttp = mockHttp { r ->
                if (r.url.encodedPath == "/api/session") respond(sessionJson())
                else { count++; respond("""{"error":"$code"}""", HttpStatusCode.fromValue(status)) }
            }
            val failedAuth = WebAuthRepository(failedHttp); val identity = failedAuth.revalidate()!!
            val failure = assertFailsWith<WebProxyException> { source(failedHttp, failedAuth).write(identity, ref, LibraryKind.RATING, LibraryState.Unknown.copy(rating = 7.5)) }
            assertEquals(certainty, failure.writeOutcome); assertEquals(1, count)
        }
    }

    @Test fun delayedWriteSuccessAfterIdentitySwitchIsPossiblyLandedNotUnsent() = runTest {
        var wire = sessionJson(); val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val http = mockHttp { r ->
            if (r.url.encodedPath == "/api/session") respond(wire)
            else { reached.complete(Unit); release.await(); respond("""{"success":true}""") }
        }
        val auth = WebAuthRepository(http); val a = auth.revalidate()!!; val library = source(http, auth)
        val write = async { runCatching { library.write(a, ref, LibraryKind.WATCHLIST, LibraryState.Unknown.copy(watchlist = true)) } }
        reached.await(); wire = sessionJson("B", "2"); auth.revalidate(); release.complete(Unit)
        assertEquals(WebWriteOutcome.PossiblyLanded, (write.await().exceptionOrNull() as WebProxyException).writeOutcome)
    }

    @Test fun malformedWriteSuccessIsPossiblyLandedAndStillInvalidates() = runTest {
        var writes = 0
        val http = mockHttp { r ->
            if (r.url.encodedPath == "/api/session") respond(sessionJson())
            else { writes++; respond("not-json") }
        }
        val auth = WebAuthRepository(http); val a = auth.revalidate()!!; val library = source(http, auth)
        val failure = assertFailsWith<WebProxyException> { library.write(a, ref, LibraryKind.WATCHLIST, LibraryState.Unknown.copy(watchlist = true)) }
        assertEquals(WebWriteOutcome.PossiblyLanded, failure.writeOutcome)
        assertEquals(1, writes); assertEquals(1, library.libraryRevision.value)
    }

    @Test fun externalSessionAndLibraryInvalidationDropInFlightReads() = runTest {
        for (sessionInvalidation in listOf(true, false)) {
            val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val http = mockHttp { r ->
                if (r.url.encodedPath == "/api/session") respond(sessionJson())
                else { reached.complete(Unit); release.await(); respond("{}") }
            }
            val auth = WebAuthRepository(http); val a = auth.revalidate()!!; val library = source(http, auth)
            val read = async { runCatching { library.states(a, ref) } }
            reached.await(); if (sessionInvalidation) auth.invalidateSession() else library.invalidateLists()
            release.complete(Unit)
            assertIs<WebProxyException>(read.await().exceptionOrNull())
        }
    }
}
