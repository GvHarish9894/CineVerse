@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.techgv.cineverse.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.techgv.cineverse.data.auth.ApprovalAttempt
import com.techgv.cineverse.data.auth.DataStorePendingAttemptStore
import com.techgv.cineverse.data.auth.KeychainTokenCipher
import com.techgv.cineverse.data.auth.RequestCode
import com.techgv.cineverse.data.auth.TokenCipher
import com.techgv.cineverse.di.initKoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.koin.mp.KoinPlatform
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIWindow
import platform.posix.getpid

/** Only exported by opt-in AuthFixture, not by the production ComposeApp framework. No network or credentials. */
@Throws(Exception::class)
fun runNativeAuthPersistenceFixture(phase: String): Int = runBlocking {
    initKoin("fixture-unused-not-a-token")
    val cipher = KoinPlatform.getKoin().get<TokenCipher>()
    check(cipher is KeychainTokenCipher) { "Fixture must exercise the production Keychain binding" }
    val directory = checkNotNull(NSFileManager.defaultManager.URLForDirectory(
        NSApplicationSupportDirectory, NSUserDomainMask, null, true, null,
    )?.path).toPath()
    val file = directory / "c1_pending_fixture.preferences_pb"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val data = PreferenceDataStoreFactory.createWithPath(scope = scope) { file }
    val store = DataStorePendingAttemptStore(data, cipher)
    try {
        when (phase) {
            "seed" -> {
                check(store.load() == null) { "Existing fixture must not be overwritten" }
                store.save(ApprovalAttempt("c1-fixture-id", RequestCode("c1-FAKE-request-token")))
                val raw = FileSystem.SYSTEM.read(file) { readByteArray() }.decodeToString()
                check(!raw.contains("c1-FAKE-request-token") && !raw.contains("c1-fixture-id"))
            }
            "reload" -> {
                val restored = checkNotNull(store.load()) { "Pending fixture/key did not survive process restart" }
                check(restored.id == "c1-fixture-id" && restored.code.requestToken == "c1-FAKE-request-token")
            }
            "cleanup" -> store.clear()
            else -> error("Unknown fixture phase")
        }
    } finally {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        if (phase == "cleanup") FileSystem.SYSTEM.delete(file, mustExist = false)
    }
    getpid()
}

fun nativeAuthFixtureHostMarker(): String = "Native auth persistence fixture"

/** Hosted XCTest supplies scene-owned windows; this check never opens approval or accesses persistence. */
@Throws(Exception::class)
fun verifyNativeApprovalWindowReplacement(old: UIWindow, current: UIWindow) {
    check(old != current) { "The replacement must be a different window" }
    val opener = IosWebAuthenticationSessionOpener()
    opener.attach(old)
    opener.attach(current)
    check(!opener.detach(old)) { "The old host must not detach the replacement" }
    check(opener.detach(current)) { "The current host must detach its window" }
    check(!opener.detach(current)) { "A detached host must not detach again" }
}
