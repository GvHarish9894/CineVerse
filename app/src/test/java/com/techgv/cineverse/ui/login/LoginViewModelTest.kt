package com.techgv.cineverse.ui.login

import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.CredentialCipher
import com.techgv.cineverse.data.auth.CredentialStore
import com.techgv.cineverse.data.auth.InMemoryPreferencesStore
import com.techgv.cineverse.data.auth.RequestCode
import com.techgv.cineverse.data.auth.SignInOutcome
import com.techgv.cineverse.data.auth.SoftwareKeys
import com.techgv.cineverse.data.settings.AccountDefaults
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.AccessTokenBody
import com.techgv.cineverse.data.tmdb.AccessTokenDto
import com.techgv.cineverse.data.tmdb.AccountDto
import com.techgv.cineverse.data.tmdb.ConvertSessionBody
import com.techgv.cineverse.data.tmdb.RequestTokenBody
import com.techgv.cineverse.data.tmdb.RequestTokenDto
import com.techgv.cineverse.data.tmdb.SessionDto
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.unusedTmdbApi
import com.techgv.cineverse.ui.components.SectionErrorKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var approved = false
    private var exchangeCalls = 0

    private val api = object : TmdbApi by unusedTmdbApi {
        override suspend fun createRequestToken(body: RequestTokenBody) = RequestTokenDto("request-token")
        override suspend fun createAccessToken(body: AccessTokenBody): AccessTokenDto {
            exchangeCalls++
            if (!approved) throw pending()
            return AccessTokenDto(accessToken = "user-access-token", accountId = "aoid")
        }
        override suspend fun convertToSession(body: ConvertSessionBody) = SessionDto("session")
        override suspend fun account(sessionId: String) = AccountDto(548, "harish", "Harish", language = "ta", region = "IN")
    }

    private val settings = SettingsRepository(InMemoryPreferencesStore())
    private val auth = AuthRepository(api, CredentialStore(InMemoryPreferencesStore(), CredentialCipher(SoftwareKeys())), onAccountChanged = {})

    /** TMDB "not approved yet": HTTP 422, status_code 41. */
    private fun pending(): HttpException {
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://api.themoviedb.org/4/auth/access_token").build())
            .protocol(Protocol.HTTP_1_1).code(422).message("error").build()
        return HttpException(Response.error<Any>("""{"success":false,"status_code":41}""".toResponseBody(), raw))
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `code arrives, pending keeps the spinner, approval signs in and offers account defaults`() = runTest(dispatcher) {
        val vm = LoginViewModel(auth, settings)
        runCurrent()
        assertEquals(LoginUiState.ready(RequestCode("request-token")).primaryAction, vm.state.value.primaryAction)
        assertEquals("request-token", vm.state.value.code?.requestToken)

        val name = async { vm.signedIn.first() }
        vm.onPrimary()
        runCurrent()
        assertTrue(vm.state.value.polling)
        assertEquals("Waiting for TMDB to confirm your approval…", vm.state.value.message)

        approved = true
        advanceTimeBy(3_001)
        assertEquals("Harish", name.await())
        assertEquals(AccountDefaults("aoid", "ta-IN", "IN", false), settings.accountDefaults.first())
    }

    @Test
    fun `stopPolling cancels the poll and keeps the code`() = runTest(dispatcher) {
        val vm = LoginViewModel(auth, settings)
        runCurrent()
        vm.onPrimary()
        runCurrent()
        val calls = exchangeCalls
        vm.stopPolling()
        advanceTimeBy(30_000)
        assertEquals(calls, exchangeCalls)
        assertEquals(LoginUiState.ready(RequestCode("request-token")).primaryLabel, vm.state.value.primaryLabel)
        assertEquals(LoginAction.Poll, vm.state.value.primaryAction)
        assertEquals("request-token", vm.state.value.code?.requestToken)

        vm.onPrimary() // polling again after a stop works (the old poll released its single-flight lock)
        runCurrent()
        assertTrue(vm.state.value.polling)
        assertEquals(calls + 1, exchangeCalls)
    }

    @Test
    fun `every outcome that stays on Login has its copy and next step`() {
        val code = RequestCode("t")
        val timedOut = SignInOutcome.TimedOut.toLoginState(code)!!
        assertEquals("Still waiting — press OK to check again", timedOut.message)
        assertEquals("Check again" to LoginAction.Poll, timedOut.primaryLabel to timedOut.primaryAction)
        assertEquals(code, timedOut.code)

        val expired = SignInOutcome.CodeExpired.toLoginState(code)!!
        assertEquals("Code expired — press OK for a new one", expired.message)
        assertEquals("Get a new code" to LoginAction.NewCode, expired.primaryLabel to expired.primaryAction)
        assertNull(expired.code)

        val rejected = SignInOutcome.Rejected(TmdbError.ExchangeRejected(401, 17)).toLoginState(code)!!
        assertEquals("TMDB didn't approve this sign-in", rejected.message)
        assertEquals(LoginAction.NewCode, rejected.primaryAction)

        val failed = SignInOutcome.Failed(TmdbError.Network).toLoginState(code)!!
        assertEquals(LoginAction.NewCode, failed.primaryAction)

        val appKey = SignInOutcome.AppCredentialError.toLoginState(code)!!
        assertEquals(SectionErrorKind.AppCredential.message, appKey.banner)
        assertTrue(!appKey.waiting)

        assertEquals(LoginUiState.ready(code), SignInOutcome.AlreadyPolling.toLoginState(code))
        assertNull(SignInOutcome.Superseded.toLoginState(code))
    }
}
