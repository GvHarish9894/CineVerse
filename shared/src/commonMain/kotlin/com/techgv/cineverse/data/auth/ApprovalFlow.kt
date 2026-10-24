package com.techgv.cineverse.data.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** One sign-in attempt: [id] is what a redirect callback must name (`cineverse://auth?attempt=<id>`); [code] its request token. */
class ApprovalAttempt(val id: String, val code: RequestCode) {
    override fun toString() = "ApprovalAttempt($id)"

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun of(code: RequestCode) = ApprovalAttempt(Uuid.random().toHexString(), code)
    }
}

/** The one attempt awaiting approval, kept where a cold-started app (phone/iOS callback, web tab) can find it. */
interface PendingAttemptStore {
    suspend fun save(attempt: ApprovalAttempt)
    suspend fun load(): ApprovalAttempt?
    suspend fun clear()
}

/** Process-lifetime store: enough where no callback can arrive after process death (TV QR). */
class InMemoryPendingAttemptStore : PendingAttemptStore {
    private val lock = Mutex()
    private var attempt: ApprovalAttempt? = null
    override suspend fun save(attempt: ApprovalAttempt) = lock.withLock { this.attempt = attempt }
    override suspend fun load(): ApprovalAttempt? = lock.withLock { attempt }
    override suspend fun clear() = lock.withLock { attempt = null }
}

/**
 * §3 / §4.1 how the user approves a request code, per platform: TV = QR + polling ([PollingApprovalFlow]); phone =
 * Custom Tabs / ASWebAuthenticationSession + `cineverse://auth` callback (P8); web = new tab + proxy callback (P9).
 * Rules every actual keeps:
 * - [start] persists [ApprovalAttempt] as the pending one (a newer attempt replaces it) before showing the approval page.
 * - [onCallback] is a hint, never proof: it only reports whether the URI names the pending attempt
 *   ([isPendingCallback]); approval is verified by the token exchange in [awaitApproval].
 * - [awaitApproval] is bound to its attempt: a non-pending one is [SignInOutcome.Superseded], and so is a result that
 *   arrives after a newer [start] (it never clears the newer attempt). Outcomes: deny →
 *   [SignInOutcome.Rejected], 15-min expiry / consumed token → [SignInOutcome.CodeExpired] / [SignInOutcome.Failed]
 *   (the attempt is cleared), no answer yet → [SignInOutcome.TimedOut] (same attempt). Cancel = cancelling the
 *   awaiting coroutine (BACK, background, dispose): the attempt stays pending for the next [awaitApproval].
 */
interface ApprovalFlow {
    suspend fun start(attempt: ApprovalAttempt)
    suspend fun onCallback(uri: String): Boolean
    suspend fun awaitApproval(attempt: ApprovalAttempt): SignInOutcome
}

/** Redirect target of the callback platforms (K11 spike: TMDB stores any `redirect_to` verbatim; see the plan). */
const val APPROVAL_CALLBACK_URI = "cineverse://auth"

/** The callback URI for [attempt]. */
fun approvalCallbackUri(attempt: ApprovalAttempt) = "$APPROVAL_CALLBACK_URI?attempt=${attempt.id}"

/** True when [uri] is `cineverse://auth?attempt=<id>` naming the pending attempt; anything else is ignored. */
suspend fun PendingAttemptStore.isPendingCallback(uri: String): Boolean {
    val id = Regex("cineverse://auth\\?attempt=([A-Za-z0-9_-]+)").matchEntire(uri)?.groupValues?.get(1)
        ?: return false
    return id == load()?.id
}

/** TV: the QR shows the approval URL for another device; "I've approved it" polls the exchange (§4.1). No callback. */
class PollingApprovalFlow(
    private val auth: AuthRepository,
    private val pending: PendingAttemptStore = InMemoryPendingAttemptStore(),
) : ApprovalFlow {
    /** Serializes pending-attempt transitions; never held across the approval wait. */
    private val transitions = Mutex()

    override suspend fun start(attempt: ApprovalAttempt) = transitions.withLock { pending.save(attempt) }

    override suspend fun onCallback(uri: String): Boolean = false

    override suspend fun awaitApproval(attempt: ApprovalAttempt): SignInOutcome {
        if (!transitions.withLock { pending.load()?.id == attempt.id }) return SignInOutcome.Superseded
        val outcome = auth.awaitApproval(attempt.code)
        return transitions.withLock {
            // A newer start() during the wait owns the pending slot: this late result must not touch it.
            if (pending.load()?.id != attempt.id) return@withLock SignInOutcome.Superseded
            if (outcome.consumesAttempt) pending.clear()
            outcome
        }
    }
}

/** The request token is spent (signed in, expired, denied, or exchanged but not completed): a new attempt is needed. */
val SignInOutcome.consumesAttempt: Boolean
    get() = this is SignInOutcome.SignedIn || this == SignInOutcome.CodeExpired || this is SignInOutcome.Rejected ||
        this is SignInOutcome.Failed
