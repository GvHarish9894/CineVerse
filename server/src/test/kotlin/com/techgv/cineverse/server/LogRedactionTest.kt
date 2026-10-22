package com.techgv.cineverse.server

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRedactionTest {
    /** slf4j-simple writes to the current System.err (debug level in tests, see build.gradle.kts). */
    private fun captureLogs(block: () -> Unit): String {
        val original = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return buffer.toString()
    }

    @Test
    fun noCredentialEverReachesLogsOrResponses() {
        val fake = FakeTmdb()
        val secrets = mutableSetOf(READ_TOKEN, "UAT-A", "SID-A")
        val responses = StringBuilder()
        val logs = captureLogs {
            serverTest(fake) {
                val alice = browser()
                suspend fun record(response: io.ktor.client.statement.HttpResponse) {
                    responses.append(response.headers.entries()).append(response.bodyAsText())
                }
                val (attemptId, requestToken) = alice.start()
                secrets += requestToken
                secrets += alice.cookies.getValue(ATTEMPT_COOKIE)
                fake.approve(requestToken, "A")
                record(alice.callback(attemptId))
                secrets += alice.cookies.getValue(SESSION_COOKIE)
                secrets += alice.cookies.getValue(CSRF_COOKIE)
                val a = alice.session()

                record(alice.get("/api/session"))
                record(alice.get("/api/tmdb/movie/550?append_to_response=credits"))
                record(alice.get("/api/account/movie/watchlist?page=1"))
                record(alice.get("/api/account/states/movie/550"))
                record(alice.send(HttpMethod.Post, "/api/account/watchlist", """{"media_type":"movie","media_id":550,"watchlist":true}""", session = a))
                // Failure paths: Ktor client exception messages contain the upstream URL with session_id.
                fake.overrides["POST /3/movie/550/rating"] = { throw IOException("failed ${it.url}") }
                record(alice.send(HttpMethod.Post, "/api/account/rating/movie/550", """{"value":6}""", session = a))
                fake.overrides["GET /3/movie/550/account_states"] = { error("boom ${it.url} ${it.authorization}") }
                record(alice.get("/api/account/states/movie/550"))
                record(alice.get("/api/tmdb/movie/550/account_states?session_id=SID-A"))
                fake.overrides["DELETE /4/auth/access_token"] = { throw IOException("failed ${it.authorization}") }
                record(alice.send(HttpMethod.Post, "/api/auth/logout"))
            }
        }
        assertTrue(logs.contains("POST /api/account/watchlist 201"), "access log is captured:\n$logs")
        assertTrue(logs.contains("access token revocation failed"), logs)
        for (secret in secrets) {
            assertFalse(logs.contains(secret), "secret leaked into logs: ${secret.take(6)}…\n$logs")
        }
        // TMDB credentials never reach the browser (cookies only carry opaque ids).
        listOf(READ_TOKEN, "UAT-A", "SID-A").forEach { assertFalse(responses.contains(it), it) }
    }
}
