package com.techgv.cineverse.data.tmdb

import io.ktor.client.call.save
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlin.time.TimeSource

/** Body preview size when opted in: logging whole bodies (~880 KB TV details) stalled loads on device. */
const val BODY_PREVIEW_BYTES = 2048L

private const val REDACTED = "██"
private val sessionIdValue = Regex("([?&]session_id=)[^&#]*")

class TmdbHttpLoggingConfig {
    /** Where the lines go (debug builds only, §4.3). */
    var log: (String) -> Unit = {}

    /** > 0: also log the first that many bytes of each non-auth response body. */
    var bodyPreviewBytes: Long = 0
}

/**
 * Debug HTTP log (§4.3): request / response lines and headers, `Authorization` and `session_id` values redacted;
 * response bodies only when opted in, cut to [TmdbHttpLoggingConfig.bodyPreviewBytes], never for auth endpoints.
 * A failed call logs only the exception type (engine messages may contain the URL).
 */
val TmdbHttpLogging = createClientPlugin("TmdbHttpLogging", ::TmdbHttpLoggingConfig) {
    val log = pluginConfig.log
    val previewBytes = pluginConfig.bodyPreviewBytes
    on(Send) { request ->
        val method = request.method.value
        val url = request.url.buildString().replace(sessionIdValue, "$1$REDACTED")
        log("--> $method $url")
        logHeaders(request.headers.build(), log)
        log("--> END $method")
        val started = TimeSource.Monotonic.markNow()
        val call = try {
            proceed(request)
        } catch (e: Throwable) {
            log("<-- HTTP FAILED: ${e::class.simpleName}")
            throw e
        }
        val response = call.response
        log("<-- ${response.status.value} $url (${started.elapsedNow().inWholeMilliseconds}ms)")
        logHeaders(response.headers, log)
        log("<-- END HTTP")
        if (previewBytes <= 0 || isAuthEndpoint(request.url.build().encodedPath)) return@on call
        val saved = call.save() // the preview must not consume the body the API reads
        val preview = saved.response.bodyAsChannel().readRemaining(previewBytes).readByteArray().decodeToString()
        log("<-- body (first $previewBytes bytes): $preview")
        saved
    }
}

private fun logHeaders(headers: Headers, log: (String) -> Unit) = headers.forEach { name, values ->
    log("$name: " + if (name.equals(HttpHeaders.Authorization, ignoreCase = true)) REDACTED else values.joinToString())
}
