package com.techgv.cineverse.data.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.techgv.cineverse.data.tmdb.AccountId
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.SessionId
import com.techgv.cineverse.data.tmdb.UserAccessToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The credential DataStore with this platform's cipher (Android: Keystore code path, iOS: Keychain, jvm: test cipher). */
class DataStoreCredentialStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cipher = testTokenCipher()
    private val file = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "credentials-${Random.nextLong()}.preferences_pb"
    private val dataStore = PreferenceDataStoreFactory.createWithPath(scope = scope) { file }
    private val store = DataStoreCredentialStore(dataStore, cipher)

    private val credentials = Credentials(
        userAccessToken = UserAccessToken("user-token-SECRET"),
        accountObjectId = AccountObjectId("4bc8892a017a3c0f92000002"),
        sessionId = SessionId("session-SECRET"),
        accountId = AccountId(548),
        username = "harish",
        name = null,
    )

    @AfterTest
    fun tearDown() {
        scope.cancel()
        cipher.loseKey()
        FileSystem.SYSTEM.delete(file)
    }

    @Test
    fun roundTripThroughARealDataStoreFileSecretsEncryptedAtRest() = runBlocking {
        assertNull(store.credentials.first())
        store.save(credentials)
        assertEquals(credentials, store.credentials.first())
        val raw = FileSystem.SYSTEM.read(file) { readByteArray() }.decodeToString()
        assertFalse(raw.contains("user-token-SECRET"))
        assertFalse(raw.contains("session-SECRET"))
        store.clear()
        assertNull(store.credentials.first())
    }

    @Test
    fun keyLostReadsAsSignedOut() = runBlocking {
        store.save(credentials)
        cipher.loseKey()
        assertNull(store.credentials.first())
        cipher.encrypt("x") // a new key cannot decrypt the old ciphertext either
        assertNull(store.credentials.first())
    }

    @Test
    fun saveReplacesTheWholeTuple() = runBlocking {
        store.save(credentials.copy(name = "Old Name"))
        store.save(credentials)
        assertEquals(credentials, store.credentials.first()) // stale optional name is not carried over
    }

    /** Upgrade compatibility: the TV v1 key names and types (written field by field here) read as the same tuple. */
    @Test
    fun aTupleWrittenWithTheV1KeyNamesReads() = runBlocking {
        dataStore.edit { p ->
            p[stringPreferencesKey("user_access_token_enc")] = cipher.encrypt("user-token-SECRET")
            p[stringPreferencesKey("session_id_enc")] = cipher.encrypt("session-SECRET")
            p[stringPreferencesKey("account_object_id")] = "4bc8892a017a3c0f92000002"
            p[intPreferencesKey("account_id")] = 548
            p[stringPreferencesKey("username")] = "harish"
        }
        assertEquals(credentials, store.credentials.first())
        assertEquals("credentials", DataStoreCredentialStore.FILE_NAME)
    }
}
