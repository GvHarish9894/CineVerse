package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.data.tmdb.TmdbOperation.AccessTokenExchange
import com.techgv.cineverse.data.tmdb.TmdbOperation.Catalog
import com.techgv.cineverse.data.tmdb.TmdbOperation.Session
import com.techgv.cineverse.data.tmdb.TmdbOperation.UserToken
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

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
        Row(AccessTokenExchange, 401, 17, TmdbError.ExchangeRejected),
        Row(AccessTokenExchange, 401, 35, TmdbError.ExchangeRejected),
        Row(AccessTokenExchange, 400, null, TmdbError.ExchangeRejected), // unparsable body
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
            assertEquals(row.toString(), row.expected, classifyTmdbError(row.op, row.http, row.status, row.retryAfter))
        }
    }

    @Test
    fun `HttpException body status_code and Retry-After are parsed`() {
        val pending = HttpException(Response.error<Unit>(422, """{"success":false,"status_code":41,"status_message":"x"}""".toResponseBody()))
        assertEquals(TmdbError.ApprovalPending, pending.toTmdbError(AccessTokenExchange))
        val garbage = HttpException(Response.error<Unit>(401, "<html>".toResponseBody()))
        assertEquals(TmdbError.AppCredential, garbage.toTmdbError(Catalog))
        assertEquals(TmdbError.Network, IOException().toTmdbError(Catalog))
    }

    @Test
    fun `one retry after Retry-After outside the exchange, then the error surfaces`() = runTest {
        var calls = 0
        try {
            tmdbCall(Catalog) { calls++; throw rateLimited(retryAfterSeconds = 4) }
            fail()
        } catch (e: TmdbException) {
            assertEquals(TmdbError.RateLimited(4_000), e.error)
        }
        assertEquals(2, calls)
        assertEquals(4_000, currentTime)

        calls = 0
        try {
            tmdbCall(AccessTokenExchange) { calls++; throw rateLimited(retryAfterSeconds = 4) }
            fail()
        } catch (e: TmdbException) {
            assertEquals(1, calls) // the polling loop owns the exchange's waiting
        }
    }

    private fun rateLimited(retryAfterSeconds: Long) = HttpException(
        Response.error<Unit>(
            "".toResponseBody(),
            okhttp3.Response.Builder()
                .request(okhttp3.Request.Builder().url("https://api.themoviedb.org/3/x").build())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(429).message("Too Many Requests")
                .header("Retry-After", retryAfterSeconds.toString())
                .build(),
        ),
    )
}
