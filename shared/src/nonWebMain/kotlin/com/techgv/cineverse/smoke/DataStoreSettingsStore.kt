package com.techgv.cineverse.smoke

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.Path

class DataStoreSettingsStore(private val dataStore: DataStore<Preferences>) : SettingsStore {
    override fun string(key: String): Flow<String?> = dataStore.data.map { it[stringPreferencesKey(key)] }

    override suspend fun setString(key: String, value: String) {
        dataStore.edit { it[stringPreferencesKey(key)] = value }
    }

    override suspend fun remove(key: String) {
        dataStore.edit { it.remove(stringPreferencesKey(key)) }
    }

    companion object {
        /** [file] must end in `.preferences_pb`; one store per file per process. */
        fun create(file: Path, scope: CoroutineScope): DataStoreSettingsStore =
            DataStoreSettingsStore(PreferenceDataStoreFactory.createWithPath(scope = scope, produceFile = { file }))
    }
}
