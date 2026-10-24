package com.techgv.cineverse.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.content.TextContent
import io.ktor.http.takeFrom
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.LongAsStringSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The only upstream this server ever talks to. */
const val TMDB_ORIGIN = "https://api.themoviedb.org"

/** Lenient decoder for TMDB responses. */
val tmdbJson = Json { ignoreUnknownKeys = true }

fun tmdbHttpClient(engine: HttpClientEngine) = HttpClient(engine) {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 10_000
        requestTimeoutMillis = 15_000
    }
}

/** One TMDB response. [body] may hold credentials (auth endpoints): never log it. */
class Upstream(val status: Int, val body: String, val retryAfter: String?) {
    val ok get() = status in 200..299
    val statusCode: Int? by lazy {
        runCatching { tmdbJson.parseToJsonElement(body).jsonObject["status_code"]?.jsonPrimitive?.int }.getOrNull()
    }

    inline fun <reified T> decode(): T = tmdbJson.decodeFromString(body)
}

/**
 * TMDB client. Every call is made exactly once (no retries: writes and auth steps are not idempotent).
 * Bearer is the read token unless [call]'s `bearer` (a user access token) is given (spec §3.1).
 * At most [maxConcurrent] calls run at once; past that a call fails fast with `503 upstream_busy`, before sending.
 */
class Tmdb(private val http: HttpClient, private val readToken: String, maxConcurrent: Int) {
    private val permits = Semaphore(maxConcurrent)

    suspend fun call(
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: JsonObject? = null,
        bearer: String = readToken,
    ): Upstream {
        if (!permits.tryAcquire()) throw UPSTREAM_BUSY
        try {
            val response = http.request {
                this.method = method
                url {
                    takeFrom(TMDB_ORIGIN)
                    appendPathSegments(path.split('/'))
                    query.forEach { (name, value) -> parameters.append(name, value) }
                }
                bearerAuth(bearer)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                if (body != null) setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
            return Upstream(response.status.value, response.bodyAsText(), response.headers[HttpHeaders.RetryAfter])
        } finally {
            permits.release()
        }
    }

    fun close() = http.close()
}

/** Which row of the spec §3.3 table applies. */
enum class TmdbOperation { Catalog, Session, UserToken, AccessTokenExchange }

/**
 * A sanitized client-facing error: `{"error": code, "status_code": tmdbStatusCode}`, plus the ended session's
 * `sessionGeneration`/`accountObjectId` on a TMDB-reported `session_expired`.
 */
class ApiError(
    val status: HttpStatusCode,
    val code: String,
    val tmdbStatusCode: Int? = null,
    val retryAfter: String? = null,
    val sessionGeneration: Long? = null,
    val accountObjectId: String? = null,
) : Exception(code, null, false, false)

@Serializable
data class ErrorBody(
    val error: String,
    @SerialName("status_code") val statusCode: Int? = null,
    /** Decimal string, like [AuthSession.sessionGeneration]. */
    @Serializable(with = LongAsStringSerializer::class) val sessionGeneration: Long? = null,
    val accountObjectId: String? = null,
)

private val appKeyCodes = setOf(7, 10)
private val sessionInvalidCodes = setOf(3, 14, 17, 37, 35, 36)

/** Spec §3.3: by operation + TMDB `status_code`, never by HTTP status alone. */
fun classify(operation: TmdbOperation, upstream: Upstream): ApiError {
    val code = upstream.statusCode
    val http = upstream.status
    fun error(status: HttpStatusCode, name: String) = ApiError(status, name, code)
    if (http == 429) return ApiError(HttpStatusCode.TooManyRequests, "rate_limited", code, upstream.retryAfter ?: "3")
    if (code in appKeyCodes) return error(HttpStatusCode.InternalServerError, "app_credential")
    if (operation == TmdbOperation.AccessTokenExchange) {
        return when {
            code == 41 -> error(HttpStatusCode.Conflict, "not_approved")
            code == 33 -> error(HttpStatusCode.Gone, "request_token_expired")
            http in 400..499 -> error(HttpStatusCode.Forbidden, "denied")
            else -> error(HttpStatusCode.BadGateway, "upstream_error")
        }
    }
    return when {
        operation != TmdbOperation.Catalog && code in sessionInvalidCodes -> error(HttpStatusCode.Unauthorized, SESSION_EXPIRED)
        code == 34 || http == 404 -> error(HttpStatusCode.NotFound, "not_found")
        http >= 500 -> error(HttpStatusCode.BadGateway, "upstream_error")
        http == 401 && code == null -> error(HttpStatusCode.InternalServerError, "app_credential")
        http in 400..499 -> error(HttpStatusCode.fromValue(http), "tmdb_rejected")
        else -> error(HttpStatusCode.BadGateway, "upstream_error")
    }
}

const val SESSION_EXPIRED = "session_expired"

val UPSTREAM_BUSY = ApiError(HttpStatusCode.ServiceUnavailable, "upstream_busy", retryAfter = "1")

// --- minimal TMDB DTOs (the server keeps its own; plan §1) ---

@Serializable
class RequestTokenDto(@SerialName("request_token") val requestToken: String)

@Serializable
class AccessTokenDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("account_id") val accountObjectId: String,
)

@Serializable
class SessionDto(@SerialName("session_id") val sessionId: String)

@Serializable
class AccountDto(
    val id: Int,
    val username: String = "",
    val name: String? = null,
    @SerialName("iso_639_1") val language: String? = null,
    @SerialName("iso_3166_1") val region: String? = null,
    @SerialName("include_adult") val includeAdult: Boolean? = null,
)
