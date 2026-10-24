package com.techgv.cineverse.data.web

import com.techgv.cineverse.data.auth.RequestCode
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.first
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WebAuthRepositoryTest {
    @Test fun verificationQueryDistinguishesInitialNullInvalidatedIdentityAndVerifiedTruth() = runTest {
        var wire = GUEST
        val auth = WebAuthRepository(mockHttp { respond(wire) })
        assertFalse(auth.isVerifiedSession(null))
        auth.revalidate()
        assertTrue(auth.isVerifiedSession(null))
        wire = sessionJson()
        val a = auth.revalidate()!!
        assertTrue(auth.isVerifiedSession(a)); assertFalse(auth.isVerifiedSession(null))
        auth.invalidateSession()
        assertEquals(a, auth.session.value)
        assertFalse(auth.isVerifiedSession(a))
        wire = sessionJson("B", "-9007199254740993")
        val b = auth.revalidate()!!
        assertTrue(auth.isVerifiedSession(b)); assertFalse(auth.isVerifiedSession(a))
        wire = GUEST
        auth.revalidate()
        assertTrue(auth.isVerifiedSession(null)); assertFalse(auth.isVerifiedSession(b))
    }

    @Test fun lateSessionSnapshotCannotOverwriteNewerTruth() = runTest {
        var count = 0; val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val auth = WebAuthRepository(mockHttp {
            count++
            if (count == 1) { reached.complete(Unit); release.await(); respond(sessionJson()) }
            else respond(sessionJson("B", "-9007199254740993"))
        })
        val old = async { auth.revalidate() }; reached.await()
        auth.revalidate(); release.complete(Unit); old.await()
        assertEquals("B", auth.session.value!!.accountObjectId.value)
    }

    @Test fun invalidationPreventsLateSnapshotFromBecomingTruth() = runTest {
        val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val auth = WebAuthRepository(mockHttp { reached.complete(Unit); release.await(); respond(sessionJson()) })
        val old = async { auth.revalidate() }; reached.await(); auth.invalidateSession(); release.complete(Unit); old.await()
        assertNull(auth.session.value)
    }

    @Test fun browserApprovalUsesOnlyServerDescriptorAndSessionTruth() = runTest {
        var wire = GUEST; val requests = mutableListOf<HttpRequestData>()
        val auth = WebAuthRepository(mockHttp { r ->
            requests += r
            respond(when (r.url.encodedPath) {
                "/api/session" -> wire
                "/api/auth/start" -> """{"approveUrl":"https://www.themoviedb.org/auth/access?request_token=SERVER-SECRET","attemptId":"server-id"}"""
                else -> ""
            })
        })
        val attempt = auth.startApproval()
        assertEquals("server-id", attempt.attemptId)
        assertTrue(attempt.approveUrl.endsWith("SERVER-SECRET")); assertFalse(attempt.toString().contains("SERVER-SECRET"))
        assertNull(auth.onApprovalReturned(), "callback hint does not mean approved")
        wire = sessionJson(); val a = auth.onApprovalReturned()!!
        auth.cancelApproval()
        assertEquals(a, auth.session.value, "cancel does not undo a committed sign-in")
        assertEquals("/api/auth/cancel", requests[requests.lastIndex - 1].url.encodedPath)
        assertTrue(requests[requests.lastIndex - 1].url.parameters.isEmpty(), "cancel is browser-wide, not scoped to attempt")
        assertFailsWith<UnsupportedOperationException> { auth.createRequestCode() }
        assertFailsWith<UnsupportedOperationException> { auth.awaitApproval(RequestCode("never-exchanged")) }
        assertFalse(requests.any { it.url.encodedPath.contains("callback") || it.url.parameters["session_id"] != null })
    }

    @Test fun logoutLateSignedOutBodyDoesNotOverwriteNewCookieTruth() = runTest {
        var wire = sessionJson(); val paths = mutableListOf<String>()
        val auth = WebAuthRepository(mockHttp { r ->
            paths += r.url.encodedPath
            if (r.url.encodedPath.endsWith("logout")) { wire = sessionJson("B", "2"); respond(GUEST) }
            else respond(wire)
        })
        auth.revalidate(); var locallySignedOut = false
        auth.signOut { locallySignedOut = true }
        assertEquals("B", auth.session.value!!.accountObjectId.value); assertFalse(locallySignedOut)
        assertEquals(listOf("/api/session", "/api/auth/logout", "/api/session"), paths)
    }

    @Test fun genuineExpiryPublishesGuestOnlyAfterSessionRevalidation() = runTest {
        var wire = sessionJson(); val paths = mutableListOf<String>()
        val auth = WebAuthRepository(mockHttp { r -> paths += r.url.encodedPath; respond(wire) })
        val a = auth.revalidate()!!
        val expired = async(start = CoroutineStart.UNDISPATCHED) { auth.sessionExpired.first() }
        wire = GUEST; auth.expireSession(a); expired.await()
        assertNull(auth.session.value)
        auth.expireSession(a)
        assertEquals(listOf("/api/session", "/api/session"), paths)
    }

    @Test fun bufferedExpiryOfAIsDiscardedAfterBSignsIn() = runTest {
        var wire = sessionJson("P", "1")
        val auth = WebAuthRepository(mockHttp { respond(wire) })
        val first = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var slowReceived = 0; var fastReceived = 0
        val slow = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            auth.sessionExpired.collect {
                slowReceived++
                if (slowReceived == 1) { first.complete(Unit); release.await() }
            }
        }
        val fast = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            auth.sessionExpired.collect { fastReceived++ }
        }
        try {
            val p = auth.revalidate()!!
            wire = GUEST; auth.expireSession(p); first.await(); runCurrent()
            wire = sessionJson("A", "9007199254740993"); val a = auth.revalidate()!!
            wire = GUEST; auth.expireSession(a); runCurrent()
            assertEquals(2, fastReceived, "each live subscriber receives genuine expiries")
            wire = sessionJson("B", "-9007199254740993"); auth.revalidate()
            release.complete(Unit); runCurrent()
            assertEquals("B", auth.session.value!!.accountObjectId.value)
            assertEquals(1, slowReceived, "A's buffered notice is filtered at delivery after B")
        } finally { release.complete(Unit); slow.cancelAndJoin(); fast.cancelAndJoin() }
    }

    @Test fun bufferedGenuineExpirySurvivesUnchangedGuestRevalidation() = runTest {
        var wire = sessionJson("P", "1")
        val auth = WebAuthRepository(mockHttp { respond(wire) })
        val first = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var received = 0
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            auth.sessionExpired.collect {
                received++
                if (received == 1) { first.complete(Unit); release.await() }
            }
        }
        try {
            val p = auth.revalidate()!!
            wire = GUEST; auth.expireSession(p); first.await()
            wire = sessionJson("A", "2"); val a = auth.revalidate()!!
            wire = GUEST; auth.expireSession(a)
            auth.revalidate()
            release.complete(Unit); runCurrent()
            assertEquals(2, received, "a request revision alone is not an auth transition")
            assertNull(auth.session.value)
        } finally { release.complete(Unit); collector.cancelAndJoin() }
    }

    @Test fun approvalRejectsUntrustedDescriptorsWithoutLeakingResponse() = runTest {
        for (url in listOf("https://evil.invalid/SECRET", "https://www.themoviedb.org:444/auth/access?request_token=SECRET", "javascript:SECRET")) {
            val auth = WebAuthRepository(mockHttp { r ->
                respond(if (r.url.encodedPath == "/api/session") GUEST else """{"approveUrl":"$url","attemptId":"server-id"}""")
            })
            val error = assertFailsWith<WebProxyException> { auth.startApproval() }
            assertEquals("malformed_approval", error.code); assertFalse(error.toString().contains("SECRET")); assertNull(error.cause)
        }
    }

    @Test fun cancellationNeverBecomesSignedOutOrNativeExchange() = runTest {
        var cancel = false
        val auth = WebAuthRepository(mockHttp { if (cancel) throw CancellationException() else respond(sessionJson()) })
        val a = auth.revalidate(); cancel = true
        assertFailsWith<CancellationException> { auth.revalidate() }
        assertEquals(a, auth.session.value)
    }
}
