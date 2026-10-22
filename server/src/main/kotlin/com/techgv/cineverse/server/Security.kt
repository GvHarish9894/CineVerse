package com.techgv.cineverse.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes

const val CSRF_COOKIE = "cv_csrf"
const val CSRF_HEADER = "X-CV-CSRF"
const val EXPECTED_GENERATION_HEADER = "X-CV-Expected-Session-Generation"
const val EXPECTED_ACCOUNT_HEADER = "X-CV-Expected-Account"
const val MAX_BODY_BYTES = 1024

val AUTH_BY_IP = RateLimitName("auth-ip")
val AUTH_BY_SESSION = RateLimitName("auth-session")
val MUTATION_BY_IP = RateLimitName("mutation-ip")
val MUTATION_BY_SESSION = RateLimitName("mutation-session")

/** API JSON; strict when decoding client bodies (unknown keys are rejected). */
val apiJson = Json

fun ApplicationCall.clientIp(): String = request.origin.remoteAddress

fun Application.installSecurity(config: ServerConfig) {
    install(RateLimit) {
        fun register(name: RateLimitName, perMinute: Int, key: (ApplicationCall) -> String) = register(name) {
            rateLimiter(limit = perMinute, refillPeriod = 1.minutes)
            requestKey { key(it) }
        }
        // Without a session cookie the session limiter falls back to the client IP.
        val sessionKey = { call: ApplicationCall -> call.request.cookies[SESSION_COOKIE] ?: "ip:${call.clientIp()}" }
        register(AUTH_BY_IP, config.authRequestsPerMinute) { it.clientIp() }
        register(AUTH_BY_SESSION, config.authRequestsPerMinute, sessionKey)
        register(MUTATION_BY_IP, config.mutationRequestsPerMinute * 2) { it.clientIp() }
        register(MUTATION_BY_SESSION, config.mutationRequestsPerMinute, sessionKey)
    }
    install(SecurityHeaders) { hsts = config.publicOrigin.startsWith("https://") }

    // Every failure leaves as a sanitized JSON error. Exception messages are never logged: Ktor client messages
    // can contain the upstream URL, which carries `session_id` on v3 account calls.
    intercept(ApplicationCallPipeline.Plugins) {
        try {
            proceed()
        } catch (e: ApiError) {
            call.respondError(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            call.application.log.warn("upstream unavailable: ${e::class.simpleName} on ${call.request.path()}")
            call.respondError(ApiError(HttpStatusCode.GatewayTimeout, "upstream_unavailable"))
        } catch (e: Exception) {
            call.application.log.error("unhandled ${e::class.simpleName} on ${call.request.path()}")
            call.respondError(ApiError(HttpStatusCode.InternalServerError, "internal"))
        }
    }
}

private class SecurityHeadersConfig { var hsts = false }

/**
 * Security headers + a redacted access log (method, path, status — never query, headers, cookies or bodies).
 * API responses default to `private, no-store`; only catalog hits set a public `Cache-Control` themselves.
 */
private val SecurityHeaders = createApplicationPlugin("SecurityHeaders", ::SecurityHeadersConfig) {
    val hsts = pluginConfig.hsts
    onCall { call ->
        val api = call.request.path().startsWith("/api/")
        with(call.response) {
            header("X-Content-Type-Options", "nosniff")
            header("X-Frame-Options", "DENY")
            header("Cross-Origin-Opener-Policy", "same-origin-allow-popups")
            if (hsts) header("Strict-Transport-Security", "max-age=31536000")
            if (api) {
                header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                header("Referrer-Policy", "no-referrer")
            } else {
                header("Content-Security-Policy", "frame-ancestors 'none'")
                // YouTube embeds need at least the origin as referrer.
                header("Referrer-Policy", "strict-origin-when-cross-origin")
            }
        }
    }
    onCallRespond { call, _ ->
        if (call.request.path().startsWith("/api/") && call.response.headers[HttpHeaders.CacheControl] == null) {
            call.response.header(HttpHeaders.CacheControl, "private, no-store")
        }
    }
    on(ResponseSent) { call ->
        call.application.log.info("${call.request.httpMethod.value} ${call.request.path()} ${call.response.status()?.value}")
    }
}

suspend fun ApplicationCall.respondError(e: ApiError) {
    e.retryAfter?.let { response.header(HttpHeaders.RetryAfter, it) }
    val body = ErrorBody(e.code, e.tmdbStatusCode, e.sessionGeneration, e.accountObjectId)
    respondText(apiJson.encodeToString(body), ContentType.Application.Json, e.status)
}

/**
 * CSRF defence for every state-changing route (CORS is not CSRF protection): the browser-set `Origin` must be
 * ours, Fetch Metadata must not say cross-site, and `X-CV-CSRF` must equal the `cv_csrf` cookie (double submit).
 */
fun ApplicationCall.requireCsrf(config: ServerConfig) {
    val origin = request.headers[HttpHeaders.Origin]
    if (origin == null || origin !in config.allowedOrigins) throw ApiError(HttpStatusCode.Forbidden, "csrf_origin")
    val site = request.headers["Sec-Fetch-Site"]
    if (site != null && site != "same-origin" && site != "same-site") throw ApiError(HttpStatusCode.Forbidden, "csrf_origin")
    val cookie = request.cookies[CSRF_COOKIE]
    val header = request.headers[CSRF_HEADER]
    if (cookie.isNullOrEmpty() || header == null || !constantTimeEquals(cookie, header)) {
        throw ApiError(HttpStatusCode.Forbidden, "csrf_token")
    }
}

fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

/** Reads a small JSON body (≤ [MAX_BODY_BYTES]) strictly; anything else is rejected before TMDB is called. */
suspend inline fun <reified T> ApplicationCall.receiveSmallJson(): T {
    if (request.contentType().withoutParameters() != ContentType.Application.Json) {
        throw ApiError(HttpStatusCode.UnsupportedMediaType, "json_required")
    }
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > MAX_BODY_BYTES) throw ApiError(HttpStatusCode.PayloadTooLarge, "body_too_large")
    val bytes = receiveChannel().readBuffer(MAX_BODY_BYTES + 1L).readByteArray()
    if (bytes.size > MAX_BODY_BYTES) throw ApiError(HttpStatusCode.PayloadTooLarge, "body_too_large")
    return try {
        apiJson.decodeFromString<T>(bytes.decodeToString())
    } catch (_: IllegalArgumentException) {
        throw ApiError(HttpStatusCode.BadRequest, "invalid_body")
    }
}
