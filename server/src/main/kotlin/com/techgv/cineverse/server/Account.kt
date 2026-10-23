package com.techgv.cineverse.server

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private class WatchlistRequest(
    @SerialName("media_type") val mediaType: String,
    @SerialName("media_id") val mediaId: Int,
    val watchlist: Boolean,
)

@Serializable
private class FavoriteRequest(
    @SerialName("media_type") val mediaType: String,
    @SerialName("media_id") val mediaId: Int,
    val favorite: Boolean,
)

@Serializable
private class RatingRequest(val value: Double)

private val mediaTypes = setOf("movie", "tv")
private val accountLists = setOf("watchlist", "favorites", "rated")

private fun ApplicationCall.media(): String = parameters["media"]?.takeIf { it in mediaTypes } ?: throw NOT_ALLOWED
private fun ApplicationCall.titleId(): Int = parameters["id"]?.toIntOrNull()?.takeIf { it > 0 } ?: throw NOT_ALLOWED

private fun validTitle(mediaType: String, mediaId: Int) {
    if (mediaType !in mediaTypes || mediaId <= 0) throw ApiError(HttpStatusCode.BadRequest, "invalid_body")
}

/**
 * Account reads/writes on behalf of the `cv_session` cookie's TMDB account (spec §3.1/§3.5). Responses are
 * never cached (`private, no-store` default). Reads and writes are bound to the expected session generation + account.
 * Writes are also CSRF-checked and sent exactly once.
 */
fun Route.accountRoutes(config: ServerConfig, tmdb: Tmdb, sessions: SessionLookup) {
    /** Relays a TMDB account response; TMDB saying the session is gone ends that session locally. */
    suspend fun RoutingContext.relay(operation: TmdbOperation, session: UserSession, upstream: Upstream) {
        if (upstream.ok) return call.respondJson(upstream.body, HttpStatusCode.fromValue(upstream.status))
        val error = classify(operation, upstream)
        throw if (error.code == SESSION_EXPIRED) sessions.expired(session, error.tmdbStatusCode) else error
    }

    /** Account preconditions: CSRF for mutations, signed in, request bound to this exact session. */
    fun RoutingContext.boundSession(mutation: Boolean = true): UserSession {
        if (mutation) call.requireCsrf(config)
        val session = sessions.require(call)
        val generation = call.request.headers[EXPECTED_GENERATION_HEADER]?.toLongOrNull()
        val account = call.request.headers[EXPECTED_ACCOUNT_HEADER]
        if (generation == null || account == null) throw ApiError(HttpStatusCode.BadRequest, "missing_session_binding")
        if (generation != session.generation || account != session.accountObjectId) {
            throw ApiError(HttpStatusCode.Conflict, "stale_session")
        }
        return session
    }

    suspend fun RoutingContext.write(session: UserSession, method: HttpMethod, path: String, body: JsonObject?) {
        // The body was read after [boundSession]: a logout, expiry or rotation meanwhile stops the write here.
        if (!sessions.isLive(session)) throw ApiError(HttpStatusCode.Conflict, "stale_session")
        relay(TmdbOperation.Session, session, tmdb.call(method, path, listOf("session_id" to session.sessionId), body))
    }

    rateLimit(ACCOUNT_READ_BY_IP) {
        get("/api/account/{media}/{list}") {
            val session = boundSession(mutation = false)
            val list = call.parameters["list"]?.takeIf { it in accountLists } ?: throw NOT_ALLOWED
            val path = "4/account/${session.accountObjectId}/${call.media()}/$list"
            val upstream = tmdb.call(HttpMethod.Get, path, accountListQuery(call.request.queryParameters), bearer = session.userAccessToken)
            relay(TmdbOperation.UserToken, session, upstream)
        }

        get("/api/account/states/{media}/{id}") {
            val session = boundSession(mutation = false)
            if (!call.request.queryParameters.isEmpty()) throw NOT_ALLOWED
            val path = "3/${call.media()}/${call.titleId()}/account_states"
            relay(TmdbOperation.Session, session, tmdb.call(HttpMethod.Get, path, listOf("session_id" to session.sessionId)))
        }
    }

    rateLimit(MUTATION_BY_IP) {
        rateLimit(MUTATION_BY_SESSION) {
            post("/api/account/watchlist") {
                val session = boundSession()
                val request = call.receiveSmallJson<WatchlistRequest>()
                validTitle(request.mediaType, request.mediaId)
                write(
                    session, HttpMethod.Post, "3/account/${session.accountId}/watchlist",
                    buildJsonObject {
                        put("media_type", request.mediaType)
                        put("media_id", request.mediaId)
                        put("watchlist", request.watchlist)
                    },
                )
            }

            post("/api/account/favorite") {
                val session = boundSession()
                val request = call.receiveSmallJson<FavoriteRequest>()
                validTitle(request.mediaType, request.mediaId)
                write(
                    session, HttpMethod.Post, "3/account/${session.accountId}/favorite",
                    buildJsonObject {
                        put("media_type", request.mediaType)
                        put("media_id", request.mediaId)
                        put("favorite", request.favorite)
                    },
                )
            }

            post("/api/account/rating/{media}/{id}") {
                val session = boundSession()
                val path = "3/${call.media()}/${call.titleId()}/rating"
                val value = call.receiveSmallJson<RatingRequest>().value
                // 0.5–10 in 0.5 steps (spec §7.11).
                if (value !in 0.5..10.0 || value * 2 != Math.floor(value * 2)) throw ApiError(HttpStatusCode.BadRequest, "invalid_body")
                write(session, HttpMethod.Post, path, buildJsonObject { put("value", value) })
            }

            delete("/api/account/rating/{media}/{id}") {
                val session = boundSession()
                write(session, HttpMethod.Delete, "3/${call.media()}/${call.titleId()}/rating", null)
            }
        }
    }
}
