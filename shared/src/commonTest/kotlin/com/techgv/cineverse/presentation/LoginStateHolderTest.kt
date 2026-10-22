package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.auth.PollingApprovalFlow
import com.techgv.cineverse.data.auth.RequestCode
import com.techgv.cineverse.data.auth.SignInOutcome
import com.techgv.cineverse.data.settings.AccountDefaults
import com.techgv.cineverse.data.settings.InMemorySettingsStore
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.AccountDto
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §4.1 Login over [PollingApprovalFlow]: outcomes → copy keys, cancel on background, consumed codes. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginStateHolderTest {
    private val dispatcher = StandardTestDispatcher()
    private val auth = FakeAuthRepository()
    private val settings = SettingsRepository(InMemorySettingsStore())
    private val harish = session()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.holder() = LoginStateHolder(auth, PollingApprovalFlow(auth), settings, backgroundScope)

    private fun TestScope.signedInEvents(holder: LoginStateHolder) =
        mutableListOf<AuthSession>().also { list -> backgroundScope.launch { holder.signedIn.toList(list) } }

    @Test fun codeArrivesApprovalSignsInOnceAndOffersAccountDefaults() = runTest(dispatcher) {
        val approved = CompletableDeferred<Unit>()
        auth.approval = {
            approved.await()
            auth.sessions.value = harish // the repository commits before answering
            SignInOutcome.SignedIn(harish, AccountDto(548, "harish", "Harish", language = "ta", region = "IN"))
        }
        val holder = holder()
        val events = signedInEvents(holder)
        runCurrent()
        assertEquals("token-1", holder.state.value.code?.requestToken)
        assertEquals(LoginAction.Poll, holder.state.value.action)

        holder.onPrimary()
        runCurrent()
        assertTrue(holder.state.value.polling)
        assertEquals(LoginMessage.WaitingForApproval, holder.state.value.message)
        assertEquals(LoginButton.Checking, holder.state.value.button)

        approved.complete(Unit)
        runCurrent()
        assertEquals(listOf(harish), events)
        assertEquals(LoginScreenState.signedIn, holder.state.value)
        assertEquals(mapOf("aoid" to AccountDefaults("aoid", "ta-IN", "IN", false)), settings.pendingAccountDefaults.first())
        holder.onPrimary() // terminal: the consumed code is never polled again
        runCurrent()
        assertEquals(listOf("token-1"), auth.waits)
    }

    @Test fun stopPollingCancelsTheWaitAndKeepsTheCodeForTheNextPress() = runTest(dispatcher) {
        val holder = holder()
        runCurrent()
        holder.onPrimary()
        runCurrent()
        holder.stopPolling() // BACK / background / window focus lost
        assertEquals(LoginScreenState.ready(holder.state.value.code!!), holder.state.value)
        assertEquals("token-1", holder.state.value.code?.requestToken)

        holder.onPrimary()
        runCurrent()
        assertTrue(holder.state.value.polling)
        assertEquals(listOf("token-1", "token-1"), auth.waits)
    }

    @Test fun timeoutKeepsTheCodeAndExpiryAsksForANewOne() = runTest(dispatcher) {
        val answers = ArrayDeque(listOf<SignInOutcome>(SignInOutcome.TimedOut, SignInOutcome.CodeExpired))
        auth.approval = { answers.removeFirst() }
        val holder = holder()
        runCurrent()
        holder.onPrimary()
        runCurrent()
        assertEquals(LoginMessage.StillWaiting, holder.state.value.message)
        assertEquals(LoginAction.Poll, holder.state.value.action)
        assertEquals("token-1", holder.state.value.code?.requestToken)

        holder.onPrimary()
        runCurrent()
        assertEquals(LoginMessage.CodeExpired, holder.state.value.message)
        assertNull(holder.state.value.code)
        holder.onPrimary() // NewCode
        runCurrent()
        assertEquals("token-2", holder.state.value.code?.requestToken)
    }

    @Test fun aSupersededSignInStartsOverQuietlyWithANewCode() = runTest(dispatcher) {
        auth.approval = { SignInOutcome.Superseded }
        val holder = holder()
        runCurrent()
        holder.onPrimary()
        runCurrent()
        assertEquals(LoginScreenState.ready(holder.state.value.code!!), holder.state.value)
        assertEquals("token-2", holder.state.value.code?.requestToken)
    }

    @Test fun codeRequestFailuresMapToOfflineOrTheAppCredentialBanner() = runTest(dispatcher) {
        auth.createError = TmdbException(TmdbError.Network)
        val offline = holder()
        runCurrent()
        assertEquals(LoginMessage.Offline, offline.state.value.message)
        assertEquals(LoginAction.NewCode to LoginButton.TryAgain, offline.state.value.action to offline.state.value.button)

        auth.createError = TmdbException(TmdbError.AppCredential)
        val appKey = holder()
        runCurrent()
        assertEquals(LoginMessage.CodeError, appKey.state.value.message)
        assertTrue(appKey.state.value.appCredentialProblem)
    }

    @Test fun everyOutcomeThatStaysOnLoginHasItsKeysAndNextStep() {
        val code = RequestCode("t")
        val timedOut = SignInOutcome.TimedOut.toLoginScreenState(code)!!
        assertEquals(LoginScreenState(code, LoginMessage.StillWaiting, LoginButton.CheckAgain, LoginAction.Poll), timedOut)
        assertEquals(
            LoginScreenState(null, LoginMessage.CodeExpired, LoginButton.NewCode, LoginAction.NewCode),
            SignInOutcome.CodeExpired.toLoginScreenState(code),
        )
        assertEquals(LoginMessage.NotApproved, SignInOutcome.Rejected(TmdbError.ExchangeRejected(401, 17)).toLoginScreenState(code)?.message)
        assertEquals(LoginMessage.CouldNotFinish, SignInOutcome.Failed(TmdbError.Network).toLoginScreenState(code)?.message)
        val appKey = SignInOutcome.AppCredentialError.toLoginScreenState(code)!!
        assertTrue(appKey.appCredentialProblem)
        assertFalse(appKey.waiting)
        assertEquals(LoginScreenState.ready(code), SignInOutcome.AlreadyPolling.toLoginScreenState(code))
        assertNull(SignInOutcome.Superseded.toLoginScreenState(code))
    }
}
