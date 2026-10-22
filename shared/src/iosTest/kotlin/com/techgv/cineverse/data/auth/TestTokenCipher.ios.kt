package com.techgv.cineverse.data.auth

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.kCFBooleanFalse
import platform.Security.SecKeyRef
import platform.Security.kSecAttrIsPermanent

/**
 * The production [KeychainTokenCipher] (real ECIES path) with an in-memory P-256 key: Kotlin/Native test binaries run
 * outside an app on the simulator and get errSecNotAvailable (-25291) from the Keychain itself.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun testTokenCipher(): LosableTokenCipher {
    val keys = object : EcKeys {
        var key: SecKeyRef? = null
        override fun existing(): SecKeyRef? = key?.let { CFRetain(it) as SecKeyRef }
        override fun create(): SecKeyRef {
            key?.let(::CFRelease)
            val created = cf { createEcKey(dictionary(kSecAttrIsPermanent to kCFBooleanFalse)) }
            key = created
            return CFRetain(created) as SecKeyRef
        }
    }
    val cipher = KeychainTokenCipher(keys)
    return object : LosableTokenCipher, TokenCipher by cipher {
        override fun loseKey() {
            keys.key?.let(::CFRelease)
            keys.key = null
        }
    }
}
