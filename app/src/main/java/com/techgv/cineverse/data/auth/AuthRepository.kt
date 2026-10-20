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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    data object Rejected : SignInOutcome
    /** 2-min deadline: "Still waiting — press OK to check again" (same code). */
    data object TimedOut : SignInOutcome
    /** A poll for this repository is already running (single-flight); ignore the press. */
    data object AlreadyPolling : SignInOutcome
    /** Approved, but convert / account kept failing: nothing persisted, restart login. */
    data class Failed(val error: TmdbError) : SignInOutcome
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
            withContext(NonCancellable) {
                onAccountChanged()
                store.save(credentials)
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
                    else -> Poll.Stopped(SignInOutcome.Rejected)
                }
            }
        }
        result
    } ?: Poll.Stopped(SignInOutcome.TimedOut)

    /** Retries transient failures (network / 5xx / 429) of one post-approval step; throws [TmdbException]. */
    private suspend fun <T> step(operation: TmdbOperation, block: suspend () -> T): T {
        repeat(stepAttempts - 1) {
            try {
                return tmdbCall(operation, block)
            } catch (e: TmdbException) {
                val transient = e.error == TmdbError.Network || e.error is TmdbError.Server || e.error is TmdbError.RateLimited
                if (!transient) throw e
                delay(pollIntervalMillis)
            }
        }
        return tmdbCall(operation, block)
    }

    /**
     * §4.2: local sign-out first (generation bump, account caches, tuple), then bounded best-effort
     * remote revocation. Remote failures are logged without token values and ignored.
     */
    suspend fun signOut(): Unit = withContext(NonCancellable) {
        val snapshot = store.credentials.first() ?: return@withContext
        onAccountChanged()
        store.clear()
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
