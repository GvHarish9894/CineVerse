package com.techgv.cineverse.auth

import com.techgv.cineverse.data.auth.ApprovalAttempt
import com.techgv.cineverse.data.auth.ApprovalFlow
import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.auth.InMemoryPendingAttemptStore
import com.techgv.cineverse.data.auth.PendingAttemptStore
import com.techgv.cineverse.data.auth.RequestCode
import com.techgv.cineverse.data.auth.SignInOutcome
import com.techgv.cineverse.data.auth.approvalCallbackUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Both platform wrappers must reach the same persist/open/verify path with an injected platform boundary. */
suspend fun approvalFlowBoundaryFixture(factory: (AuthRepository, PendingAttemptStore) -> ApprovalFlow) {
    var exchanges = 0
    val auth = object : AuthRepository {
        override val session = MutableStateFlow<AuthSession?>(null)
        override val sessionExpired = emptyFlow<Unit>()
        override suspend fun createRequestCode() = error("Use native bound creation")
        override suspend fun awaitApproval(code: RequestCode): SignInOutcome {
            exchanges++
            return SignInOutcome.TimedOut
        }
        override suspend fun signOut(onSignedOutLocally: () -> Unit) = error("Not used")
        override suspend fun expireSession(failed: AuthSession) = error("Not used")
    }
    val pending = InMemoryPendingAttemptStore()
    val flow = factory(auth, pending)
    val attempt = ApprovalAttempt.of(RequestCode("fake-only"))
    flow.start(attempt)
    assertEquals(attempt.id, pending.load()?.id)
    assertFalse(flow.onCallback("cineverse://auth?attempt=stale"))
    assertEquals(0, exchanges)
    assertTrue(flow.onCallback(approvalCallbackUri(attempt)))
    assertEquals(1, exchanges)
    assertEquals(attempt.id, pending.load()?.id)
}
