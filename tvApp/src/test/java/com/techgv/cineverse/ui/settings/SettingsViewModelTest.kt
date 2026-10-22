package com.techgv.cineverse.ui.settings

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.CredentialCipher
import com.techgv.cineverse.data.auth.DataStoreCredentialStore
import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.auth.GatedPreferencesStore
import com.techgv.cineverse.data.auth.InMemoryPreferencesStore
import com.techgv.cineverse.data.auth.SoftwareKeys
import com.techgv.cineverse.data.settings.AccountDefaults
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.settings.DataStoreSettingsStore
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.AccountId
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.DeleteAccessTokenBody
import com.techgv.cineverse.data.tmdb.DeleteSessionBody
import com.techgv.cineverse.data.tmdb.SessionId
import com.techgv.cineverse.data.tmdb.StatusDto
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.UserAccessToken
import com.techgv.cineverse.data.tmdb.UserBearer
import com.techgv.cineverse.data.tmdb.unusedTmdbApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val remoteRevocation = CompletableDeferred<StatusDto>()
    private var deleteSessionCalls = 0

    private val api = object : TmdbApi by unusedTmdbApi {
        override suspend fun deleteAccessToken(bearer: UserBearer, body: DeleteAccessTokenBody) = remoteRevocation.await()
        override suspend fun deleteSession(body: DeleteSessionBody): StatusDto {
            deleteSessionCalls++
            return remoteRevocation.await()
        }
    }

    private val credentialData = GatedPreferencesStore()
    private val credentialStore = DataStoreCredentialStore(credentialData, CredentialCipher(SoftwareKeys()))
    private val settings = SettingsRepository(DataStoreSettingsStore(InMemoryPreferencesStore()))
    private val vm by lazy { SettingsViewModel(SavedStateHandle(), settings, AuthRepository(api, credentialStore, onAccountChanged = {})) }

    private fun account(id: String, name: String) =
        Credentials(UserAccessToken("t-$id"), AccountObjectId(id), SessionId("s-$id"), AccountId(id.hashCode()), name.lowercase(), name)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an unresolved offer returns with its account, and a stale click never resolves another account's offer`() = runTest(dispatcher) {
        backgroundScope.launch { vm.state.collect {} }
        val a = AccountDefaults("a", "hi-IN", "US", includeAdult = false)
        val b = AccountDefaults("b", "es-ES", "GB", includeAdult = false)
        credentialStore.save(account("a", "Ann"))
        settings.offerAccountDefaults(a)
        runCurrent()
        assertEquals(a, vm.state.value?.accountDefaults) // shown to A; showing resolves nothing

        credentialStore.save(account("b", "Bob"))
        settings.offerAccountDefaults(b)
        runCurrent()
        assertEquals(b, vm.state.value?.accountDefaults)

        vm.resolveAccountDefaults(a, apply = true) // stale click from A's row while B is signed in
        runCurrent()
        assertEquals(b, vm.state.value?.accountDefaults)
        assertEquals(Settings(), settings.current())

        credentialStore.save(account("a", "Ann"))
        runCurrent()
        assertEquals(a, vm.state.value?.accountDefaults)
    }

    @Test
    fun `signed out is reported only after the local clear, once, while the remote revocation still runs`() = runTest(dispatcher) {
        credentialStore.save(account("a", "Ann"))
        val results = mutableListOf<Pair<Boolean, Credentials?>>()
        backgroundScope.launch { vm.signOutResults.collect { results += it to credentialStore.credentials.first() } }
        val localClear = CompletableDeferred<Unit>().also { credentialData.gate = it }

        vm.signOut()
        vm.signOut() // duplicate press while in flight
        runCurrent()
        assertEquals(emptyList<Pair<Boolean, Credentials?>>(), results) // local clear still pending: no toast yet

        localClear.complete(Unit)
        runCurrent()
        assertEquals(listOf(true to null), results) // credentials were already gone when the toast fired
        assertEquals(1, deleteSessionCalls) // remote half running, once

        remoteRevocation.complete(StatusDto(success = true))
        runCurrent()
        assertEquals(listOf(true to null), results)
    }

    @Test
    fun `a failed local clear reports failure and allows another try`() = runTest(dispatcher) {
        credentialStore.save(account("a", "Ann"))
        val results = mutableListOf<Boolean>()
        backgroundScope.launch { vm.signOutResults.collect { results += it } }
        credentialData.failWrites = true

        vm.signOut()
        runCurrent()
        assertEquals(listOf(false), results)
        assertEquals("a", credentialStore.credentials.first()?.accountObjectId?.value)

        credentialData.failWrites = false
        vm.signOut()
        runCurrent()
        assertEquals(listOf(false, true), results)
        assertNull(credentialStore.credentials.first())
        remoteRevocation.complete(StatusDto(success = true))
    }
}
