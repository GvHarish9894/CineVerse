package com.techgv.cineverse.data.web

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.tmdb.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Whether a failed mutation may already have reached TMDB. Never automatically replay PossiblyLanded. */
enum class WebWriteOutcome { NotApplicable, DefinitelyUnsent, PossiblyLanded }

class WebProxyException(
    val code: String,
    val httpStatus: Int?,
    val retryAfterMillis: Long?,
    val writeOutcome: WebWriteOutcome,
    error: TmdbError,
) : TmdbException(error)

internal val WebJson = Json { ignoreUnknownKeys = true }
private val safeCodes = setOf(
    "not_allowed", "not_found", "app_credential", "rate_limited", "upstream_error", "upstream_unavailable",
    "not_approved", "request_token_expired", "denied", "attempt_expired", "attempt_mismatch", "attempt_superseded",
    "sign_in_failed", "not_signed_in", "session_expired", "csrf_origin", "csrf_token", "missing_session_binding",
    "stale_session", "invalid_body", "body_too_large", "json_required", "upstream_busy",
)

internal fun webFailure(status: Int, text: String, retryAfter: String?, mutation: Boolean): WebProxyException {
    val wire = try { WebJson.parseToJsonElement(text) as? JsonObject } catch (_: SerializationException) { null }
    val rawCode = (wire?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val code = rawCode?.takeIf { it in safeCodes } ?: if (status == 429) "rate_limited" else "unknown_error"
    val retry = retryAfter?.toLongOrNull()?.takeIf { it >= 0 }?.coerceAtMost(Long.MAX_VALUE / 1000)?.times(1000)
    val error = when {
        status == 429 -> TmdbError.RateLimited(retry?.coerceAtLeast(1000) ?: 3000)
        code == "app_credential" -> TmdbError.AppCredential
        code == "not_found" -> TmdbError.NotFound
        code == "session_expired" || code == "not_signed_in" -> TmdbError.SessionExpired
        code == "not_approved" -> TmdbError.ApprovalPending
        code == "request_token_expired" || code == "attempt_expired" -> TmdbError.RequestTokenExpired
        status >= 500 -> TmdbError.Server(status)
        else -> TmdbError.Http(status, null)
    }
    val unsent = code in setOf("upstream_busy", "stale_session", "missing_session_binding", "csrf_origin", "csrf_token",
        "invalid_body", "body_too_large", "json_required", "not_signed_in", "not_allowed") || status == 429
    return WebProxyException(code, status, retry, when {
        !mutation -> WebWriteOutcome.NotApplicable
        unsent -> WebWriteOutcome.DefinitelyUnsent
        else -> WebWriteOutcome.PossiblyLanded
    }, error)
}

internal fun staleWebSession() = WebProxyException("stale_session", 409, null, WebWriteOutcome.DefinitelyUnsent, TmdbError.Http(409, null))

/** The client must be credential-free and same-origin. [csrfCookie] reads the CURRENT readable cookie per mutation. */
class WebProxyHttp(private val client: HttpClient, private val csrfCookie: () -> String?) {
    internal suspend fun call(
        path: String,
        query: List<Pair<String, Any?>> = emptyList(),
        method: HttpMethod = HttpMethod.Get,
        body: String? = null,
        expected: AuthSession? = null,
    ): String {
        require(path.startsWith("/api/") && '?' !in path && '#' !in path && ".." !in path)
        val mutation = method == HttpMethod.Post || method == HttpMethod.Delete
        try {
            val response = client.request(path) {
                this.method = method
                query.forEach { (key, value) -> if (value != null) parameter(key, value) }
                expected?.let {
                    header("X-CV-Expected-Session-Generation", it.sessionGeneration.toString())
                    header("X-CV-Expected-Account", it.accountObjectId.value)
                }
                if (mutation) header("X-CV-CSRF", csrfCookie()?.takeIf { it.isNotBlank() }
                    ?: throw WebProxyException("csrf_token", null, null, WebWriteOutcome.DefinitelyUnsent, TmdbError.Http(403, null)))
                if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
            }
            val text = response.bodyAsText()
            if (response.status.value !in 200..299) throw webFailure(response.status.value, text, response.headers[HttpHeaders.RetryAfter], mutation)
            return text
        } catch (e: CancellationException) { throw e
        } catch (e: TmdbException) { throw e
        } catch (_: Exception) {
            // Do not retain engine exceptions: their messages can contain URLs, cookies or response bodies.
            throw WebProxyException("transport_failure", null, null,
                if (mutation) WebWriteOutcome.PossiblyLanded else WebWriteOutcome.NotApplicable, TmdbError.Network)
        }
    }
}
