package com.techgv.cineverse.data.web

import com.techgv.cineverse.data.auth.AccountDefaultsMetadata
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.TmdbError
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlin.test.*

private fun defaultsJson(account: String = "A", generation: String = "9007199254740993", fields: String = "") =
    """{"accountObjectId":"$account","sessionGeneration":"$generation"$fields}"""

class WebAccountDefaultsTest {
    @Test fun signed64ExtremesAreStringOnlyAndMalformedMetadataIsRejected() {
        for (generation in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0, 9007199254740993, -9007199254740993)) {
            assertEquals(generation, decodeWebAccountDefaults(defaultsJson(generation = generation.toString())).sessionGeneration)
        }
        for (generation in listOf("1.0", "1e3", "+1", "01", "", "9223372036854775808", "-9223372036854775809", " 1")) {
            assertFailsWith<SerializationException> { decodeWebAccountDefaults(defaultsJson(generation = generation)) }
        }
        for (numeric in listOf("0", "9007199254740993", "null", "true")) {
            assertFailsWith<SerializationException> {
                decodeWebAccountDefaults(defaultsJson().replace("\"9007199254740993\"", numeric))
            }
        }
        for (body in listOf("[]", "{}", defaultsJson(account = ""),
            defaultsJson(fields = ",\"language\":12"), defaultsJson(fields = ",\"region\":false"),
            defaultsJson(fields = ",\"includeAdult\":\"false\""), defaultsJson(fields = ",\"includeAdult\":0"))) {
            assertFailsWith<SerializationException> { decodeWebAccountDefaults(body) }
        }
    }

    @Test fun optionalMetadataPreservesAbsentNullFalseAndUnsupportedValues() {
        val unavailable = AccountDefaultsMetadata(AccountObjectId("A"), 9007199254740993, null, null, null)
        assertEquals(unavailable, decodeWebAccountDefaults(defaultsJson()))
        assertEquals(unavailable, decodeWebAccountDefaults(defaultsJson(fields = ",\"language\":null,\"region\":null,\"includeAdult\":null")))
        assertEquals(unavailable.copy(language = "fr", includeAdult = false),
            decodeWebAccountDefaults(defaultsJson(fields = ",\"language\":\"fr\",\"includeAdult\":false,\"future\":42")))
    }

    @Test fun capturedExpectedIdentityIsSentExactlyWithoutCsrfOrCredentials() = runTest {
        val calls = mutableListOf<HttpRequestData>()
        var generation = Long.MIN_VALUE
        val auth = WebAuthRepository(mockHttp { request ->
            calls += request
            respond(if (request.url.encodedPath == "/api/session") sessionJson(generation = generation.toString())
                else defaultsJson(generation = generation.toString(), fields = ",\"includeAdult\":false"))
        })
        for (value in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            generation = value
            val a = auth.revalidate()!!
            assertEquals(false, auth.accountDefaults(a).includeAdult)
            val request = calls.last()
            assertEquals("/api/account/defaults", request.url.encodedPath)
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(value.toString(), request.headers["X-CV-Expected-Session-Generation"])
            assertEquals("A", request.headers["X-CV-Expected-Account"])
            assertTrue(request.url.parameters.isEmpty())
            assertNull(request.headers["X-CV-CSRF"])
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers[HttpHeaders.Cookie])
        }
    }

    @Test fun captureThenOtherLoginOrSameAccountNewGenerationRejectsBeforeDispatch() = runTest {
        var wire = sessionJson()
        var reads = 0
        val auth = WebAuthRepository(mockHttp { request ->
            if (request.url.encodedPath == "/api/account/defaults") reads++
            respond(wire)
        })
        val a = auth.revalidate()!!
        for (newSession in listOf(sessionJson("B", "2"), sessionJson("A", "3"))) {
            wire = newSession
            auth.revalidate()
            assertEquals("stale_session", assertFailsWith<WebProxyException> { auth.accountDefaults(a) }.code)
        }
        assertEquals(0, reads)
        auth.invalidateSession()
        assertFailsWith<WebProxyException> { auth.accountDefaults(auth.session.value!!) }
        assertEquals(0, reads)
    }

    @Test fun delayedOldMetadataCannotPublishAfterIdentityOrAuthEpochChanges() = runTest {
        for (replacement in listOf(sessionJson("B", "2"), sessionJson("A", "3"), sessionJson())) {
            var wire = sessionJson()
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val auth = WebAuthRepository(mockHttp { request ->
                if (request.url.encodedPath == "/api/session") respond(wire)
                else { reached.complete(Unit); release.await(); respond(defaultsJson()) }
            })
            val a = auth.revalidate()!!
            val old = async { assertFailsWith<WebProxyException> { auth.accountDefaults(a) } }
            reached.await()
            auth.invalidateSession() // Includes a same-identity revalidation after an auth hint.
            wire = replacement
            val current = auth.revalidate()
            release.complete(Unit)
            assertEquals("stale_session", old.await().code)
            assertEquals(current, auth.session.value)
            assertTrue(auth.isVerifiedSession(current))
        }
    }

    @Test fun responseBindingMismatchIsNeverDeliveredAsExpectedAccount() = runTest {
        for (body in listOf(defaultsJson("B"), defaultsJson(generation = "2"))) {
            val auth = WebAuthRepository(mockHttp { request ->
                respond(if (request.url.encodedPath == "/api/session") sessionJson() else body)
            })
            val a = auth.revalidate()!!
            assertEquals("stale_session", assertFailsWith<WebProxyException> { auth.accountDefaults(a) }.code)
            assertEquals(a, auth.session.value)
        }
    }

    @Test fun malformedNetworkAndRateLimitFailuresDoNotUndoCommittedSignInOrRetry() = runTest {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
            var reads = 0
            val auth = WebAuthRepository(mockHttp { request ->
                if (request.url.encodedPath == "/api/session") respond(sessionJson())
                else { reads++; respond("SECRET", status, headersOf(HttpHeaders.RetryAfter, "2")) }
            })
            val a = auth.onApprovalReturned()!!
            val failure = assertFailsWith<WebProxyException> { auth.accountDefaults(a) }
            assertEquals(if (status == HttpStatusCode.OK) "malformed_response" else if (status.value == 429) "rate_limited" else "unknown_error", failure.code)
            assertEquals(WebWriteOutcome.NotApplicable, failure.writeOutcome)
            assertFalse(failure.toString().contains("SECRET")); assertNull(failure.cause)
            assertEquals(a, auth.session.value)
            assertTrue(auth.isVerifiedSession(a))
            assertEquals(1, reads)
        }
        val auth = WebAuthRepository(mockHttp { request ->
            if (request.url.encodedPath == "/api/session") respond(sessionJson()) else error("SECRET")
        })
        val a = auth.revalidate()!!
        assertEquals(TmdbError.Network, assertFailsWith<WebProxyException> { auth.accountDefaults(a) }.error)
        assertEquals(a, auth.session.value)
        assertTrue(auth.isVerifiedSession(a))
    }

    @Test fun protectedErrorsRevalidateButOnlyGenuineExpiryEmitsAndCancellationPropagates() = runTest {
        for (code in listOf("stale_session", "session_expired", "not_signed_in")) {
            var wire = sessionJson()
            val auth = WebAuthRepository(mockHttp { request ->
                if (request.url.encodedPath == "/api/session") respond(wire)
                else { wire = GUEST; respond("""{"error":"$code"}""", HttpStatusCode.Unauthorized) }
            })
            val a = auth.revalidate()!!
            var expiries = 0
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { auth.sessionExpired.collect { expiries++ } }
            assertEquals(code, assertFailsWith<WebProxyException> { auth.accountDefaults(a) }.code)
            yield()
            assertNull(auth.session.value)
            assertTrue(auth.isVerifiedSession(null))
            assertEquals(if (code == "session_expired") 1 else 0, expiries)
            collector.cancelAndJoin()
        }
        val auth = WebAuthRepository(mockHttp { request ->
            if (request.url.encodedPath == "/api/session") respond(sessionJson()) else throw CancellationException()
        })
        val a = auth.revalidate()!!
        assertFailsWith<CancellationException> { auth.accountDefaults(a) }
        assertEquals(a, auth.session.value)
    }
}
