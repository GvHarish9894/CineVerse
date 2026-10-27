package com.techgv.cineverse.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.IOException as DataStoreIOException
import com.techgv.cineverse.data.storage.StorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import okio.IOException
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class DataStoreSettingsStoreTest : SettingsStoreContractTest() {
    private val file = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "settings-smoke-${Random.nextLong()}.preferences_pb"
    private val contractScope = CoroutineScope(Job())

    override fun newStore(): SettingsStore = DataStoreSettingsStore.create(file, contractScope)

    @AfterTest
    fun cleanUp() {
        contractScope.cancel()
        FileSystem.SYSTEM.delete(file)
    }

    @Test
    fun persistenceReadFailuresAreTypedAndSanitizedWithoutDefaults() = runTest {
        persistenceFailures().forEach { failure ->
            val native = NativeSettingsBoundaryDataStore().apply { readFailure = failure }
            val store = DataStoreSettingsStore(native)
            val error = assertFailsWith<StorageException> { store.data.first() }
            assertEquals("Settings storage unavailable", error.message)
            assertNull(error.cause)
            assertEquals(0, native.edits)
        }
    }

    @Test
    fun persistenceEditFailuresAreTypedWithoutRetryOrPartialCommit() = runTest {
        persistenceFailures().forEach { failure ->
            val native = NativeSettingsBoundaryDataStore().apply { writeFailure = failure }
            val store = DataStoreSettingsStore(native)
            var transforms = 0
            val error = assertFailsWith<StorageException> {
                store.edit { transforms++; it.setString("owned-fixture", "not committed") }
            }
            assertEquals("Settings storage unavailable", error.message)
            assertNull(error.cause)
            assertEquals(1, transforms)
            assertEquals(1, native.edits)
            assertNull(store.data.first().string("owned-fixture"))
        }
    }

    @Test
    fun readAndEditCancellationAndProgrammerFailuresPropagateUnchanged() = runTest {
        listOf(CancellationException("cancelled"), IllegalStateException("programmer"), AssertionError("assertion")).forEach { failure ->
            val native = NativeSettingsBoundaryDataStore().apply { readFailure = failure }
            val store = DataStoreSettingsStore(native)
            assertSame(failure, assertFailsWith<Throwable> { store.data.first() })
            native.readFailure = null
            native.writeFailure = failure
            assertSame(failure, assertFailsWith<Throwable> { store.edit { it.setBoolean("owned-fixture", true) } })
            assertEquals(1, native.edits)
            assertNull(store.data.first().boolean("owned-fixture"))
        }
    }

    @Test
    fun transformThrownFailuresIncludingIoAreNeverTranslatedOrCommitted() = runTest {
        (persistenceFailures() + listOf(CancellationException("cancelled"), IllegalArgumentException("programmer"), AssertionError("assertion"))).forEach { failure ->
            val native = NativeSettingsBoundaryDataStore()
            val store = DataStoreSettingsStore(native)
            assertSame(failure, assertFailsWith<Throwable> {
                store.edit { it.setString("owned-fixture", "not committed"); throw failure }
            })
            assertEquals(1, native.edits)
            assertNull(store.data.first().string("owned-fixture"))
        }
    }

    @Test
    fun downstreamCollectorFailuresIncludingIoAreNeverTranslated() = runTest {
        (persistenceFailures() + IllegalStateException("collector")).forEach { failure ->
            val store = DataStoreSettingsStore(NativeSettingsBoundaryDataStore())
            assertSame(failure, assertFailsWith<Throwable> { store.data.collect { throw failure } })
        }
    }

    @Test
    fun corruptOwnedFileRemainsByteForByteUnchangedAfterReadAndEditFailure() = runTest {
        val bytes = byteArrayOf(0x0f, 0x7f, 0x01)
        FileSystem.SYSTEM.write(file) { write(bytes) }
        val store = newStore()
        assertFailsWith<StorageException> { store.data.first() }
        assertFailsWith<StorageException> { store.edit { it.setString("region", "IN") } }
        assertEquals(bytes.toList(), FileSystem.SYSTEM.read(file) { readByteArray() }.toList())
    }

    private fun persistenceFailures() = listOf(
        DataStoreIOException("owned-fixture/path secret", IllegalStateException("sensitive cause")),
        IOException("owned-fixture/path secret"),
        CorruptionException("owned-fixture/path secret", IllegalArgumentException("sensitive bytes")),
    )

    @Test
    fun valueSurvivesANewStoreOnTheSameFile() = runTest {
        val firstJob = Job()
        DataStoreSettingsStore.create(file, CoroutineScope(firstJob)).edit { it.setString("region", "IN") }
        firstJob.cancelAndJoin()

        val secondJob = Job()
        assertEquals("IN", DataStoreSettingsStore.create(file, CoroutineScope(secondJob)).data.first().string("region"))
        secondJob.cancelAndJoin()
    }
}
