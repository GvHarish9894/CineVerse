package com.techgv.cineverse.data.tmdb

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.util.AttributeKey

const val TMDB_HOST = "api.themoviedb.org"

/** How the TMDB client reaches TMDB. */
sealed interface Transport {
    /** Native: straight to [TMDB_HOST] with the app's read token (injected by the Android / iOS entry point). */
    class Direct(val readToken: ReadToken) : Transport

    /**
     * Web: the same-origin server proxy (plan §5) at [baseUrl]; the browser holds no token or session id.
     * Implemented with the web client against the server lane's API.
     */
    interface Proxy : Transport {
        val baseUrl: String
    }
}

/** Per-call token kind (§3.1): set by [KtorTmdbApi] for [UserBearer] calls, read by the bearer plugin. */
internal val UserBearerKey = AttributeKey<UserAccessToken>("TmdbUserBearer")

/** A non-2xx TMDB response, read once: the input of [classifyTmdbError]. Carries no URL or body (no secrets). */
class TmdbHttpException(val httpCode: Int, val statusCode: Int?, val retryAfterSeconds: Long?) :
    Exception("TMDB HTTP $httpCode, status_code $statusCode")

/**
 * The authenticated TMDB client for [Transport.Direct]: base URL `https://api.themoviedb.org/`, a bearer only for
 * [TMDB_HOST] (the call's [UserBearer] if any, else the read token), non-2xx → [TmdbHttpException].
 * [configure] adds e.g. debug logging. Images use a separate, credential-free client.
 */
fun tmdbHttpClient(
    engine: HttpClientEngine,
    transport: Transport.Direct,
    configure: HttpClientConfig<*>.() -> Unit = {},
): HttpClient = HttpClient(engine) {
    expectSuccess = false
    defaultRequest { url("https://$TMDB_HOST/") }
    install(tmdbBearer(transport.readToken))
    HttpResponseValidator {
        validateResponse { response -> if (!response.status.isSuccess()) throw response.toTmdbHttpException() }
    }
    configure()
}

private fun tmdbBearer(readToken: ReadToken) = createClientPlugin("TmdbBearer") {
    onRequest { request, _ ->
        if (request.url.host != TMDB_HOST) return@onRequest
        val bearer = request.attributes.getOrNull(UserBearerKey)?.value ?: readToken.value
        request.headers[HttpHeaders.Authorization] = "Bearer $bearer"
    }
}

private suspend fun HttpResponse.toTmdbHttpException(): TmdbHttpException {
    val statusCode = runCatching { TmdbJson.decodeFromString<StatusDto>(bodyAsText()).statusCode }.getOrNull()
    val retryAfter = headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()
    return TmdbHttpException(status.value, statusCode, retryAfter)
}

/** Auth endpoints carry tokens / session ids in their bodies; debug logging never prints those bodies. */
fun isAuthEndpoint(encodedPath: String): Boolean =
    encodedPath.startsWith("/4/auth/") || encodedPath.startsWith("/3/authentication/")
