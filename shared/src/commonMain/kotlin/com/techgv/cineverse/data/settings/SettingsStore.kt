package com.techgv.cineverse.data.settings

import kotlinx.coroutines.flow.Flow

/**
 * Typed key/value settings storage (§2). [data] emits consistent snapshots of every key; [edit] is one atomic
 * read-modify-write of any number of keys. A key keeps one value type (nonWeb: the DataStore preference type, so
 * the TV's existing `settings` file reads unchanged); reading it as another type gives null.
 */
interface SettingsStore {
    val data: Flow<SettingsValues>
    suspend fun edit(transform: (MutableSettingsValues) -> Unit)
}

interface SettingsValues {
    fun string(key: String): String?
    fun boolean(key: String): Boolean?
    fun stringSet(key: String): Set<String>?
}

interface MutableSettingsValues : SettingsValues {
    fun setString(key: String, value: String)
    fun setBoolean(key: String, value: Boolean)
    fun setStringSet(key: String, value: Set<String>)
    fun remove(key: String)
}
