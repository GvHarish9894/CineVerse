package com.techgv.cineverse.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Fails at the DataStore boundary, not at the SettingsStore contract under test. */
internal class NativeSettingsBoundaryDataStore : DataStore<Preferences> {
    private val snapshots = MutableStateFlow<Pair<Preferences, Throwable?>>(emptyPreferences() to null)
    val values get() = snapshots.value.first
    var readFailure: Throwable?
        get() = snapshots.value.second
        set(value) { snapshots.value = values to value }
    var writeFailure: Throwable? = null
    var edits = 0
        private set
    override val data = snapshots.map { (values, failure) ->
        failure?.let { throw it }
        values
    }
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        edits++
        val next = transform(values)
        writeFailure?.let { throw it }
        snapshots.value = next to null
        return next
    }
}
