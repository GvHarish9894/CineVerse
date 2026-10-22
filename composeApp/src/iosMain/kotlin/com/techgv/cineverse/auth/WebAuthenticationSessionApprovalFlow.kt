package com.techgv.cineverse.auth

import com.techgv.cineverse.data.auth.ApprovalAttempt
import com.techgv.cineverse.data.auth.ApprovalFlow
import com.techgv.cineverse.data.auth.PendingAttemptStore
import com.techgv.cineverse.data.auth.SignInOutcome
import com.techgv.cineverse.data.auth.isPendingCallback

/**
 * iOS approval (plan §3, K11): ASWebAuthenticationSession with callback scheme `cineverse`; the session object is
 * retained for the attempt's lifetime. [pending] must survive process death (a persistent store, P8).
 */
class WebAuthenticationSessionApprovalFlow(private val pending: PendingAttemptStore) : ApprovalFlow {
    override suspend fun start(attempt: ApprovalAttempt): Unit =
        TODO("P8: save the attempt in [pending], then start an ASWebAuthenticationSession on attempt.code.approvalUrl")

    override suspend fun onCallback(uri: String): Boolean = pending.isPendingCallback(uri)

    override suspend fun awaitApproval(attempt: ApprovalAttempt): SignInOutcome =
        TODO("P8: wait for the session's bound callback (user cancel → stop), then verify by the token exchange")
}
