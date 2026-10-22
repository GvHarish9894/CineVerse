package com.techgv.cineverse.auth

import com.techgv.cineverse.data.auth.ApprovalAttempt
import com.techgv.cineverse.data.auth.ApprovalFlow
import com.techgv.cineverse.data.auth.PendingAttemptStore
import com.techgv.cineverse.data.auth.SignInOutcome
import com.techgv.cineverse.data.auth.isPendingCallback

/**
 * Phone approval (plan §3, K11): the approval page in a Custom Tab, back through the `cineverse://auth?attempt=…`
 * deep link (also on cold start). [pending] must survive process death (a persistent store, P8).
 */
class CustomTabsApprovalFlow(private val pending: PendingAttemptStore) : ApprovalFlow {
    override suspend fun start(attempt: ApprovalAttempt): Unit =
        TODO("P8: save the attempt in [pending], then open attempt.code.approvalUrl in a Custom Tab")

    override suspend fun onCallback(uri: String): Boolean = pending.isPendingCallback(uri)

    override suspend fun awaitApproval(attempt: ApprovalAttempt): SignInOutcome =
        TODO("P8: wait for the bound callback (or \"I've approved it\"), then verify by the token exchange")
}
