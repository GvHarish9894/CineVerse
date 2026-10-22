package com.techgv.cineverse.data.tmdb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

// Retrofit-bound half of §3.3 (the table itself is classifyTmdbError in :shared); replaced by the Ktor client in P2.

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
