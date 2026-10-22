package com.techgv.cineverse.data.auth

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The production [CredentialCipher] with a software key standing in for the Android Keystore (host JVM). */
actual fun testTokenCipher(): LosableTokenCipher {
    val keys = object : CredentialKeys {
        var key: SecretKey? = null
        override fun existing() = key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }
    val cipher = CredentialCipher(keys)
    return object : LosableTokenCipher, TokenCipher by cipher {
        override fun loseKey() {
            keys.key = null
        }
    }
}
