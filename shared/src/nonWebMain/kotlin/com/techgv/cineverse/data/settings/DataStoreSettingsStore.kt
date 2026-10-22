package com.techgv.cineverse.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.Path

/** [SettingsStore] over a Preferences DataStore; keys are DataStore keys of the matching type. */
class DataStoreSettingsStore(private val dataStore: DataStore<Preferences>) : SettingsStore {
    override val data: Flow<SettingsValues> = dataStore.data.map(::PreferencesValues)

    override suspend fun edit(transform: (MutableSettingsValues) -> Unit) {
        dataStore.edit { transform(MutablePreferencesValues(it)) }
    }

    private open class PreferencesValues(private val p: Preferences) : SettingsValues {
        // A key stored with another type reads as null (DataStore throws ClassCastException on the typed get).
        override fun string(key: String) = p.asMap()[stringPreferencesKey(key)] as? String
        override fun boolean(key: String) = p.asMap()[booleanPreferencesKey(key)] as? Boolean
        @Suppress("UNCHECKED_CAST")
        override fun stringSet(key: String) = p.asMap()[stringSetPreferencesKey(key)] as? Set<String>
    }

    private class MutablePreferencesValues(private val p: MutablePreferences) : PreferencesValues(p), MutableSettingsValues {
        override fun setString(key: String, value: String) { p[stringPreferencesKey(key)] = value }
        override fun setBoolean(key: String, value: Boolean) { p[booleanPreferencesKey(key)] = value }
        override fun setStringSet(key: String, value: Set<String>) { p[stringSetPreferencesKey(key)] = value }
        override fun remove(key: String) { p.remove(stringPreferencesKey(key)) } // keys compare by name only
    }

    companion object {
        /** [file] must end in `.preferences_pb`; one store per file per process. */
        fun create(file: Path, scope: CoroutineScope): DataStoreSettingsStore =
            DataStoreSettingsStore(PreferenceDataStoreFactory.createWithPath(scope = scope, produceFile = { file }))
    }
}
