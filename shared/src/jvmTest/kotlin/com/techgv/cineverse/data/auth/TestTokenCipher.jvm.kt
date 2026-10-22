package com.techgv.cineverse.data.auth

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** jvm test cipher: AES/GCM with an in-memory key (the jvm target has no platform key store). */
actual fun testTokenCipher(): LosableTokenCipher = object : LosableTokenCipher {
    private var key: SecretKey? = null

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it })
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()))
    }

    override fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(encoded)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key ?: return null, GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()

    override fun loseKey() {
        key = null
    }
}
