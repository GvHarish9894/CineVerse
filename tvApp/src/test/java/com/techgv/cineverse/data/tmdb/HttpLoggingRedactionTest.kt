package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.domain.MediaType
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §4.3: debug logging never shows bearer tokens, session ids or auth bodies. */
class HttpLoggingRedactionTest {
    private val lines = mutableListOf<String>()
    private var accountBody = """{"id":548,"username":"harish"}"""
    private fun api(bodyPreviewBytes: Long) = TmdbClient.api(
        OkHttpClient.Builder()
            .addInterceptor(TmdbBearerInterceptor(ReadToken("READ-SECRET")))
            .addInterceptor(tmdbLoggingInterceptor(bodyPreviewBytes) { lines += it })
            .addInterceptor(Interceptor { chain ->
                val body = if (isAuthEndpoint(chain.request().url)) {
                    """{"access_token":"USER-SECRET","account_id":"aoid","session_id":"SESSION-SECRET"}"""
                } else {
                    accountBody
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody()).build()
            })
            .build(),
    )

    @Test
    fun `secrets never reach the log, even with body previews on`() = runTest {
        val api = api(BODY_PREVIEW_BYTES)
        api.account("SESSION-SECRET")
        api.createAccessToken(AccessTokenBody("REQUEST-SECRET"))
        api.convertToSession(ConvertSessionBody("USER-SECRET"))
        api.accountList(UserBearer(UserAccessToken("USER-SECRET")), "aoid", MediaType.Tv, AccountList.Rated, "en-US", 1)
        val log = lines.joinToString("\n")
        listOf("READ-SECRET", "USER-SECRET", "SESSION-SECRET", "REQUEST-SECRET").forEach { assertFalse(it, log.contains(it)) }
        assertTrue(log.contains("Authorization: ██"))
        assertTrue(log.contains("\"username\":\"harish\"")) // non-auth bodies are previewed
    }

    @Test
    fun `bodies are off by default and an opted-in preview is bounded`() = runTest {
        accountBody = """{"id":548,"username":"${"x".repeat(10_000)}"}"""
        api(bodyPreviewBytes = 0).account("s")
        assertFalse(lines.joinToString("\n").contains("xxxx"))

        api(BODY_PREVIEW_BYTES).account("s")
        val prefix = "<-- body (first $BODY_PREVIEW_BYTES bytes): "
        assertEquals(BODY_PREVIEW_BYTES.toInt(), lines.single { it.startsWith(prefix) }.removePrefix(prefix).length)
    }
}
