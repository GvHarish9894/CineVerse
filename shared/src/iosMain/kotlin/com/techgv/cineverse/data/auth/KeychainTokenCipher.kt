package com.techgv.cineverse.data.auth

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFErrorGetCode
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateDecryptedData
import platform.Security.SecKeyCreateEncryptedData
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyRef
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecClass
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnRef

/** Where the iOS P-256 key lives. Production: [KeychainKeys]; tests use an ephemeral key. Both return +1 references. */
@OptIn(ExperimentalForeignApi::class)
interface EcKeys {
    /** The existing private key, or null when it was never created or has been lost (Keychain reset, other device). */
    fun existing(): SecKeyRef?
    fun create(): SecKeyRef
}

/** A P-256 private key kept in the Keychain (this device only, after first unlock) under [tag]. */
@OptIn(ExperimentalForeignApi::class)
class KeychainKeys(private val tag: String = "com.techgv.cineverse.credentials") : EcKeys {
    override fun existing(): SecKeyRef? = cf {
        memScoped {
            val result = alloc<CFTypeRefVar>()
            if (SecItemCopyMatching(query(returnRef = true), result.ptr) == errSecSuccess) result.value as SecKeyRef? else null
        }
    }

    override fun create(): SecKeyRef = cf {
        val privateAttrs = dictionary(
            kSecAttrIsPermanent to kCFBooleanTrue,
            kSecAttrApplicationTag to retained(tagData()),
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        )
        createEcKey(privateAttrs)
    }

    /** Removes the key (a lost key). */
    fun delete() {
        cf { SecItemDelete(query(returnRef = false)) }
    }

    private fun CfScope.query(returnRef: Boolean): CFMutableDictionaryRef = dictionary(
        kSecClass to kSecClassKey,
        kSecAttrApplicationTag to retained(tagData()),
        kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
    ).also { if (returnRef) CFDictionaryAddValue(it, kSecReturnRef, kCFBooleanTrue) }

    private fun tagData(): NSData = NSString.create(string = tag).dataUsingEncoding(NSUTF8StringEncoding)!!
}

/** A new P-256 key (+1) with [privateAttrs] (Keychain persistence or none). */
@OptIn(ExperimentalForeignApi::class)
internal fun CfScope.createEcKey(privateAttrs: CFMutableDictionaryRef): SecKeyRef {
    val attrs = dictionary(
        kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
        kSecAttrKeySizeInBits to retained(NSNumber(int = 256)),
        kSecPrivateKeyAttrs to privateAttrs,
    )
    return memScoped {
        val error = alloc<CFErrorRefVar>()
        SecKeyCreateRandomKey(attrs, error.ptr) ?: error("EC key creation failed: OSStatus ${error.value?.let(::CFErrorGetCode)}")
    }
}

/**
 * iOS [TokenCipher]: secrets are ECIES-encrypted (X9.63 SHA-256, AES-GCM) with the public half of a P-256 key from
 * [keys] (the Keychain in production) and stored Base64 in the credential DataStore. Key lost → [decrypt] returns
 * null → signed out, as on Android.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainTokenCipher(private val keys: EcKeys = KeychainKeys()) : TokenCipher {
    override fun encrypt(plain: String): String = cf {
        val privateKey = owned(keys.existing() ?: keys.create())
        val publicKey = owned(SecKeyCopyPublicKey(privateKey) ?: error("Keychain key has no public key"))
        val plainData = NSString.create(string = plain).dataUsingEncoding(NSUTF8StringEncoding) ?: error("UTF-8 encoding failed")
        val encrypted = SecKeyCreateEncryptedData(publicKey, ALGORITHM, retained(plainData) as CFDataRef?, null)
            ?: error("Keychain encryption failed")
        (CFBridgingRelease(encrypted) as NSData).base64EncodedStringWithOptions(0u)
    }

    override fun decrypt(encoded: String): String? = cf {
        val key = owned(keys.existing() ?: return@cf null)
        val data = NSData.create(base64EncodedString = encoded, options = 0u) ?: return@cf null
        val decrypted = SecKeyCreateDecryptedData(key, ALGORITHM, retained(data) as CFDataRef?, null) ?: return@cf null
        NSString.create(data = CFBridgingRelease(decrypted) as NSData, encoding = NSUTF8StringEncoding)?.toString()
    }

    private companion object {
        val ALGORITHM = kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM
    }
}

/** Core Foundation references created in a [cf] block, released when it ends. */
@OptIn(ExperimentalForeignApi::class)
internal class CfScope {
    private val refs = mutableListOf<CFTypeRef>()

    fun <T : CFTypeRef> owned(ref: T): T = ref.also { refs += it }

    fun retained(value: Any): CFTypeRef = owned(CFBridgingRetain(value)!!)

    fun dictionary(vararg entries: Pair<CFTypeRef?, CFTypeRef?>): CFMutableDictionaryRef =
        owned(CFDictionaryCreateMutable(null, entries.size.toLong(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!)
            .also { dict -> entries.forEach { (key, value) -> CFDictionaryAddValue(dict, key, value) } }

    fun release() = refs.asReversed().forEach(::CFRelease)
}

@OptIn(ExperimentalForeignApi::class)
internal inline fun <T> cf(block: CfScope.() -> T): T {
    val scope = CfScope()
    try {
        return scope.block()
    } finally {
        scope.release()
    }
}
