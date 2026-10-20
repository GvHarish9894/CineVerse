package com.techgv.cineverse.data.auth

import com.techgv.cineverse.data.tmdb.AccessTokenBody
import com.techgv.cineverse.data.tmdb.AccessTokenDto
import com.techgv.cineverse.data.tmdb.AccountDto
import com.techgv.cineverse.data.tmdb.ConvertSessionBody
import com.techgv.cineverse.data.tmdb.DeleteAccessTokenBody
import com.techgv.cineverse.data.tmdb.DeleteSessionBody
import com.techgv.cineverse.data.tmdb.RequestTokenBody
import com.techgv.cineverse.data.tmdb.RequestTokenDto
import com.techgv.cineverse.data.tmdb.SessionDto
import com.techgv.cineverse.data.tmdb.StatusDto
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.UserBearer
import com.techgv.cineverse.data.tmdb.unusedTmdbApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class AuthRepositoryTest {
    /** Scripted exchange responses; the last one repeats. */
    private val exchange = ArrayDeque<() -> AccessTokenDto>()
    private var exchangeCalls = 0
    private val exchangeTimes = mutableListOf<Long>()
    private val convert = ArrayDeque<() -> SessionDto>()
    private var accountCalls = 0
    private var remoteSignOut: suspend () -> StatusDto = { StatusDto(success = true) }
    private val remoteCalls = mutableListOf<String>()
    private var accountChanges = 0
    private lateinit var clock: TestScope

    private val api = object : TmdbApi by unusedTmdbApi {
        override suspend fun createRequestToken(body: RequestTokenBody) = RequestTokenDto("request-token")
        override suspend fun createAccessToken(body: AccessTokenBody): AccessTokenDto {
            exchangeCalls++
            exchangeTimes += clock.currentTime
            return (if (exchange.size > 1) exchange.removeFirst() else exchange.first())()
        }
        override suspend fun convertToSession(body: ConvertSessionBody): SessionDto =
            (if (convert.size > 1) convert.removeFirst() else convert.firstOrNull() ?: { SessionDto("session") })()
        override suspend fun account(sessionId: String): AccountDto {
            accountCalls++
            return AccountDto(id = 548, username = "harish", name = "Harish")
        }
        override suspend fun deleteAccessToken(bearer: UserBearer, body: DeleteAccessTokenBody): StatusDto {
            remoteCalls += "deleteAccessToken"
            return remoteSignOut()
        }
        override suspend fun deleteSession(body: DeleteSessionBody): StatusDto {
            remoteCalls += "deleteSession"
            return remoteSignOut()
        }
    }

    private val store = CredentialStore(InMemoryPreferencesStore(), CredentialCipher(SoftwareKeys()))
    private val repo = AuthRepository(api, store, onAccountChanged = { accountChanges++ })
    private val code = RequestCode("request-token")
    private val approved = { AccessTokenDto(accessToken = "user-access-token", accountId = "aoid") }

    private fun tmdbError(http: Int, statusCode: Int?, retryAfter: Long? = null): () -> Nothing = {
        val body = (statusCode?.let { """{"success":false,"status_code":$it}""" } ?: "not json").toResponseBody()
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://api.themoviedb.org/4/auth/access_token").build())
            .protocol(Protocol.HTTP_1_1).code(http).message("error")
            .apply { retryAfter?.let { header("Retry-After", it.toString()) } }
            .build()
        throw HttpException(Response.error<Any>(body, raw))
    }

    private fun authTest(block: suspend TestScope.() -> Unit) = runTest {
        clock = this
        block()
    }

    @Test
    fun `pending then approved converts, reads account and commits the tuple`() = authTest {
        exchange += listOf(tmdbError(422, 41), tmdbError(422, 41), approved)
        val outcome = repo.awaitApproval(code)
        assertTrue(outcome is SignInOutcome.SignedIn)
        assertEquals(listOf(0L, 3_000L, 6_000L), exchangeTimes)
        val saved = store.credentials.first()!!
        assertEquals("user-access-token", saved.userAccessToken.value)
        assertEquals("aoid", saved.accountObjectId.value)
        assertEquals("session", saved.sessionId.value)
        assertEquals(548, saved.accountId.value)
        assertEquals("Harish", saved.displayName)
        assertEquals(1, accountChanges)
    }

    @Test
    fun `expired code stops polling`() = authTest {
        exchange += tmdbError(401, 33)
        assertEquals(SignInOutcome.CodeExpired, repo.awaitApproval(code))
        assertEquals(1, exchangeCalls)
        assertNull(store.credentials.first())
    }

    @Test
    fun `app key errors stop polling`() = authTest {
        exchange += tmdbError(401, 7)
        assertEquals(SignInOutcome.AppCredentialError, repo.awaitApproval(code))
        exchange.clear()
        exchange += tmdbError(401, 10)
        assertEquals(SignInOutcome.AppCredentialError, repo.awaitApproval(code))
        assertEquals(2, exchangeCalls)
    }

    @Test
    fun `other 4xx is terminal`() = authTest {
        exchange += tmdbError(401, 17)
        assertEquals(SignInOutcome.Rejected, repo.awaitApproval(code))
        exchange.clear()
        exchange += tmdbError(400, null) // unparsable body
        assertEquals(SignInOutcome.Rejected, repo.awaitApproval(code))
        assertEquals(2, exchangeCalls)
        assertEquals(0, accountChanges)
    }

    @Test
    fun `429 waits Retry-After (min 3 s) and keeps polling`() = authTest {
        exchange += listOf(tmdbError(429, null, retryAfter = 7), tmdbError(429, null, retryAfter = 1), approved)
        assertTrue(repo.awaitApproval(code) is SignInOutcome.SignedIn)
        assertEquals(listOf(0L, 7_000L, 10_000L), exchangeTimes)
    }

    @Test
    fun `network and 5xx failures retry on the next tick`() = authTest {
        exchange += listOf({ throw IOException("offline") }, tmdbError(503, null), approved)
        assertTrue(repo.awaitApproval(code) is SignInOutcome.SignedIn)
        assertEquals(listOf(0L, 3_000L, 6_000L), exchangeTimes)
    }

    @Test
    fun `deadline after 2 minutes of pending`() = authTest {
        exchange += tmdbError(422, 41)
        assertEquals(SignInOutcome.TimedOut, repo.awaitApproval(code))
        assertEquals(120_000L, currentTime)
        assertEquals(40, exchangeCalls) // t = 0, 3, …, 117 s
        assertNull(store.credentials.first())
    }

    @Test
    fun `cancellation stops polling and releases the single-flight lock`() = authTest {
        exchange += tmdbError(422, 41)
        val job = launch { repo.awaitApproval(code) }
        advanceTimeBy(10_000)
        job.cancel()
        runCurrent()
        val callsAtCancel = exchangeCalls
        advanceTimeBy(30_000)
        assertEquals(callsAtCancel, exchangeCalls)
        exchange.clear()
        exchange += approved
        assertTrue(repo.awaitApproval(code) is SignInOutcome.SignedIn)
    }

    @Test
    fun `single flight - a second poll while one runs is ignored`() = authTest {
        exchange += tmdbError(422, 41)
        val first = async { repo.awaitApproval(code) }
        runCurrent()
        assertEquals(SignInOutcome.AlreadyPolling, repo.awaitApproval(code))
        first.cancel()
    }

    @Test
    fun `convert failure retries that step only`() = authTest {
        exchange += approved
        convert += listOf({ throw IOException("offline") }, { SessionDto("session") })
        assertTrue(repo.awaitApproval(code) is SignInOutcome.SignedIn)
        assertEquals(1, exchangeCalls)
    }

    @Test
    fun `convert keeps failing - nothing persisted`() = authTest {
        exchange += approved
        convert += { throw IOException("offline") }
        val outcome = repo.awaitApproval(code)
        assertEquals(SignInOutcome.Failed(TmdbError.Network), outcome)
        assertEquals(1, exchangeCalls)
        assertEquals(0, accountCalls)
        assertNull(store.credentials.first())
        assertEquals(0, accountChanges)
    }

    @Test
    fun `sign-out is local first, remote bounded and failures ignored`() = authTest {
        exchange += approved
        repo.awaitApproval(code)
        remoteSignOut = { awaitCancellation() } // TMDB never answers
        repo.signOut()
        assertNull(store.credentials.first())
        assertEquals(2, accountChanges)
        assertEquals(setOf("deleteAccessToken", "deleteSession"), remoteCalls.toSet())
        assertEquals(5_000L, currentTime)

        repo.signOut() // already signed out: no remote calls
        assertEquals(2, remoteCalls.size)
    }

    @Test
    fun `remote sign-out errors do not resurrect the session`() = authTest {
        exchange += approved
        repo.awaitApproval(code)
        remoteSignOut = { throw IOException("offline") }
        repo.signOut()
        assertNull(store.credentials.first())
        assertFalse(remoteCalls.isEmpty())
    }

    @Test
    fun `request code renders the approval url and never its token in toString`() = authTest {
        val requested = repo.createRequestCode()
        assertEquals("https://www.themoviedb.org/auth/access?request_token=request-token", requested.approvalUrl)
        assertFalse(requested.toString().contains("request-token"))
    }
}
