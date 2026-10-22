package com.techgv.cineverse.data.auth

/**
 * Encrypts credential secrets at rest with a key held by the platform (Android: Keystore AES, [CredentialCipher];
 * iOS: Keychain, `KeychainTokenCipher`). [decrypt] returns null when the key is gone or does not match: the
 * credential store then reads as signed out.
 */
interface TokenCipher {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String?
}
