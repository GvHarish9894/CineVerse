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

private fun JsonElement?.webGeneration(): Long {
    val value = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw SerializationException("Invalid web generation")
    if (!Regex("-?(0|[1-9][0-9]*)").matches(value)) throw SerializationException("Invalid web generation")
    return value.toLongOrNull() ?: throw SerializationException("Invalid web generation")
}

internal fun decodeWebSession(text: String): AuthSession? {
    fun malformed(): Nothing = throw SerializationException("Invalid web session")
    val obj = WebJson.parseToJsonElement(text) as? JsonObject ?: malformed()
    val signedIn = (obj["signedIn"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: malformed()
    val generation = obj["sessionGeneration"].webGeneration()
    if (!signedIn) {
        if (obj["accountObjectId"] != JsonNull || obj["displayName"] != JsonNull || generation != 0L) malformed()
        return null
    }
    val account = (obj["accountObjectId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: malformed()
    val name = (obj["displayName"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed()
    return AuthSession(AccountObjectId(account), name, generation)
}

internal fun decodeWebAccountDefaults(text: String): AccountDefaultsMetadata {
    fun malformed(): Nothing = throw SerializationException("Invalid web account defaults")
    val obj = WebJson.parseToJsonElement(text) as? JsonObject ?: malformed()
    fun optionalString(key: String): String? = when (val value = obj[key]) {
        null, JsonNull -> null
        else -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed()
    }
    val account = optionalString("accountObjectId")?.takeIf { it.isNotBlank() } ?: malformed()
    val adult = when (val value = obj["includeAdult"]) {
        null, JsonNull -> null
        else -> (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: malformed()
    }
    return AccountDefaultsMetadata(AccountObjectId(account), obj["sessionGeneration"].webGeneration(),
        optionalString("language"), optionalString("region"), adult)
}

/** Explicit browser approval controller + AuthRepository. Only /api/session establishes identity. */
class WebAuthRepository(private val http: WebProxyHttp) : AuthRepository {
    private val lock = SynchronizedObject()
    private val mutations = Mutex()
    private var revision = 0L
    private var sessionEpoch = 0L
    private var verified = false
    private val current = MutableStateFlow<AuthSession?>(null)
    override val session: StateFlow<AuthSession?> = current.asStateFlow()
    private val expired = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    // Filter per subscriber at delivery: a busy collector may still hold an earlier identity's notice.
    override val sessionExpired: Flow<Unit> = expired.filter { epoch ->
        synchronized(lock) { epoch == sessionEpoch && verified && current.value == null }
    }.map { }

    /** C3/focus/visibility hook: immediately makes in-flight account results unusable; then call revalidate. */
    fun invalidateSession() = synchronized(lock) { revision++; sessionEpoch++; verified = false }

    /** Latest-started revalidation wins, regardless of response completion order. Revisions are local, not wire ordering. */
    suspend fun revalidate(): AuthSession? {
        val ticket = synchronized(lock) { ++revision }
        val value = try { decodeWebSession(http.call("/api/session")) }
        catch (_: SerializationException) { throw WebProxyException("malformed_session", null, null, WebWriteOutcome.NotApplicable, TmdbError.Malformed) }
        return synchronized(lock) {
            if (ticket == revision) {
                if (current.value != value) sessionEpoch++
                current.value = value
                verified = true
            }
            current.value
        }
    }

    /** Root status must distinguish a verified guest from an unverified/superseded null snapshot. */
    internal fun isVerifiedSession(expected: AuthSession?): Boolean = synchronized(lock) {
        verified && current.value == expected
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
            synchronized(lock) {
                if (failure.code == "session_expired" && previous != null && verified && current.value == null) {
                    expired.tryEmit(sessionEpoch)
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (_: TmdbException) { /* Keep unverified until an authoritative sync succeeds. */ }
    }

    /** Explicit private read for the caller's captured sign-in; never applies settings or participates in login. */
    suspend fun accountDefaults(expected: AuthSession): AccountDefaultsMetadata {
        val ticket = capture(expected)
        try {
            val metadata = try { decodeWebAccountDefaults(http.call("/api/account/defaults", expected = expected)) }
            catch (_: SerializationException) {
                throw WebProxyException("malformed_response", null, null, WebWriteOutcome.NotApplicable, TmdbError.Malformed)
            }
            check(expected, ticket)
            if (metadata.accountObjectId != expected.accountObjectId || metadata.sessionGeneration != expected.sessionGeneration) {
                throw staleWebSession()
            }
            return metadata
        } catch (failure: WebProxyException) {
            recover(failure)
            throw failure
        }
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
