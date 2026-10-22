package com.techgv.cineverse.server

import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.renderSetCookieHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.LongAsStringSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

const val SESSION_COOKIE = "cv_session"
const val ATTEMPT_COOKIE = "cv_auth_attempt"

private val random = SecureRandom()

/** 256-bit opaque identifier (base64url). */
fun randomId(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

/**
 * The only identity the browser ever sees (plan §3). Never carries tokens. [sessionGeneration] is an opaque 64-bit
 * value, a decimal JSON string on the wire (JS numbers lose precision above 2^53); clients only compare it for equality.
 */
@Serializable
data class AuthSession(
    val signedIn: Boolean,
    val accountObjectId: String?,
    val displayName: String?,
    @Serializable(with = LongAsStringSerializer::class) val sessionGeneration: Long,
)

val SIGNED_OUT = AuthSession(signedIn = false, accountObjectId = null, displayName = null, sessionGeneration = 0)

/**
 * A pending sign-in, bound to the browser that started it by [binding] (the attempt cookie) and valid only while
 * that browser's auth [epoch] is unchanged (see [AuthStore.supersede]).
 */
class AuthAttempt(val id: String, val binding: String, val epoch: Long, val requestToken: String, val createdAt: Long) {
    override fun toString() = "AuthAttempt(redacted)"
}

/** Server-side TMDB credentials, keyed by an opaque [id] that is the `cv_session` cookie value. */
class UserSession(
    val id: String,
    val generation: Long,
    val accountObjectId: String,
    val accountId: Int,
    val displayName: String,
    val userAccessToken: String,
    val sessionId: String,
    val expiresAt: Long,
) {
    fun toAuthSession() = AuthSession(true, accountObjectId, displayName, generation)
    override fun toString() = "UserSession(redacted)"
}

/**
 * In-memory session + attempt stores.
 * shortcut: in-memory, single instance — sessions are lost on restart; move to a shared store before scaling out.
 */
class AuthStore(private val config: ServerConfig, private val nowMillis: () -> Long) {
    private val sessions = ConcurrentHashMap<String, UserSession>()
    private val attempts = ConcurrentHashMap<String, AuthAttempt>()
    private val generations = AtomicLong(0)

    /** Random 32-bit nonce per store instance: makes generations distinct across restarts with high probability (~1/2^32 per pair of runs; the low 32-bit counter wraps after 2^32 sign-ins) and unpredictable from a previous run. Adequate for the single-instance prototype; not an unlimited-lifetime guarantee. */
    private val bootNonce = random.nextInt().toLong() shl 32
    private val attemptTtl = config.attemptTtl.inWholeMilliseconds

    /**
     * Per browser (keyed by its attempt-cookie binding): the auth [epoch] and the session the browser currently holds
     * (the last committed sign-in, until logout). Lets logout end a session whose cookie the browser has not received yet.
     */
    private class BrowserState(val epoch: Long, val currentSessionId: String?, val touchedAt: Long)

    /** Guarded by `this`. */
    private val browsers = HashMap<String, BrowserState>()

    /** Session id -> browser binding, kept until the session's own expiry (its cookie's Max-Age), even once ended. */
    private class SessionOwner(val binding: String, val expiresAt: Long)
    private val owners = ConcurrentHashMap<String, SessionOwner>()

    private fun bindingOf(sessionId: String?) = sessionId?.let { owners[it]?.binding }

    /**
     * Reuses the browser's binding — [attemptBinding] while the server still knows it, else the server-issued binding
     * of its [sessionId] cookie — so one state covers all its attempts and sessions.
     */
    @Synchronized
    fun newAttempt(requestToken: String, id: String, attemptBinding: String?, sessionId: String?): AuthAttempt {
        val now = nowMillis()
        attempts.values.removeIf { now - it.createdAt >= attemptTtl }
        // Untouched for a whole attempt TTL (so every attempt under it expired) and not holding a live session.
        browsers.values.removeIf { now - it.touchedAt >= attemptTtl && it.currentSessionId?.let(::liveSession) == null }
        val binding = attemptBinding?.takeIf { it in browsers } ?: bindingOf(sessionId) ?: randomId()
        val state = browsers[binding]
        browsers[binding] = BrowserState(state?.epoch ?: 0, state?.currentSessionId, now)
        return AuthAttempt(id, binding, state?.epoch ?: 0, requestToken, now).also { attempts[id] = it }
    }

    fun attempt(id: String): AuthAttempt? = attempts[id]?.takeIf { nowMillis() - it.createdAt < attemptTtl }

    /** Cancel: every outstanding (pending or in-flight) attempt of the browser dies; its session stays. */
    @Synchronized
    fun supersede(binding: String) {
        val state = browsers[binding] ?: return
        browsers[binding] = BrowserState(state.epoch + 1, state.currentSessionId, nowMillis())
    }

    @Synchronized
    fun isCurrent(attempt: AuthAttempt) =
        browsers[attempt.binding]?.epoch == attempt.epoch && nowMillis() - attempt.createdAt < attemptTtl

    class SignIn(val session: UserSession, val replaced: List<UserSession>)

    /**
     * Creates the session unless [attempt] was superseded meanwhile, makes it the browser's current one and ends the
     * sessions it replaces ([previousSessionId] from the cookie, and the browser's former current one). Atomic with
     * [logout] and [supersede]; no I/O inside — the caller revokes [SignIn.replaced] afterwards.
     */
    @Synchronized
    fun commitSignIn(attempt: AuthAttempt, tokens: AccessTokenDto, sessionId: String, account: AccountDto, previousSessionId: String?): SignIn? {
        if (!isCurrent(attempt)) return null
        val state = browsers.getValue(attempt.binding)
        val session = createSession(tokens, sessionId, account, attempt.binding)
        val replaced = listOfNotNull(previousSessionId, state.currentSessionId).distinct().mapNotNull(sessions::remove)
        browsers[attempt.binding] = BrowserState(state.epoch + 1, session.id, nowMillis())
        return SignIn(session, replaced)
    }

    /** [session] is still live and still its browser's current session (no logout since its commit). */
    @Synchronized
    fun isBrowserCurrent(session: UserSession) =
        browsers[bindingOf(session.id)]?.currentSessionId == session.id && isLive(session)

    /**
     * Logout: ends the browser's current session — whichever it is, also one committed but not yet in the browser's
     * cookie jar — and the [sessionId] cookie's session, and supersedes every attempt. Returns the ended sessions
     * for the caller to revoke outside the lock.
     */
    @Synchronized
    fun logout(attemptBinding: String?, sessionId: String?): List<UserSession> {
        val current = listOfNotNull(attemptBinding, bindingOf(sessionId)).distinct().mapNotNull { binding ->
            val state = browsers[binding] ?: return@mapNotNull null
            browsers[binding] = BrowserState(state.epoch + 1, null, nowMillis())
            state.currentSessionId
        }
        return (current + listOfNotNull(sessionId)).distinct().mapNotNull(sessions::remove)
    }

    /** Atomically takes [attempt] so concurrent callbacks never exchange one request token twice. */
    fun claim(attempt: AuthAttempt) = attempts.remove(attempt.id, attempt)
    fun restore(attempt: AuthAttempt) { attempts.putIfAbsent(attempt.id, attempt) }

    private fun createSession(tokens: AccessTokenDto, sessionId: String, account: AccountDto, binding: String): UserSession {
        val now = nowMillis()
        sessions.values.removeIf { now >= it.expiresAt }
        owners.values.removeIf { now >= it.expiresAt }
        val session = UserSession(
            id = randomId(),
            generation = bootNonce or (generations.incrementAndGet() and 0xFFFF_FFFFL),
            accountObjectId = tokens.accountObjectId,
            accountId = account.id,
            displayName = account.name?.takeIf { it.isNotBlank() } ?: account.username,
            userAccessToken = tokens.accessToken,
            sessionId = sessionId,
            expiresAt = now + config.sessionTtl.inWholeMilliseconds,
        )
        sessions[session.id] = session
        owners[session.id] = SessionOwner(binding, session.expiresAt)
        return session
    }

    /** The live session for [id]; an expired one is removed. */
    fun session(id: String): UserSession? {
        val session = sessions[id] ?: return null
        if (nowMillis() < session.expiresAt) return session
        sessions.remove(id, session)
        return null
    }

    private fun liveSession(id: String) = sessions[id]?.takeIf { nowMillis() < it.expiresAt }

    /** [session] is still the stored, unexpired record for its id (same generation and account). */
    fun isLive(session: UserSession) = sessions[session.id] === session && nowMillis() < session.expiresAt

    /** True only for the caller that actually removed [session]. */
    fun removeSession(session: UserSession): Boolean = sessions.remove(session.id, session)
}

class Cookies(private val config: ServerConfig) {
    private fun cookie(name: String, value: String, path: String, maxAgeSeconds: Long, httpOnly: Boolean, sameSite: String) =
        Cookie(
            name = name, value = value, maxAge = maxAgeSeconds.toInt(), path = path,
            secure = config.cookieSecure, httpOnly = httpOnly, extensions = mapOf("SameSite" to sameSite),
        )

    fun session(value: String) =
        cookie(SESSION_COOKIE, value, "/api", config.sessionTtl.inWholeSeconds, httpOnly = true, sameSite = "Lax")

    fun attempt(value: String) =
        cookie(ATTEMPT_COOKIE, value, "/api/auth", config.attemptTtl.inWholeSeconds, httpOnly = true, sameSite = "Lax")

    // Readable by the web app (double-submit token), never sent cross-site.
    fun csrf(value: String) =
        cookie(CSRF_COOKIE, value, "/", config.sessionTtl.inWholeSeconds, httpOnly = false, sameSite = "Strict")

    fun clearSession() = session("").copy(maxAge = 0, expires = GMTDate.START)
    fun clearAttempt() = attempt("").copy(maxAge = 0, expires = GMTDate.START)
}

/** Like `response.cookies.append`, without Ktor's non-standard `$x-enc` attribute. */
fun ApplicationCall.setCookie(c: Cookie) = response.headers.append(
    HttpHeaders.SetCookie,
    renderSetCookieHeader(
        c.name, c.value, c.encoding, c.maxAge, c.expires, c.domain, c.path, c.secure, c.httpOnly, c.extensions,
        includeEncoding = false,
    ),
)

/**
 * Session state of a request. Never touches `cv_session`: only `POST /api/auth/logout` deletes it. Any other
 * response may be delivered after another tab stored a newer cookie, which a deletion header would destroy; a stale
 * cookie is harmless (unknown to the server) and is overwritten by the next sign-in or expires.
 */
class SessionLookup(private val store: AuthStore) {
    private fun cookieId(call: ApplicationCall) = call.request.cookies[SESSION_COOKIE]?.takeIf { it.isNotEmpty() }

    /** The live session of the presented cookie; an expired record is removed. */
    fun current(call: ApplicationCall): UserSession? = cookieId(call)?.let(store::session)

    fun require(call: ApplicationCall): UserSession {
        val hadCookie = !call.request.cookies[SESSION_COOKIE].isNullOrEmpty()
        return current(call) ?: throw ApiError(HttpStatusCode.Unauthorized, if (hadCookie) SESSION_EXPIRED else "not_signed_in")
    }

    /** TMDB reported [session] invalid: ends it locally; the 401 names the ended session for the client. */
    fun expired(session: UserSession, tmdbStatusCode: Int?): ApiError {
        store.removeSession(session)
        return ApiError(
            HttpStatusCode.Unauthorized, SESSION_EXPIRED, tmdbStatusCode,
            sessionGeneration = session.generation, accountObjectId = session.accountObjectId,
        )
    }

    fun isLive(session: UserSession) = store.isLive(session)
}

suspend fun ApplicationCall.respondJson(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(text, ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondSession(session: AuthSession) =
    respondJson(apiJson.encodeToString(session))

@Serializable
private class StartResponse(val approveUrl: String, val attemptId: String)

/** v4 sign-in (spec §4, plan §3) with server-held credentials; the browser only gets the opaque cookie. */
fun Route.authRoutes(config: ServerConfig, tmdb: Tmdb, store: AuthStore, cookies: Cookies, sessions: SessionLookup) {
    get("/api/session") {
        if (call.request.cookies[CSRF_COOKIE].isNullOrEmpty()) call.setCookie(cookies.csrf(randomId()))
        call.respondSession(sessions.current(call)?.toAuthSession() ?: SIGNED_OUT)
    }

    rateLimit(AUTH_BY_IP) {
        rateLimit(AUTH_BY_SESSION) {
            post("/api/auth/start") {
                call.requireCsrf(config)
                val id = randomId()
                val redirectTo = "${config.publicOrigin}/api/auth/callback?attempt=$id"
                val upstream = tmdb.call(HttpMethod.Post, "4/auth/request_token", body = buildJsonObject { put("redirect_to", redirectTo) })
                if (!upstream.ok) throw classify(TmdbOperation.Catalog, upstream)
                val attempt = store.newAttempt(
                    upstream.decode<RequestTokenDto>().requestToken, id,
                    call.request.cookies[ATTEMPT_COOKIE], call.request.cookies[SESSION_COOKIE],
                )
                call.setCookie(cookies.attempt(attempt.binding))
                val approveUrl = "https://www.themoviedb.org/auth/access?request_token=" + attempt.requestToken.encodeURLParameter()
                call.respondJson(apiJson.encodeToString(StartResponse(approveUrl, attempt.id)))
            }

            // The redirect is only a hint: approval is proven by the token exchange, for the browser that started it.
            get("/api/auth/callback") {
                val attempt = call.request.queryParameters["attempt"]?.let(store::attempt)
                    ?: throw ApiError(HttpStatusCode.Gone, "attempt_expired")
                val binding = call.request.cookies[ATTEMPT_COOKIE]
                if (binding == null || !constantTimeEquals(binding, attempt.binding)) {
                    throw ApiError(HttpStatusCode.Forbidden, "attempt_mismatch")
                }
                if (!store.isCurrent(attempt)) {
                    store.claim(attempt)
                    throw ApiError(HttpStatusCode.Conflict, ATTEMPT_SUPERSEDED)
                }
                if (!store.claim(attempt)) throw ApiError(HttpStatusCode.Gone, "attempt_expired")

                val exchange = try {
                    tmdb.call(
                        HttpMethod.Post, "4/auth/access_token", body = buildJsonObject { put("request_token", attempt.requestToken) },
                    )
                } catch (e: ApiError) {
                    store.restore(attempt) // upstream_busy: nothing was sent, the attempt may be retried
                    throw e
                }
                if (!exchange.ok) {
                    val error = classify(TmdbOperation.AccessTokenExchange, exchange)
                    // Pending / throttled / upstream trouble: the same attempt may be retried.
                    if (error.code in setOf("not_approved", "rate_limited", "upstream_error")) store.restore(attempt)
                    throw error
                }
                // The request token is consumed from here on; nothing partial is kept if a later step fails.
                val tokens = exchange.decode<AccessTokenDto>()
                var sessionId: String? = null
                val account = try {
                    val convert = tmdb.call(
                        HttpMethod.Post, "3/authentication/session/convert/4",
                        body = buildJsonObject { put("access_token", tokens.accessToken) },
                    )
                    if (!convert.ok) throw ApiError(HttpStatusCode.BadGateway, "sign_in_failed", convert.statusCode)
                    sessionId = convert.decode<SessionDto>().sessionId
                    val account = tmdb.call(HttpMethod.Get, "3/account", listOf("session_id" to sessionId))
                    if (!account.ok) throw ApiError(HttpStatusCode.BadGateway, "sign_in_failed", account.statusCode)
                    account.decode<AccountDto>()
                } catch (e: Exception) {
                    revoke(call, tmdb, tokens.accessToken, sessionId)
                    throw e
                }

                // Logout / cancel / another sign-in of this browser while TMDB was answering wins over this one.
                val signIn = store.commitSignIn(attempt, tokens, sessionId, account, call.request.cookies[SESSION_COOKIE]) ?: run {
                    revoke(call, tmdb, tokens.accessToken, sessionId)
                    throw ApiError(HttpStatusCode.Conflict, ATTEMPT_SUPERSEDED)
                }
                val session = signIn.session
                // Rotation: the sessions this browser held are ended, never reused.
                signIn.replaced.forEach { revoke(call, tmdb, it.userAccessToken, it.sessionId) }
                // A logout during that revocation ended the new session already: never hand its cookie out.
                if (!store.isBrowserCurrent(session)) {
                    // Normally that logout removed (and revokes) it; otherwise this request does.
                    if (store.removeSession(session)) revoke(call, tmdb, session.userAccessToken, session.sessionId)
                    throw ApiError(HttpStatusCode.Conflict, ATTEMPT_SUPERSEDED)
                }
                call.setCookie(cookies.session(session.id))
                call.setCookie(cookies.csrf(randomId()))
                call.setCookie(cookies.clearAttempt())
                call.respondRedirect("${config.publicOrigin}/?signedIn=1")
            }

            post("/api/auth/logout") {
                call.requireCsrf(config)
                // Local sign-out first; remote revocation is best effort and never blocks it.
                val ended = store.logout(call.request.cookies[ATTEMPT_COOKIE], call.request.cookies[SESSION_COOKIE])
                call.setCookie(cookies.clearSession()) // the only cv_session deletion; see SessionLookup
                ended.forEach { revoke(call, tmdb, it.userAccessToken, it.sessionId) }
                call.respondSession(SIGNED_OUT)
            }

            // "Not now": ends every pending or in-flight sign-in of this browser; an existing session stays.
            post("/api/auth/cancel") {
                call.requireCsrf(config)
                call.request.cookies[ATTEMPT_COOKIE]?.let(store::supersede)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

const val ATTEMPT_SUPERSEDED = "attempt_superseded"

/** Best-effort, bounded (5 s) revocation (spec §4.2). Failures are logged without any token value. */
private suspend fun revoke(call: ApplicationCall, tmdb: Tmdb, userAccessToken: String, sessionId: String?) {
    val log = call.application.log
    withTimeoutOrNull(5_000) {
        runCatching {
            tmdb.call(
                HttpMethod.Delete, "4/auth/access_token", bearer = userAccessToken,
                body = buildJsonObject { put("access_token", userAccessToken) },
            )
        }.onSuccess { if (!it.ok) log.info("access token revocation rejected: ${it.status}") }
            .onFailure { log.info("access token revocation failed: ${it::class.simpleName}") }
        if (sessionId != null) {
            runCatching {
                tmdb.call(HttpMethod.Delete, "3/authentication/session", body = buildJsonObject { put("session_id", sessionId) })
            }.onSuccess { if (!it.ok) log.info("session revocation rejected: ${it.status}") }
                .onFailure { log.info("session revocation failed: ${it::class.simpleName}") }
        }
    } ?: log.info("revocation timed out")
}
