package com.techgv.cineverse.data.auth

import com.techgv.cineverse.data.tmdb.AccountDto
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.TmdbError
import kotlinx.coroutines.flow.Flow

/**
 * The signed-in identity common code binds to (plan §3); `null` = guest. Equality = one sign-in: every new sign-in
 * gets a larger [sessionGeneration], so a result or write bound to an older session never matches the current one.
 * Never carries tokens or session ids (those stay native, or on the server for web).
 * Wire form (server `/api/session`, P9): `{"signedIn": false}` ↔ `null`; signed in ↔ these fields.
 */
data class AuthSession(val accountObjectId: AccountObjectId, val displayName: String, val sessionGeneration: Long)

val AuthSession?.signedIn: Boolean get() = this != null

/** Short-lived v4 request token: the only credential ever rendered on screen; never logged. */
class RequestCode(val requestToken: String) {
    val approvalUrl: String get() = "https://www.themoviedb.org/auth/access?request_token=$requestToken"
    override fun toString() = "RequestCode(<redacted>)"
}

sealed interface SignInOutcome {
    /** [account] carries the account's content defaults (iso_639_1 / iso_3166_1 / include_adult, §7.9). */
    data class SignedIn(val session: AuthSession, val account: AccountDto) : SignInOutcome
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

/**
 * §4 auth, the common-facing contract: it never returns or takes native credentials. Native: `NativeAuthRepository`
 * (v4 exchange, session convert, revocation, encrypted credential store). Web: the server proxy (P9).
 */
interface AuthRepository {
    /** The current identity; `null` = guest. */
    val session: Flow<AuthSession?>

    /**
     * One event per expired sign-in (§3.3 "Sign-in expired"), emitted after the local sign-out; dropped when a newer
     * transition (e.g. a new sign-in) superseded it, so it never bounces a fresh session to Login.
     */
    val sessionExpired: Flow<Unit>

    /** §4.1 step 1. Throws `TmdbException`. */
    suspend fun createRequestCode(): RequestCode

    /** §4.1 steps 3–5: waits for approval, then commits the sign-in atomically; nothing partial is saved. */
    suspend fun awaitApproval(code: RequestCode): SignInOutcome

    /** §4.2: local sign-out first; [onSignedOutLocally] runs once that is done, before best-effort revocation. */
    suspend fun signOut(onSignedOutLocally: () -> Unit = {})

    /** §3.3 session invalid: local sign-out of [failed] only; a stale or repeated report changes nothing. */
    suspend fun expireSession(failed: AuthSession)
}
