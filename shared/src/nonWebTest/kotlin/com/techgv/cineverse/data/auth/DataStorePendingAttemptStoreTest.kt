package com.techgv.cineverse.data.auth

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DataStorePendingAttemptStoreTest {
    @Test fun encryptedTupleSurvivesFileCloseReloadAndReplacement() = runBlocking {
        val file = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pending-${Random.nextLong()}.preferences_pb"
        val cipher = testTokenCipher()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun open() = DataStorePendingAttemptStore(PreferenceDataStoreFactory.createWithPath(scope = scope) { file }, cipher)
        try {
            val attempt = ApprovalAttempt.of(RequestCode("pending-REQUEST-SECRET"))
            val store = open()
            store.save(attempt)
            val raw = FileSystem.SYSTEM.read(file) { readByteArray() }.decodeToString()
            assertFalse(raw.contains(attempt.code.requestToken))
            assertFalse(raw.contains(attempt.id), "the id and token are one encrypted bound tuple")
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val reloaded = open()
            assertEquals(attempt.id, reloaded.load()?.id)
            assertEquals(attempt.code.requestToken, reloaded.load()?.code?.requestToken)
            val next = ApprovalAttempt.of(RequestCode("second-SECRET"))
            reloaded.save(next)
            assertEquals(next.id, reloaded.load()?.id)
            cipher.loseKey()
            assertNull(reloaded.load())
            cipher.encrypt("new key")
            assertNull(reloaded.load())
            reloaded.clear()
            assertNull(reloaded.load())
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            cipher.loseKey()
            FileSystem.SYSTEM.delete(file, mustExist = false)
        }
    }

    @Test fun corruptCiphertextOrMalformedTupleNeverBecomesPending() = runBlocking {
        val preferences = InMemoryPreferencesStore()
        val cipher = testTokenCipher()
        val store = DataStorePendingAttemptStore(preferences, cipher)
        try {
            for (value in listOf("not ciphertext", cipher.encrypt("{not json"),
                cipher.encrypt("{\"id\":\"a&attempt=b\",\"token\":\"secret\"}"),
                cipher.encrypt("{\"id\":\"valid\",\"token\":\"\"}"))) {
                preferences.edit { it[stringPreferencesKey("attempt_enc")] = value }
                assertNull(store.load())
            }
        } finally { cipher.loseKey() }
    }

    @Test fun corruptPendingFileRecoversWithoutTouchingCredentialsOrSettings() = runBlocking {
        val file = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pending-corrupt-${Random.nextLong()}.preferences_pb"
        FileSystem.SYSTEM.write(file) { writeUtf8("not a preferences protobuf") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cipher = testTokenCipher()
        try {
            val data = PreferenceDataStoreFactory.createWithPath(
                scope = scope, corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            ) { file }
            val store = DataStorePendingAttemptStore(data, cipher)
            assertNull(store.load())
            val attempt = ApprovalAttempt.of(RequestCode("fresh"))
            store.save(attempt)
            assertEquals(attempt.id, store.load()?.id)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            cipher.loseKey()
            FileSystem.SYSTEM.delete(file, mustExist = false)
        }
    }
}
