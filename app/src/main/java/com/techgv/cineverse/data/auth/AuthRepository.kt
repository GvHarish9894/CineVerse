package com.techgv.cineverse.data.auth

import android.util.Log
import com.techgv.cineverse.data.tmdb.AccessTokenBody
import com.techgv.cineverse.data.tmdb.AccessTokenDto
import com.techgv.cineverse.data.tmdb.AccountId
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.ConvertSessionBody
import com.techgv.cineverse.data.tmdb.DeleteAccessTokenBody
import com.techgv.cineverse.data.tmdb.DeleteSessionBody
import com.techgv.cineverse.data.tmdb.RequestTokenBody
import com.techgv.cineverse.data.tmdb.SessionId
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.tmdb.TmdbOperation
import com.techgv.cineverse.data.tmdb.UserAccessToken
import com.techgv.cineverse.data.tmdb.UserBearer
import com.techgv.cineverse.data.tmdb.tmdbCall
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** Short-lived v4 request token: the only credential ever rendered on screen; never logged. */
class RequestCode(val requestToken: String) {
    val approvalUrl: String get() = "https://www.themoviedb.org/auth/access?request_token=$requestToken"
    override fun toString() = "RequestCode(<redacted>)"
}

sealed interface SignInOutcome {
    data class SignedIn(val credentials: Credentials) : SignInOutcome
    /** 33: "Code expired — press OK for a new one" → new request code. */
    data object CodeExpired : SignInOutcome
    /** 7 / 10: app-credential banner. */
    data object AppCredentialError : SignInOutcome
    /** Other 4xx: "TMDB didn't approve this sign-in" → "Get a new code". */
    data class Rejected(val error: TmdbError) : SignInOutcome
    /** 2-min deadline: "Still waiting — press OK to check again" (same code). */
    data object TimedOut : SignInOutcome
    /** A poll for this repository is already running (single-flight); ignore the press. */
    data object AlreadyPolling : SignInOutcome
    /** Approved, but convert / account kept failing: nothing persisted, restart login. */
    data class Failed(val error: TmdbError) : SignInOutcome
    /** A sign-out or session expiry happened while this attempt ran: nothing persisted. */
    data object Superseded : SignInOutcome
}

