package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.data.tmdb.TmdbOperation.AccessTokenExchange
import com.techgv.cineverse.data.tmdb.TmdbOperation.Catalog
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** The Retrofit-bound half of §3.3 (TmdbCalls.kt); the table itself is tested in :shared (TmdbErrorTest). */
class TmdbCallsTest {
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
