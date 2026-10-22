package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.data.tmdb.TmdbOperation.AccessTokenExchange
import com.techgv.cineverse.data.tmdb.TmdbOperation.Catalog
import com.techgv.cineverse.data.tmdb.TmdbOperation.Session
import com.techgv.cineverse.data.tmdb.TmdbOperation.UserToken
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class TmdbErrorTest {
    private data class Row(val op: TmdbOperation, val http: Int, val status: Int?, val expected: TmdbError, val retryAfter: Long? = null)

    /** §3.3, row by row. */
    private val table = listOf(
        // exchange (polling)
        Row(AccessTokenExchange, 422, 41, TmdbError.ApprovalPending),
        Row(AccessTokenExchange, 401, 33, TmdbError.RequestTokenExpired),
        Row(AccessTokenExchange, 401, 7, TmdbError.AppCredential),
        Row(AccessTokenExchange, 401, 10, TmdbError.AppCredential),
        Row(AccessTokenExchange, 429, 25, TmdbError.RateLimited(5_000), retryAfter = 5),
        Row(AccessTokenExchange, 429, null, TmdbError.RateLimited(3_000), retryAfter = 1), // min 3 s
        Row(AccessTokenExchange, 429, null, TmdbError.RateLimited(3_000)),
        Row(AccessTokenExchange, 401, 17, TmdbError.ExchangeRejected(401, 17)),
        Row(AccessTokenExchange, 401, 35, TmdbError.ExchangeRejected(401, 35)),
        Row(AccessTokenExchange, 400, null, TmdbError.ExchangeRejected(400, null)), // unparsable body
        Row(AccessTokenExchange, 503, null, TmdbError.Server(503)),
        // any call: app key, never a sign-out
        Row(Session, 401, 7, TmdbError.AppCredential),
        Row(UserToken, 401, 10, TmdbError.AppCredential),
        // session_id calls
        Row(Session, 401, 3, TmdbError.SessionExpired),
        Row(Session, 401, 14, TmdbError.SessionExpired),
        Row(Session, 401, 17, TmdbError.SessionExpired),
        Row(Session, 404, 37, TmdbError.SessionExpired),
        Row(Catalog, 401, 3, TmdbError.Http(401, 3)), // no session on the call: not a sign-out
        // user-token calls
        Row(UserToken, 401, 35, TmdbError.SessionExpired),
        Row(UserToken, 401, 36, TmdbError.SessionExpired),
        Row(Session, 401, 36, TmdbError.Http(401, 36)),
        // generic
        Row(Catalog, 429, null, TmdbError.RateLimited(2_000), retryAfter = 2),
        Row(Catalog, 404, 34, TmdbError.NotFound),
        Row(Catalog, 500, 11, TmdbError.Server(500)),
        Row(Catalog, 401, null, TmdbError.AppCredential), // bare 401 outside the exchange
        Row(Catalog, 422, 22, TmdbError.Http(422, 22)),
    )

    @Test
    fun `classification table`() {
        table.forEach { row ->
            assertEquals(row.expected, classifyTmdbError(row.op, row.http, row.status, row.retryAfter), row.toString())
        }
    }

    // --- from real Ktor responses: TMDB `status_code` body, `Retry-After`, bare 401, network, malformed 2xx ---

    private suspend fun errorOf(operation: TmdbOperation, tmdb: MockTmdb): TmdbError = try {
        tmdbCall(operation) { tmdb.api.createAccessToken(AccessTokenBody("rt")) }
        fail("expected a TmdbException")
    } catch (e: TmdbException) {
        e.error
    }

    @Test
    fun statusCodeBodyBareUnauthorizedNetworkAndMalformedAreClassified() = runTest {
        val pending = MockTmdb { ok("""{"success":false,"status_code":41,"status_message":"x"}""", HttpStatusCode.UnprocessableEntity) }
        assertEquals(TmdbError.ApprovalPending, errorOf(AccessTokenExchange, pending))
        val bare401 = MockTmdb { ok("<html>", HttpStatusCode.Unauthorized) }
        assertEquals(TmdbError.AppCredential, errorOf(Catalog, bare401))
        assertEquals(TmdbError.ExchangeRejected(401, null), errorOf(AccessTokenExchange, bare401))
        val offline = MockTmdb { throw IOException("offline") }
        assertEquals(TmdbError.Network, errorOf(Catalog, offline))
        val malformed = MockTmdb { ok("""{"unexpected":true}""") } // access_token / account_id missing
        assertEquals(TmdbError.Malformed, errorOf(AccessTokenExchange, malformed))
        val notFound = MockTmdb { ok("""{"status_code":34}""", HttpStatusCode.NotFound) }
        assertEquals(TmdbError.NotFound, errorOf(Catalog, notFound))
    }

    @Test
    fun oneRetryAfterRetryAfterOutsideTheExchangeThenTheErrorSurfaces() = runTest(StandardTestDispatcher()) {
        val limited = MockTmdb(dispatcher = StandardTestDispatcher(testScheduler)) {
            ok("""{"status_code":25}""", HttpStatusCode.TooManyRequests, "Retry-After" to "4")
        }
        assertEquals(TmdbError.RateLimited(4_000), errorOf(Catalog, limited))
        assertEquals(2, limited.requests.size)
        assertEquals(4_000, currentTime)

        limited.requests.clear()
        assertEquals(TmdbError.RateLimited(4_000), errorOf(AccessTokenExchange, limited))
        assertEquals(1, limited.requests.size) // the polling loop owns the exchange's waiting
    }
}
