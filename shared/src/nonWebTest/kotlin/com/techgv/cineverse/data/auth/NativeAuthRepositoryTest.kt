package com.techgv.cineverse.data.auth

import com.techgv.cineverse.data.tmdb.AccessTokenBody
import com.techgv.cineverse.data.tmdb.AccessTokenDto
import com.techgv.cineverse.data.tmdb.AccountDto
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.ConvertSessionBody
import com.techgv.cineverse.data.tmdb.DeleteAccessTokenBody
import com.techgv.cineverse.data.tmdb.DeleteSessionBody
import com.techgv.cineverse.data.tmdb.RequestTokenBody
import com.techgv.cineverse.data.tmdb.RequestTokenDto
import com.techgv.cineverse.data.tmdb.SessionDto
import com.techgv.cineverse.data.tmdb.StatusDto
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TmdbHttpException
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.UserBearer
import com.techgv.cineverse.data.tmdb.UnusedTmdbApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.io.IOException

class NativeAuthRepositoryTest {
    /** Scripted exchange responses; the last one repeats. */
    private val exchange = ArrayDeque<() -> AccessTokenDto>()
    private var exchangeCalls = 0
    private val exchangeTimes = mutableListOf<Long>()
    private val convert = ArrayDeque<() -> SessionDto>()
    private val convertTimes = mutableListOf<Long>()
    private var convertGate: CompletableDeferred<Unit>? = null
    private var accountCalls = 0
    private var onAccount: () -> Unit = {}
    private var remoteSignOut: suspend () -> StatusDto = { StatusDto(success = true) }
    private val remoteCalls = mutableListOf<String>()
    private var accountChanges = 0
    private lateinit var clock: TestScope

    private val requestBodies = mutableListOf<RequestTokenBody>()
    private val api = object : UnusedTmdbApi() {
        override suspend fun createRequestToken(body: RequestTokenBody): RequestTokenDto {
            requestBodies += body
            return RequestTokenDto("request-token")
        }
        override suspend fun createAccessToken(body: AccessTokenBody): AccessTokenDto {
            exchangeCalls++
            exchangeTimes += clock.currentTime
            return (if (exchange.size > 1) exchange.removeFirst() else exchange.first())()
        }
        override suspend fun convertToSession(body: ConvertSessionBody): SessionDto {
            convertTimes += clock.currentTime
            convertGate?.await()
            return (if (convert.size > 1) convert.removeFirst() else convert.firstOrNull() ?: { SessionDto("session") })()
        }
        override suspend fun account(sessionId: String): AccountDto {
            accountCalls++
            onAccount()
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

    private val store = DataStoreCredentialStore(InMemoryPreferencesStore(), testTokenCipher())
    private val repo = NativeAuthRepository(api, store, onAccountChanged = { accountChanges++ })
    private val code = RequestCode("request-token")
    private val approved = { AccessTokenDto(accessToken = "user-access-token", accountId = "aoid") }

    /** What KtorTmdbApi throws for a non-2xx response (null [statusCode]: an unparsable body). */
    private fun tmdbError(http: Int, statusCode: Int?, retryAfter: Long? = null): () -> Nothing = {
        throw TmdbHttpException(http, statusCode, retryAfter)
    }

    private fun authTest(block: suspend TestScope.() -> Unit) = runTest {
        clock = this
        block()
    }

    @Test
    fun mobileCreationBindsTheExactAttemptRedirectWithoutChangingTv() = authTest {
        assertEquals("request-token", repo.createRequestCode("already-generated-id").requestToken)
        repo.createRequestCode()
        assertEquals(listOf("cineverse://auth?attempt=already-generated-id", "https://www.themoviedb.org/"),
            requestBodies.map { it.redirectTo })
        for (invalid in listOf("", "a&attempt=b", "a#x", "a?x", "a%26x")) {
            kotlin.test.assertFailsWith<IllegalArgumentException> { repo.createRequestCode(invalid) }
        }
        assertEquals(2, requestBodies.size)
    }

    @Test
    fun `pending then approved converts reads account and commits the tuple`() = authTest {
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

    /** Plan §3: common code sees only AuthSession; one sign-in = one session, each newer than the last. */
    @Test
    fun `each sign-in is a new AuthSession and the guest is null`() = authTest {
        exchange += approved
        assertNull(repo.session.first())
        val first = (repo.awaitApproval(code) as SignInOutcome.SignedIn).session
        assertEquals(AuthSession(AccountObjectId("aoid"), "Harish", first.sessionGeneration), first)
        assertEquals(first, repo.session.first())
        val firstCredentials = repo.credentialsFor(first)
        assertEquals(store.credentials.first(), firstCredentials)

        repo.signOut()
        assertNull(repo.session.first())
        convert += { SessionDto("session-2") } // the same account signs in again: a new session
        val second = (repo.awaitApproval(code) as SignInOutcome.SignedIn).session
        assertTrue(second.sessionGeneration > first.sessionGeneration)
        assertTrue(second != first)
        assertEquals(second, repo.session.first())
        // An earlier session keeps its own credentials (its calls fail on TMDB, never switch to the new sign-in).
        assertEquals(firstCredentials, repo.credentialsFor(first))
        assertEquals("session-2", repo.credentialsFor(second).sessionId.value)
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
        assertEquals(SignInOutcome.Rejected(TmdbError.ExchangeRejected(401, 17)), repo.awaitApproval(code))
        exchange.clear()
        exchange += tmdbError(400, null) // unparsable body
        assertEquals(SignInOutcome.Rejected(TmdbError.ExchangeRejected(400, null)), repo.awaitApproval(code))
        assertEquals(2, exchangeCalls)
        assertEquals(0, accountChanges)
    }

    @Test
    fun `429 waits Retry-After min 3 s and keeps polling`() = authTest {
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
    fun `second 429 on a post-approval step waits maxpoll interval Retry-After`() = authTest {
        exchange += approved
        // tmdbCall retries once after Retry-After; the step retry must not undercut the second Retry-After.
        convert += listOf(tmdbError(429, null, retryAfter = 7), tmdbError(429, null, retryAfter = 7), { SessionDto("session") })
        assertTrue(repo.awaitApproval(code) is SignInOutcome.SignedIn)
        assertEquals(listOf(0L, 7_000L, 14_000L), convertTimes)
    }

    @Test
    fun `cancel at the account-to-save boundary commits nothing`() = authTest {
        exchange += approved
        lateinit var job: Job
        onAccount = { job.cancel() } // BACK lands after /3/account answered, before the commit
        job = launch { repo.awaitApproval(code) }
        job.join()
        assertTrue(job.isCancelled)
        assertNull(store.credentials.first())
        assertEquals(0, accountChanges)
        // The single-flight lock was released.
        onAccount = {}
        assertTrue(repo.awaitApproval(code) is SignInOutcome.SignedIn)
    }

    @Test
    fun `sign-out during conversion - nothing saved`() = authTest {
        exchange += approved
        convertGate = CompletableDeferred()
        val attempt = async { repo.awaitApproval(code) }
        runCurrent() // approved; waiting for convert
        repo.signOut() // store still empty: must still invalidate the attempt
        convertGate!!.complete(Unit)
        assertEquals(SignInOutcome.Superseded, attempt.await())
        assertNull(store.credentials.first())
        assertEquals(0, accountChanges)
    }

    @Test
    fun `sign-out is local first remote bounded and failures ignored`() = authTest {
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
