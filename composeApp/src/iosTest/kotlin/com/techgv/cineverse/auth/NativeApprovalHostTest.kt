package com.techgv.cineverse.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.auth.DataStorePendingAttemptStore
import com.techgv.cineverse.data.auth.NativeApprovalCoordinator
import com.techgv.cineverse.data.auth.NativeApprovalOpener
import com.techgv.cineverse.data.auth.NativeApprovalStatus
import com.techgv.cineverse.data.auth.RequestCode
import com.techgv.cineverse.data.auth.SignInOutcome
import com.techgv.cineverse.data.auth.TokenCipher
import com.techgv.cineverse.data.auth.approvalCallbackUri
import com.techgv.cineverse.data.tmdb.AccountDto
import com.techgv.cineverse.data.tmdb.AccountObjectId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeApprovalHostTest {
    @Test fun backgroundCancelsVerificationRetainsPendingAndAllowsForegroundRetry() = runTest {
        val file = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "scene-pending-${Random.nextLong()}.preferences_pb"
        val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = storageScope) { file }
        // Fixture cipher only: this regression tests lifecycle/persistence, not platform cryptography.
        val cipher = object : TokenCipher {
            override fun encrypt(plain: String) = plain.reversed()
            override fun decrypt(encoded: String) = encoded.reversed()
        }
        val pending = DataStorePendingAttemptStore(dataStore, cipher)
        val signedIn = SignInOutcome.SignedIn(AuthSession(AccountObjectId("fixture"), "Fixture", 1), AccountDto(1))
        var exchanges = 0
        var verificationCancelled = false
        val auth = object : AuthRepository {
            override val session = MutableStateFlow<AuthSession?>(null)
            override val sessionExpired = emptyFlow<Unit>()
            override suspend fun createRequestCode(): RequestCode = error("Use bound native creation")
            override suspend fun awaitApproval(code: RequestCode): SignInOutcome {
                if (++exchanges == 1) {
                    try { awaitCancellation() } finally { verificationCancelled = true }
                }
                session.value = signedIn.session
                return signedIn
            }
            override suspend fun signOut(onSignedOutLocally: () -> Unit): Unit = error("Not used")
            override suspend fun expireSession(failed: AuthSession): Unit = error("Not used")
        }
        var presentationCancels = 0
        val opener = object : NativeApprovalOpener {
            override fun open(url: String, onCallback: (String) -> Unit, onCancel: () -> Unit) = Unit
            override fun cancel() { presentationCancels++ }
        }
        val coordinator = NativeApprovalCoordinator(auth, pending, opener, { RequestCode("fake-request") }, backgroundScope)
        try {
            val attempt = coordinator.begin()
            val waiting = async { coordinator.onCallback(approvalCallbackUri(attempt)) }
            coordinator.status.first { it == NativeApprovalStatus.Checking }
            val beforeBackground = presentationCancels
            handleNativeApprovalScenePhase(isBackground = false, coordinator) // active
            handleNativeApprovalScenePhase(isBackground = false, coordinator) // inactive system presentation
            assertTrue(waiting.isActive)
            assertEquals(beforeBackground, presentationCancels)

            handleNativeApprovalScenePhase(isBackground = true, coordinator)
            waiting.join()
            assertTrue(waiting.isCancelled)
            assertTrue(verificationCancelled, "Background must join the suspended exchange")
            assertEquals(NativeApprovalStatus.Cancelled, coordinator.status.value)
            assertEquals(beforeBackground + 1, presentationCancels)
            // A second store view reuses the one DataStore, and still reads the durable pending tuple.
            assertEquals(attempt.id, DataStorePendingAttemptStore(dataStore, cipher).load()?.id)
            assertTrue(FileSystem.SYSTEM.exists(file))

            handleNativeApprovalScenePhase(isBackground = false, coordinator) // foreground; no close/recreation
            assertEquals(signedIn, coordinator.checkAgain())
            assertEquals(2, exchanges)
            assertEquals(signedIn.session, auth.session.value)
            assertNull(pending.load(), "Successful manual retry consumes the original attempt")
        } finally {
            coordinator.close()
            storageScope.coroutineContext[Job]!!.cancelAndJoin()
            FileSystem.SYSTEM.delete(file, mustExist = false)
        }
    }
}
