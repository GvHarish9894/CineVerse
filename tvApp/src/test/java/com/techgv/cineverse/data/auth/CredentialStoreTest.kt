package com.techgv.cineverse.data.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.techgv.cineverse.data.tmdb.AccountId
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.SessionId
import com.techgv.cineverse.data.tmdb.UserAccessToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CredentialStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keys = SoftwareKeys()
    private val file by lazy { folder.newFolder().resolve("credentials.preferences_pb") }
    private val store by lazy {
        DataStoreCredentialStore(PreferenceDataStoreFactory.create(scope = scope) { file }, CredentialCipher(keys))
    }

    private val credentials = Credentials(
        userAccessToken = UserAccessToken("user-token-SECRET"),
        accountObjectId = AccountObjectId("4bc8892a017a3c0f92000002"),
        sessionId = SessionId("session-SECRET"),
        accountId = AccountId(548),
        username = "harish",
        name = null,
    )

    @After fun tearDown() = scope.cancel()

    @Test
    fun `round trip through a real DataStore file, secrets encrypted at rest`() = runBlocking {
        assertNull(store.credentials.first())
        store.save(credentials)
        assertEquals(credentials, store.credentials.first())
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("user-token-SECRET"))
        assertFalse(raw.contains("session-SECRET"))
        store.clear()
        assertNull(store.credentials.first())
    }

    @Test
    fun `key lost reads as signed out`() = runBlocking {
        store.save(credentials)
        keys.lose()
        assertNull(store.credentials.first())
        keys.create() // a new key cannot decrypt the old ciphertext either
        assertNull(store.credentials.first())
    }

    @Test
    fun `save replaces the whole tuple`() = runBlocking {
        store.save(credentials.copy(name = "Old Name"))
        store.save(credentials)
        assertEquals(credentials, store.credentials.first()) // stale optional name is not carried over
    }
}
