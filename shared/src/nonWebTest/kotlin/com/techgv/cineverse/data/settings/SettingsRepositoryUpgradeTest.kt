package com.techgv.cineverse.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A TV v1 `settings` file (typed DataStore keys written by the old SettingsRepository) reads unchanged. */
class SettingsRepositoryUpgradeTest {
    private val file = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "settings-v1-${Random.nextLong()}.preferences_pb"

    @AfterTest
    fun cleanUp() = FileSystem.SYSTEM.delete(file)

    @Test
    fun v1KeysAndTypesReadIntoTheSameSettings() = runTest {
        val v1Job = Job()
        PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(v1Job)) { file }.edit { p ->
            p[booleanPreferencesKey("haptics")] = false
            p[stringPreferencesKey("strength")] = "Strong"
            p[booleanPreferencesKey("sounds")] = true
            p[booleanPreferencesKey("glow")] = false
            p[booleanPreferencesKey("motion")] = true
            p[stringPreferencesKey("language")] = "hi-IN"
            p[stringPreferencesKey("region")] = "US"
            p[booleanPreferencesKey("include_adult")] = true
            p[stringPreferencesKey("account_defaults_pending")] =
                """{"b":{"accountObjectId":"b","language":"es-ES","region":"GB","includeAdult":false}}"""
            p[stringSetPreferencesKey("account_defaults_resolved")] = setOf("a")
        }
        v1Job.cancelAndJoin()

        val job = Job()
        val repo = SettingsRepository(DataStoreSettingsStore.create(file, CoroutineScope(job)))
        assertEquals(
            Settings(
                haptics = false, strength = HapticStrength.Strong, sounds = true, glow = false, motion = true,
                language = "hi-IN", region = "US", includeAdult = true,
            ),
            repo.current(),
        )
        assertEquals(mapOf("b" to AccountDefaults("b", "es-ES", "GB", includeAdult = false)), repo.pendingAccountDefaults.first())
        repo.offerAccountDefaults(AccountDefaults("a", "ta-IN", "IN", includeAdult = false)) // "a" was resolved in v1
        assertEquals(setOf("b"), repo.pendingAccountDefaults.first().keys)
        job.cancelAndJoin()
    }
}
