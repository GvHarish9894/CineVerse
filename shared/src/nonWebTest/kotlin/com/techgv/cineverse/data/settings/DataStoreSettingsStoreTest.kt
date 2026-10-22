package com.techgv.cineverse.data.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun valueSurvivesANewStoreOnTheSameFile() = runTest {
        val firstJob = Job()
        DataStoreSettingsStore.create(file, CoroutineScope(firstJob)).edit { it.setString("region", "IN") }
        firstJob.cancelAndJoin()

        val secondJob = Job()
        assertEquals("IN", DataStoreSettingsStore.create(file, CoroutineScope(secondJob)).data.first().string("region"))
        secondJob.cancelAndJoin()
    }
}
