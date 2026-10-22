package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.domain.MediaType
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §4.3: debug logging never shows bearer tokens, session ids or auth bodies. */
class HttpLoggingRedactionTest {
    private val lines = mutableListOf<String>()
    private var accountBody = """{"id":548,"username":"harish"}"""

    private fun tmdb(bodyPreviewBytes: Long) = MockTmdb(
        readToken = "READ-SECRET",
        configure = {
            install(TmdbHttpLogging) {
                log = { lines += it }
                this.bodyPreviewBytes = bodyPreviewBytes
            }
        },
    ) { request ->
        if (isAuthEndpoint(request.url.encodedPath)) {
            ok("""{"access_token":"USER-SECRET","account_id":"aoid","session_id":"SESSION-SECRET"}""")
        } else {
            ok(accountBody)
        }
    }

    @Test
    fun secretsNeverReachTheLogEvenWithBodyPreviewsOn() = runTest {
        val api = tmdb(BODY_PREVIEW_BYTES).api
        assertEquals("harish", api.account("SESSION-SECRET").username) // the preview leaves the body readable
        api.createAccessToken(AccessTokenBody("REQUEST-SECRET"))
        api.convertToSession(ConvertSessionBody("USER-SECRET"))
        api.accountList(UserBearer(UserAccessToken("USER-SECRET")), "aoid", MediaType.Tv, AccountList.Rated, "en-US", 1)
        val log = lines.joinToString("\n")
        listOf("READ-SECRET", "USER-SECRET", "SESSION-SECRET", "REQUEST-SECRET").forEach { assertFalse(log.contains(it), it) }
        assertTrue(log.contains("Authorization: ██"))
        assertTrue(log.contains("session_id=██"))
        assertTrue(log.contains("\"username\":\"harish\"")) // non-auth bodies are previewed
    }

    @Test
    fun bodiesAreOffByDefaultAndAnOptedInPreviewIsBounded() = runTest {
        accountBody = """{"id":548,"username":"${"x".repeat(10_000)}"}"""
        tmdb(bodyPreviewBytes = 0).api.account("s")
        assertFalse(lines.joinToString("\n").contains("xxxx"))

        tmdb(BODY_PREVIEW_BYTES).api.account("s")
        val prefix = "<-- body (first $BODY_PREVIEW_BYTES bytes): "
        assertEquals(BODY_PREVIEW_BYTES.toInt(), lines.single { it.startsWith(prefix) }.removePrefix(prefix).length)
    }

    @Test
    fun errorResponsesAndFailuresAreLoggedWithoutSecrets() = runTest {
        val failing = MockTmdb(
            readToken = "READ-SECRET",
            configure = { install(TmdbHttpLogging) { log = { lines += it } } },
        ) { ok("""{"status_code":3}""", HttpStatusCode.Unauthorized) }
        runCatching { failing.api.accountStates(MediaType.Movie, 550, "SESSION-SECRET") }
        val log = lines.joinToString("\n")
        assertTrue(log.contains("<-- 401"), log)
        assertFalse(log.contains("SESSION-SECRET"))
        assertFalse(log.contains("READ-SECRET"))
    }
}
