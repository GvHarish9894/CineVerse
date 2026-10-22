package com.techgv.cineverse.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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

/** AES/GCM; the 12-byte IV is prepended to the ciphertext, Base64 for DataStore. Format unchanged since TV v1. */
class CredentialCipher(private val keys: CredentialKeys) : TokenCipher {
    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.existing() ?: keys.create())
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()))
    }

    /** Null when the key is gone or does not match: the caller treats that as signed out. */
    override fun decrypt(encoded: String): String? = runCatching {
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
