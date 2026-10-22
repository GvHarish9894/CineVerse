package com.techgv.cineverse.data.auth

import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.presentation.FakeAuthRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §3 ApprovalFlow rules: callback = hint bound to the pending attempt; consumed outcomes clear it. */
class ApprovalFlowTest {
    private val store = InMemoryPendingAttemptStore()
    private val auth = FakeAuthRepository()
    private val flow = PollingApprovalFlow(auth, store)
    private val first = ApprovalAttempt.of(RequestCode("t1"))
    private val second = ApprovalAttempt.of(RequestCode("t2"))

    @Test fun aCallbackOnlyCountsForThePendingAttempt() = runTest {
        store.save(second)
        assertTrue(store.isPendingCallback(approvalCallbackUri(second)))
        assertFalse(store.isPendingCallback(approvalCallbackUri(first)), "an older attempt's redirect")
        assertFalse(store.isPendingCallback("cineverse://other?attempt=${second.id}"))
        assertFalse(store.isPendingCallback("https://evil.example/?attempt=${second.id}"))
        assertTrue(store.isPendingCallback("cineverse://auth?x=1&attempt=${second.id}"))
        store.clear()
        assertFalse(store.isPendingCallback(approvalCallbackUri(second)), "nothing pending")
    }

    @Test fun pollingIsBoundToThePendingAttemptAndIgnoresCallbacks() = runTest {
        auth.approval = { SignInOutcome.TimedOut }
        flow.start(first)
        flow.start(second) // a newer attempt replaces the first
        assertEquals(SignInOutcome.Superseded, flow.awaitApproval(first))
        assertEquals(SignInOutcome.TimedOut, flow.awaitApproval(second))
        assertEquals(second.id, store.load()?.id, "no answer yet: the same attempt stays pending")
        assertFalse(flow.onCallback(approvalCallbackUri(second)), "TV approval happens on another device")
        assertEquals(listOf("t2"), auth.waits)
    }

    @Test fun deniedExpiredOrSignedInAttemptsAreConsumed() = runTest {
        for (outcome in listOf(SignInOutcome.Rejected(TmdbError.ExchangeRejected(401, 17)), SignInOutcome.CodeExpired)) {
            auth.approval = { outcome }
            flow.start(first)
            assertEquals(outcome, flow.awaitApproval(first))
            assertNull(store.load())
            assertEquals(SignInOutcome.Superseded, flow.awaitApproval(first), "a consumed attempt is never polled again")
        }
    }

    @Test fun attemptIdsAreUniqueAndNeverPrintTheToken() {
        assertTrue(first.id != second.id)
        assertFalse("t1" in first.toString())
    }
}
