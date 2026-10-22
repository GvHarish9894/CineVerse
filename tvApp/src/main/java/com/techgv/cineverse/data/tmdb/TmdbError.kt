package com.techgv.cineverse.data.tmdb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/** Which row of the §3.3 table applies: classification is by operation + TMDB `status_code`. */
enum class TmdbOperation {
    /** Read-token calls without session (catalog, request token, convert). */
    Catalog,
    /** Calls carrying `session_id` (v3 account reads/writes, account_states). */
    Session,
    /** Calls carrying the user access token (v4 account lists). */
    UserToken,
    /** `POST /4/auth/access_token` while polling for approval. */
    AccessTokenExchange,
}

sealed interface TmdbError {
    /** 41 during the exchange: not approved yet — the only error that keeps the spinner. */
    data object ApprovalPending : TmdbError
    /** 33 during the exchange: "Code expired — press OK for a new one". */
    data object RequestTokenExpired : TmdbError
    /**
     * Any other 4xx during the exchange: terminal, "TMDB didn't approve this sign-in". Codes are kept so the
     * auth spike can record TMDB's real denial payload (§3.3).
     */
    data class ExchangeRejected(val httpCode: Int, val statusCode: Int?) : TmdbError
    /** 7 / 10 (any call), or a bare 401 outside the exchange: misconfiguration banner, never signs out. */
    data object AppCredential : TmdbError
    /** Session / user token no longer valid: local sign-out, "Sign-in expired". */
    data object SessionExpired : TmdbError
    data class RateLimited(val retryAfterMillis: Long) : TmdbError
    /** 34: section "Not available". */
    data object NotFound : TmdbError
    data object Network : TmdbError
    data class Server(val httpCode: Int) : TmdbError
    /** 2xx body that does not parse. */
    data object Malformed : TmdbError
    data class Http(val httpCode: Int, val statusCode: Int?) : TmdbError
}

class TmdbException(val error: TmdbError, cause: Throwable? = null) : Exception(error.toString(), cause)

private val sessionInvalidCodes = setOf(3, 14, 17, 37)
private val userTokenInvalidCodes = setOf(35, 36)
private val appKeyCodes = setOf(7, 10)

fun classifyTmdbError(
    operation: TmdbOperation,
    httpCode: Int,
    statusCode: Int?,
    retryAfterSeconds: Long? = null,
): TmdbError {
    // 429 is checked before every status-code row (incl. the exchange's terminal row).
    if (httpCode == 429) {
        val floor = if (operation == TmdbOperation.AccessTokenExchange) 3L else 1L
        return TmdbError.RateLimited((retryAfterSeconds ?: 3L).coerceAtLeast(floor) * 1000)
    }
    if (statusCode in appKeyCodes) return TmdbError.AppCredential
    if (operation == TmdbOperation.AccessTokenExchange) {
        return when {
            statusCode == 41 -> TmdbError.ApprovalPending
            statusCode == 33 -> TmdbError.RequestTokenExpired
            httpCode in 400..499 -> TmdbError.ExchangeRejected(httpCode, statusCode)
            httpCode >= 500 -> TmdbError.Server(httpCode)
            else -> TmdbError.Http(httpCode, statusCode)
        }
    }
    return when {
        operation == TmdbOperation.Session && statusCode in sessionInvalidCodes -> TmdbError.SessionExpired
        operation == TmdbOperation.UserToken && statusCode in userTokenInvalidCodes -> TmdbError.SessionExpired
        statusCode == 34 -> TmdbError.NotFound
        httpCode >= 500 -> TmdbError.Server(httpCode)
        httpCode == 401 && statusCode == null -> TmdbError.AppCredential
        else -> TmdbError.Http(httpCode, statusCode)
    }
}

fun Throwable.toTmdbError(operation: TmdbOperation): TmdbError = when (this) {
    is TmdbException -> error
    is HttpException -> {
        val response = response()
        val statusCode = runCatching {
            response?.errorBody()?.string()?.let { TmdbJson.decodeFromString<StatusDto>(it).statusCode }
        }.getOrNull()
        val retryAfter = response?.headers()?.get("Retry-After")?.trim()?.toLongOrNull()
        classifyTmdbError(operation, code(), statusCode, retryAfter)
    }
    is IOException -> TmdbError.Network
    is SerializationException -> TmdbError.Malformed
    else -> throw this
}

/**
 * Runs one TMDB call and rethrows failures as [TmdbException]. Outside the exchange, a 429 gets one
 * retry after `Retry-After` (§3.3); a second 429 surfaces as [TmdbError.RateLimited].
 */
suspend fun <T> tmdbCall(operation: TmdbOperation, block: suspend () -> T): T {
    suspend fun once(): T = try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        throw TmdbException(e.toTmdbError(operation), e)
    }
    return try {
        once()
    } catch (e: TmdbException) {
        val limited = e.error as? TmdbError.RateLimited
        if (limited == null || operation == TmdbOperation.AccessTokenExchange) throw e
        delay(limited.retryAfterMillis)
        once()
    }
}
