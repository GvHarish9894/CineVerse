package com.techgv.cineverse.data.search

import com.techgv.cineverse.data.settings.DataStoreSettingsStore
import com.techgv.cineverse.data.storage.StorageException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecentQueryStoreDataStoreTest {
    @Test
    fun recentQueriesSurviveReopeningTheOwnedTemporaryFile() = runTest {
        val file = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "recent-queries-${Random.nextLong()}.preferences_pb"
        val firstJob = Job()
        val secondJob = Job()
        try {
            val firstSettings = DataStoreSettingsStore.create(file, CoroutineScope(firstJob))
            firstSettings.edit { it.setString("language", "hi-IN") }
            val first = RecentQueryStore(firstSettings)
            first.record(" Alien ")
            first.record("Arrival")
            firstJob.cancelAndJoin()

            val reopenedSettings = DataStoreSettingsStore.create(file, CoroutineScope(secondJob))
            val reopened = RecentQueryStore(reopenedSettings)
            assertEquals(listOf("Arrival", "Alien"), reopened.observe().first())
            reopened.clear()
            assertEquals(emptyList(), reopened.observe().first())
            assertEquals("hi-IN", reopenedSettings.data.first().string("language"))
        } finally {
            firstJob.cancelAndJoin()
            secondJob.cancelAndJoin()
            FileSystem.SYSTEM.delete(file)
        }
    }

    @Test
    fun concurrentRepositoriesKeepAllRecordsAndUnrelatedSettings() = runTest {
        val file = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "recent-queries-${Random.nextLong()}.preferences_pb"
        val job = Job()
        try {
            val settings = DataStoreSettingsStore.create(file, CoroutineScope(job))
            settings.edit { it.setString("language", "en-US"); it.setBoolean("haptics", false) }
            (0..9).map { index -> launch { RecentQueryStore(settings).record("q$index") } }.joinAll()
            val result = RecentQueryStore(settings).observe().first()
            assertEquals(10, result.size)
            assertEquals((0..9).map { "q$it" }.toSet(), result.toSet())
            assertEquals("en-US", settings.data.first().string("language"))
            assertEquals(false, settings.data.first().boolean("haptics"))
        } finally {
            job.cancelAndJoin()
            FileSystem.SYSTEM.delete(file)
        }
    }

    @Test
    fun nativeWrongTypeIsPreservedUntilClear() = runTest {
        val file = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "recent-queries-${Random.nextLong()}.preferences_pb"
        val job = Job()
        try {
            val settings = DataStoreSettingsStore.create(file, CoroutineScope(job))
            settings.edit { it.setBoolean(RECENT_QUERIES_KEY, false) }
            val store = RecentQueryStore(settings)
            assertFailsWith<StorageException> { store.observe().first() }
            assertFailsWith<StorageException> { store.record("new") }
            assertEquals(false, settings.data.first().boolean(RECENT_QUERIES_KEY))
            store.clear()
            assertEquals(emptyList(), store.observe().first())
        } finally {
            job.cancelAndJoin()
            FileSystem.SYSTEM.delete(file)
        }
    }
}
