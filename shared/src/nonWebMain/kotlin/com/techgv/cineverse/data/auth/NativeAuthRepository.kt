package com.techgv.cineverse.data.auth

import com.techgv.cineverse.data.tmdb.AccessTokenBody
import com.techgv.cineverse.data.tmdb.AccessTokenDto
import com.techgv.cineverse.data.tmdb.AccountDto
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
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.techgv.cineverse.util.logWarning
import kotlin.math.max

/**
 * §4 auth on the native transport: v4 request token → approval polling → access token → session convert →
 * `/3/account`, credentials committed atomically to the encrypted [store]. Common code sees only [session];
 * native account calls resolve their [Credentials] through [credentialsFor].
 */
class NativeAuthRepository(
    private val api: TmdbApi,
    private val store: CredentialStore,
    /** Bumps the app generation and clears account caches (§3.4, §4.2). */
    private val onAccountChanged: () -> Unit,
    private val pollIntervalMillis: Long = 3_000,
    private val pollDeadlineMillis: Long = 120_000,
    private val stepAttempts: Int = 3,
    private val signOutTimeoutMillis: Long = 5_000,
) : AuthRepository {
    /** The signed-in tuple (native consumers only: account calls, the debug auth spike). */
    val credentials: Flow<Credentials?> = store.credentials

    private val sessionLock = SynchronizedObject()
    private var lastGeneration = 0L

    // shortcut: keeps every sign-in of this process (a handful) so a write bound to an older session still uses its own
    // credentials, exactly like the captured Credentials tuple did; prune if sign-ins per process ever grow large.
    private val sessions = HashMap<Credentials, AuthSession>()
    private val credentialsBySession = HashMap<AuthSession, Credentials>()

    override val session: Flow<AuthSession?> = store.credentials.map { it?.let(::sessionOf) }.distinctUntilChanged()

    /** One [AuthSession] per credentials tuple (= one sign-in), with a generation larger than every earlier one. */
    internal fun sessionOf(credentials: Credentials): AuthSession = synchronized(sessionLock) {
        sessions.getOrPut(credentials) {
            AuthSession(credentials.accountObjectId, credentials.displayName, ++lastGeneration)
                .also { credentialsBySession[it] = credentials }
        }
    }

    /** The credentials [session] was signed in with (also after it ended: its calls fail on TMDB, never switch account). */
    fun credentialsFor(session: AuthSession): Credentials =
        synchronized(sessionLock) { credentialsBySession[session] } ?: error("Unknown session ${session.sessionGeneration}")
    private val pollLock = Mutex()

    /** Serializes credential transitions: sign-in commit, sign-out, session expiry. */
    private val transitionLock = Mutex()

    /**
     * Bumped by every auth transition (sign-in commit, sign-out, expiry): a sign-in attempt started under an
     * older epoch never commits, and a buffered expiry event from an older epoch is dropped.
     */
    private val attemptEpoch = atomic(0L)

    /** Carries the epoch the expiry produced. */
    private val expiryEvents = Channel<Long>(Channel.CONFLATED)

    /** Buffered until collected (AppNav resets to Login with the message); see [AuthRepository.sessionExpired]. */
    override val sessionExpired: Flow<Unit> = expiryEvents.receiveAsFlow().filter { it == attemptEpoch.value }.map { }

    /** §4.1 step 1. Throws [TmdbException]. */
    override suspend fun createRequestCode(): RequestCode = requestCode("https://www.themoviedb.org/")

    /** Mobile only: the id exists before TMDB creates the token, so its redirect is bound to this attempt. */
    suspend fun createRequestCode(attemptId: String): RequestCode {
        require(Regex("[A-Za-z0-9_-]+").matches(attemptId)) { "Invalid approval attempt id" }
        return requestCode("$APPROVAL_CALLBACK_URI?attempt=$attemptId")
    }

    private suspend fun requestCode(redirect: String): RequestCode = tmdbCall(TmdbOperation.Catalog) {
        api.createRequestToken(RequestTokenBody(redirectTo = redirect))
    }.let { RequestCode(it.requestToken) }

    /**
     * §4.1 steps 3–5: polls every 3 s for up to 2 min, then converts, reads the account and commits the
     * tuple. Cancelling the caller (BACK, guest, dispose, background) stops it; nothing partial is saved.
     */
    override suspend fun awaitApproval(code: RequestCode): SignInOutcome {
        if (!pollLock.tryLock()) return SignInOutcome.AlreadyPolling
        val epoch = attemptEpoch.value
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
                if (attemptEpoch.value != epoch) return SignInOutcome.Superseded
                attemptEpoch.incrementAndGet()
                onAccountChanged()
                withContext(NonCancellable) { store.save(credentials) } // one atomic edit; never torn
            }
            return SignInOutcome.SignedIn(sessionOf(credentials), account)
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
     * remote revocation. Remote failures are logged without token values and ignored. [onSignedOutLocally]
     * runs once the local half is done (not when it throws), before the remote half.
     */
    override suspend fun signOut(onSignedOutLocally: () -> Unit): Unit = withContext(NonCancellable) {
        val snapshot = transitionLock.withLock {
            attemptEpoch.incrementAndGet() // before the empty-store return: an in-flight sign-in must not commit later
            store.credentials.first()?.also { clearLocally() }
        }
        onSignedOutLocally()
        if (snapshot == null) return@withContext
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
        } ?: logWarning(TAG, "Remote sign-out timed out")
    }

    override suspend fun expireSession(failed: AuthSession) = expireSession(credentialsFor(failed))

    /**
     * §3.3 session / user token invalid: local sign-out of [failed] only. Identity-bound and deduplicated:
     * a failure from an earlier sign-in (or a repeat for an already cleared one) changes nothing.
     */
    suspend fun expireSession(failed: Credentials): Unit = withContext(NonCancellable) {
        transitionLock.withLock {
            if (store.credentials.first() == failed) {
                val epoch = attemptEpoch.incrementAndGet()
                clearLocally()
                expiryEvents.trySend(epoch)
            }
        }
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
            logWarning(TAG, "Remote sign-out: $name failed (${e.error})")
        }
    }

    private companion object {
        const val TAG = "AuthRepository"
    }
}
