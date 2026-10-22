package com.techgv.cineverse.data.auth

import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.AccessTokenBody
import com.techgv.cineverse.data.tmdb.AccessTokenDto
import com.techgv.cineverse.data.tmdb.AccountDto
import com.techgv.cineverse.data.tmdb.ConvertSessionBody
import com.techgv.cineverse.data.tmdb.SessionDto
import com.techgv.cineverse.data.tmdb.AccountId
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.AccountStatesDto
import com.techgv.cineverse.data.tmdb.SessionId
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.data.tmdb.UserAccessToken
import com.techgv.cineverse.data.tmdb.unusedTmdbApi
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** §3.3 "Sign-in expired": the TmdbRepository account-call boundary → AuthRepository.expireSession. */
class SessionExpiryTest {
    private val api = object : TmdbApi by unusedTmdbApi {
        override suspend fun accountStates(media: MediaType, id: Int, sessionId: String): AccountStatesDto {
            val body = """{"success":false,"status_code":3}""".toResponseBody() // session denied
            val raw = okhttp3.Response.Builder()
                .request(Request.Builder().url("https://api.themoviedb.org/3/movie/$id/account_states").build())
                .protocol(Protocol.HTTP_1_1).code(401).message("error").build()
            throw HttpException(Response.error<Any>(body, raw))
        }
        override suspend fun createAccessToken(body: AccessTokenBody) = AccessTokenDto(accessToken = "u-s2", accountId = "aoid")
        override suspend fun convertToSession(body: ConvertSessionBody) = SessionDto("s2")
        override suspend fun account(sessionId: String) = AccountDto(id = 1, username = "user")
    }
    private var accountChanges = 0
    private val store = DataStoreCredentialStore(InMemoryPreferencesStore(), CredentialCipher(SoftwareKeys()))
    private val auth = AuthRepository(api, store, onAccountChanged = { accountChanges++ })
    private val tmdb = TmdbRepository(
        api, GenerationCounter(), settings = { Settings() }, onSessionExpired = { auth.expireSession(it) },
    )

    private fun credentials(session: String) = Credentials(
        UserAccessToken("u-$session"), AccountObjectId("aoid"), SessionId(session), AccountId(1), "user", null,
    )

    private suspend fun failingAccountCall(account: Credentials) {
        val error = runCatching { tmdb.accountStates(MediaType.Movie, 550, account) }.exceptionOrNull()
        assertEquals(TmdbError.SessionExpired, (error as TmdbException).error)
    }

    private fun TestScope.collectExpiries(): MutableList<Unit> {
        val events = mutableListOf<Unit>()
        backgroundScope.launch { auth.sessionExpired.toList(events) }
        runCurrent()
        return events
    }

    @Test
    fun `invalid-session responses trigger exactly one local sign-out`() = runTest {
        val current = credentials("s1")
        store.save(current)
        val events = collectExpiries()
        // Two sections fail concurrently, then a late third one.
        listOf(async { failingAccountCall(current) }, async { failingAccountCall(current) }).awaitAll()
        failingAccountCall(current)
        runCurrent()
        assertNull(store.credentials.first())
        assertEquals(1, accountChanges)
        assertEquals(1, events.size)
    }

    @Test
    fun `buffered expiry consumed after a newer sign-in is dropped - no navigation to Login`() = runTest {
        val expired = credentials("s1")
        store.save(expired)
        failingAccountCall(expired) // nobody collects yet: the event stays buffered
        assertTrue(auth.awaitApproval(RequestCode("request-token")) is SignInOutcome.SignedIn)
        val events = collectExpiries() // AppNav's collector starts only now
        assertTrue(events.isEmpty())
        assertEquals("s2", store.credentials.first()!!.sessionId.value)
    }

    @Test
    fun `buffered expiry with no newer transition is still delivered`() = runTest {
        val expired = credentials("s1")
        store.save(expired)
        failingAccountCall(expired)
        assertEquals(1, collectExpiries().size)
    }

    @Test
    fun `stale-account failure does not sign out the current account`() = runTest {
        val previous = credentials("old")
        val current = credentials("new") // same account signed in again: new session
        store.save(current)
        val events = collectExpiries()
        failingAccountCall(previous)
        runCurrent()
        assertEquals(current, store.credentials.first())
        assertEquals(0, accountChanges)
        assertTrue(events.isEmpty())
    }
}
