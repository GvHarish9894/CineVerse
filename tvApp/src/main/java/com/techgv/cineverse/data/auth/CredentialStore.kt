package com.techgv.cineverse.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.techgv.cineverse.data.tmdb.AccountId
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.SessionId
import com.techgv.cineverse.data.tmdb.UserAccessToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The signed-in tuple of §4.1 step 5; persisted only as a whole. */
data class Credentials(
    val userAccessToken: UserAccessToken,
    val accountObjectId: AccountObjectId,
    val sessionId: SessionId,
    val accountId: AccountId,
    val username: String,
    val name: String?,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: username
}

/** Where the AES key lives. Production: [AndroidKeystoreKeys]; tests use a software key. */
interface CredentialKeys {
    /** The existing key, or null when it was never created or has been lost (e.g. restored backup, reset). */
    fun existing(): SecretKey?
    fun create(): SecretKey
}

/** Non-exportable AES-256 key in the Android Keystore. */
class AndroidKeystoreKeys(private val alias: String = "cineverse_credentials") : CredentialKeys {
    private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    override fun existing(): SecretKey? = keyStore.getKey(alias, null) as? SecretKey

    override fun create(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }
}

/** AES/GCM; the 12-byte IV is prepended to the ciphertext, Base64 for DataStore. */
class CredentialCipher(private val keys: CredentialKeys) {
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.existing() ?: keys.create())
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()))
    }

    /** Null when the key is gone or does not match: the caller treats that as signed out. */
    fun decrypt(encoded: String): String? = runCatching {
        val key = keys.existing() ?: return null
        val bytes = Base64.getDecoder().decode(encoded)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }.getOrNull()

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/**
 * Credential DataStore (file `credentials`, excluded from backup). The tuple is written in one
 * `edit` (atomic); an incomplete tuple or an undecryptable secret (key lost) reads as signed out.
 */
class CredentialStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: CredentialCipher,
) {
    private object Keys {
        val userAccessToken = stringPreferencesKey("user_access_token_enc")
        val sessionId = stringPreferencesKey("session_id_enc")
        val accountObjectId = stringPreferencesKey("account_object_id")
        val accountId = intPreferencesKey("account_id")
        val username = stringPreferencesKey("username")
        val name = stringPreferencesKey("name")
    }

    val credentials: Flow<Credentials?> = dataStore.data.map { p ->
        val token = p[Keys.userAccessToken]?.let(cipher::decrypt) ?: return@map null
        val session = p[Keys.sessionId]?.let(cipher::decrypt) ?: return@map null
        Credentials(
            userAccessToken = UserAccessToken(token),
            accountObjectId = AccountObjectId(p[Keys.accountObjectId] ?: return@map null),
            sessionId = SessionId(session),
            accountId = AccountId(p[Keys.accountId] ?: return@map null),
            username = p[Keys.username] ?: return@map null,
            name = p[Keys.name],
        )
    }

    suspend fun save(credentials: Credentials) {
        val token = cipher.encrypt(credentials.userAccessToken.value)
        val session = cipher.encrypt(credentials.sessionId.value)
        dataStore.edit { p ->
            p.clear()
            p[Keys.userAccessToken] = token
            p[Keys.sessionId] = session
            p[Keys.accountObjectId] = credentials.accountObjectId.value
            p[Keys.accountId] = credentials.accountId.value
            p[Keys.username] = credentials.username
            credentials.name?.let { p[Keys.name] = it }
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    companion object {
        /** DataStore name; the file is `files/datastore/credentials.preferences_pb` (see backup rules). */
        const val FILE_NAME = "credentials"
    }
}