class AuthRepository(
    private val api: TmdbApi,
    private val store: CredentialStore,
    /** Bumps the app generation and clears account caches (§3.4, §4.2). */
    private val onAccountChanged: () -> Unit,
    private val pollIntervalMillis: Long = 3_000,
    private val pollDeadlineMillis: Long = 120_000,
    private val stepAttempts: Int = 3,
    private val signOutTimeoutMillis: Long = 5_000,
) {
    val credentials: Flow<Credentials?> = store.credentials
    private val pollLock = Mutex()

    /** Serializes credential transitions: sign-in commit, sign-out, session expiry. */
    private val transitionLock = Mutex()

    /** Bumped by sign-out / expiry: a sign-in attempt started under an older epoch never commits. */
    private val attemptEpoch = AtomicLong()

    private val expiryEvents = Channel<Unit>(Channel.CONFLATED)

    /**
     * One event per expired sign-in (§3.3 "Sign-in expired"), emitted after the local sign-out. Buffered
     * until collected (AppNav resets to Login with the message).
     */
    val sessionExpired: Flow<Unit> = expiryEvents.receiveAsFlow()

    /** §4.1 step 1. Throws [TmdbException]. */
    suspend fun createRequestCode(): RequestCode = tmdbCall(TmdbOperation.Catalog) {
        api.createRequestToken(RequestTokenBody(redirectTo = "https://www.themoviedb.org/"))
    }.let { RequestCode(it.requestToken) }

    /**
     * §4.1 steps 3–5: polls every 3 s for up to 2 min, then converts, reads the account and commits the
     * tuple. Cancelling the caller (BACK, guest, dispose, background) stops it; nothing partial is saved.
     */
    suspend fun awaitApproval(code: RequestCode): SignInOutcome {
        if (!pollLock.tryLock()) return SignInOutcome.AlreadyPolling
        val epoch = attemptEpoch.get()
        try {
            val access = when (val poll = pollAccessToken(code)) {
                is Poll.Approved -> poll.access
                is Poll.Stopped -> return poll.outcome
            }
            // The request token is consumed: retry each later step on its own, never the exchange.
            val session = try {
                step(TmdbOperation.Catalog) { api.convertToSession(ConvertSessionBody(access.accessToken)) }
            } catch (e: TmdbException) {
                return SignInOutcome.Failed(e.error)
            }
            val account = try {
                step(TmdbOperation.Session) { api.account(session.sessionId) }
            } catch (e: TmdbException) {
                return SignInOutcome.Failed(e.error)
            }
            val credentials = Credentials(
                userAccessToken = UserAccessToken(access.accessToken),
                accountObjectId = AccountObjectId(access.accountId),
                sessionId = SessionId(session.sessionId),
                accountId = AccountId(account.id),
                username = account.username,
                name = account.name,
            )
            transitionLock.withLock {
                // Last check before the commit: BACK / dispose cancelled the caller, or sign-out ran meanwhile.
                currentCoroutineContext().ensureActive()
                if (attemptEpoch.get() != epoch) return SignInOutcome.Superseded
                onAccountChanged()
                withContext(NonCancellable) { store.save(credentials) } // one atomic edit; never torn
            }
            return SignInOutcome.SignedIn(credentials)
        } finally {
            pollLock.unlock()
        }
    }

    private sealed interface Poll {
        class Approved(val access: AccessTokenDto) : Poll
        class Stopped(val outcome: SignInOutcome) : Poll
    }

    private suspend fun pollAccessToken(code: RequestCode): Poll = withTimeoutOrNull(pollDeadlineMillis) {
        var result: Poll? = null
        while (result == null) {
            result = try {
                Poll.Approved(tmdbCall(TmdbOperation.AccessTokenExchange) { api.createAccessToken(AccessTokenBody(code.requestToken)) })
            } catch (e: TmdbException) {
                when (val error = e.error) {
                    is TmdbError.RateLimited -> null.also { delay(error.retryAfterMillis) }
                    TmdbError.ApprovalPending, TmdbError.Network, TmdbError.Malformed, is TmdbError.Server ->
                        null.also { delay(pollIntervalMillis) }
                    TmdbError.RequestTokenExpired -> Poll.Stopped(SignInOutcome.CodeExpired)
                    TmdbError.AppCredential -> Poll.Stopped(SignInOutcome.AppCredentialError)
                    else -> Poll.Stopped(SignInOutcome.Rejected(error))
                }
            }
        }
        result
    } ?: Poll.Stopped(SignInOutcome.TimedOut)

    /**
     * Retries transient failures (network / 5xx / 429) of one post-approval step; throws [TmdbException].
     * After a second 429 (tmdbCall already retried once) it waits at least that Retry-After.
     */
    private suspend fun <T> step(operation: TmdbOperation, block: suspend () -> T): T {
        repeat(stepAttempts - 1) {
            try {
                return tmdbCall(operation, block)
            } catch (e: TmdbException) {
                val transient = e.error == TmdbError.Network || e.error is TmdbError.Server || e.error is TmdbError.RateLimited
                if (!transient) throw e
                delay(max(pollIntervalMillis, (e.error as? TmdbError.RateLimited)?.retryAfterMillis ?: 0))
            }
        }
        return tmdbCall(operation, block)
    }

    /**
     * §4.2: local sign-out first (generation bump, account caches, tuple), then bounded best-effort
     * remote revocation. Remote failures are logged without token values and ignored.
     */
    suspend fun signOut(): Unit = withContext(NonCancellable) {
        val snapshot = transitionLock.withLock {
            attemptEpoch.incrementAndGet() // before the empty-store return: an in-flight sign-in must not commit later
            store.credentials.first()?.also { clearLocally() }
        } ?: return@withContext
        withTimeoutOrNull(signOutTimeoutMillis) {
            coroutineScope {
                launch {
                    remoteSignOutStep("delete access token") {
                        api.deleteAccessToken(
                            UserBearer(snapshot.userAccessToken),
                            DeleteAccessTokenBody(snapshot.userAccessToken.value),
                        )
                    }
                }
                launch {
                    remoteSignOutStep("delete session") { api.deleteSession(DeleteSessionBody(snapshot.sessionId.value)) }
                }
            }
        } ?: Log.w(TAG, "Remote sign-out timed out")
    }

    /**
     * §3.3 session / user token invalid: local sign-out of [failed] only. Identity-bound and deduplicated:
     * a failure from an earlier sign-in (or a repeat for an already cleared one) changes nothing.
     */
    suspend fun expireSession(failed: Credentials): Unit = withContext(NonCancellable) {
        val expired = transitionLock.withLock {
            (store.credentials.first() == failed).also { current ->
                if (current) {
                    attemptEpoch.incrementAndGet()
                    clearLocally()
                }
            }
        }
        if (expired) expiryEvents.trySend(Unit)
    }

    /** Local half of §4.2; caller holds [transitionLock]. */
    private suspend fun clearLocally() {
        onAccountChanged()
        store.clear()
    }

    private suspend fun remoteSignOutStep(name: String, block: suspend () -> Unit) {
        try {
            tmdbCall(TmdbOperation.UserToken, block)
        } catch (e: TmdbException) {
            Log.w(TAG, "Remote sign-out: $name failed (${e.error})")
        }
    }

    private companion object {
        const val TAG = "AuthRepository"
    }
}
