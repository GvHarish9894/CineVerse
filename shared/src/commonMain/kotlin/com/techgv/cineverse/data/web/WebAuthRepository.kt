package com.techgv.cineverse.data.web

import com.techgv.cineverse.data.auth.*
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** A server-owned web attempt, not a native RequestCode or a locally generated LoginStateHolder id. */
@Serializable
class WebApprovalAttempt(val approveUrl: String, val attemptId: String) {
    override fun toString() = "WebApprovalAttempt(<redacted>)"
}

internal fun decodeWebSession(text: String): AuthSession? {
    fun malformed(): Nothing = throw SerializationException("Invalid web session")
    val obj = WebJson.parseToJsonElement(text) as? JsonObject ?: malformed()
    val signedIn = (obj["signedIn"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: malformed()
    val revision = (obj["sessionGeneration"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed()
    if (!Regex("-?(0|[1-9][0-9]*)").matches(revision)) malformed()
    val generation = revision.toLongOrNull() ?: malformed()
    if (!signedIn) {
        if (obj["accountObjectId"] != JsonNull || obj["displayName"] != JsonNull || generation != 0L) malformed()
        return null
    }
    val account = (obj["accountObjectId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: malformed()
    val name = (obj["displayName"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed()
    return AuthSession(AccountObjectId(account), name, generation)
}

/** Explicit browser approval controller + AuthRepository. Only /api/session establishes identity. */
class WebAuthRepository(private val http: WebProxyHttp) : AuthRepository {
    private val lock = SynchronizedObject()
    private val mutations = Mutex()
    private var revision = 0L
    private var verified = false
    private val current = MutableStateFlow<AuthSession?>(null)
    override val session: StateFlow<AuthSession?> = current.asStateFlow()
    private val expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val sessionExpired: SharedFlow<Unit> = expired.asSharedFlow()

    /** C3/focus/visibility hook: immediately makes in-flight account results unusable; then call revalidate. */
    fun invalidateSession() = synchronized(lock) { revision++; verified = false }

    /** Latest-started revalidation wins, regardless of response completion order. Revisions are local, not wire ordering. */
    suspend fun revalidate(): AuthSession? {
        val ticket = synchronized(lock) { ++revision }
        val value = try { decodeWebSession(http.call("/api/session")) }
        catch (_: SerializationException) { throw WebProxyException("malformed_session", null, null, WebWriteOutcome.NotApplicable, TmdbError.Malformed) }
        return synchronized(lock) {
            if (ticket == revision) { current.value = value; verified = true }
            current.value
        }
    }

    internal fun capture(expected: AuthSession): Long = synchronized(lock) {
        if (!verified || current.value != expected) throw staleWebSession()
        revision
    }

    internal fun check(expected: AuthSession, ticket: Long) = synchronized(lock) {
        if (!verified || current.value != expected || revision != ticket) throw staleWebSession()
    }

    internal suspend fun recover(failure: WebProxyException) {
        if (failure.code !in setOf("stale_session", "session_expired", "not_signed_in")) return
        val previous = current.value
        invalidateSession()
        try {
            revalidate()
            if (failure.code == "session_expired" && previous != null && current.value == null) expired.tryEmit(Unit)
        } catch (e: CancellationException) { throw e
        } catch (_: TmdbException) { /* Keep unverified until an authoritative sync succeeds. */ }
    }

    /** Bootstrap CSRF, then start the server-owned attempt. Open approveUrl using the browser shell. */
    suspend fun startApproval(): WebApprovalAttempt = mutations.withLock {
        revalidate()
        val text = http.call("/api/auth/start", method = HttpMethod.Post)
        try {
            val attempt = WebJson.decodeFromString<WebApprovalAttempt>(text)
            val url = Url(attempt.approveUrl)
            require(attempt.attemptId.isNotBlank() && url.protocol.name == "https" && url.host == "www.themoviedb.org" &&
                url.port == 443 && url.encodedPath == "/auth/access" && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() &&
                url.fragment.isEmpty() && url.parameters.names() == setOf("request_token") &&
                url.parameters.getAll("request_token")?.singleOrNull()?.isNotBlank() == true)
            attempt
        } catch (_: Exception) {
            throw WebProxyException("malformed_approval", null, null, WebWriteOutcome.PossiblyLanded, TmdbError.Malformed)
        }
    }

    /** Cancels ALL browser attempts, not just a descriptor. Does not sign out an already committed session. */
    suspend fun cancelApproval() = mutations.withLock {
        http.call("/api/auth/cancel", method = HttpMethod.Post)
        revalidate()
        Unit
    }

    /** A callback, signedIn=1 or cross-tab message is only a hint to call this; never establishes identity itself. */
    suspend fun onApprovalReturned(): AuthSession? = revalidate()

    override suspend fun signOut(onSignedOutLocally: () -> Unit) = mutations.withLock {
        invalidateSession()
        http.call("/api/auth/logout", method = HttpMethod.Post)
        // The logout response may be late relative to another tab's new cookie: fetch authoritative truth instead.
        revalidate()
        if (current.value == null) onSignedOutLocally()
    }

    override suspend fun expireSession(failed: AuthSession) {
        if (current.value == failed) {
            recover(webFailure(401, """{"error":"session_expired"}""", null, false))
        }
    }

    override suspend fun createRequestCode(): RequestCode = throw UnsupportedOperationException("Web login requires startApproval(), with a server-owned attempt")
    override suspend fun awaitApproval(code: RequestCode): SignInOutcome = throw UnsupportedOperationException("Web login requires onApprovalReturned(); no native token exchange or account defaults")
}
