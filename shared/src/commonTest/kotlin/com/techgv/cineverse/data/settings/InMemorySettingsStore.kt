package com.techgv.cineverse.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Synchronous in-memory [SettingsStore] (keeps coroutine tests on virtual time). */
class InMemorySettingsStore : SettingsStore {
    private val state = MutableStateFlow(emptyMap<String, Any>())

    override val data: Flow<SettingsValues> = state.map(::MapValues)

    override suspend fun edit(transform: (MutableSettingsValues) -> Unit) {
        val values = MapValues(state.value.toMutableMap())
        transform(values)
        state.value = values.map.toMap()
    }

    private class MapValues(val map: Map<String, Any>) : MutableSettingsValues {
        private val mutable get() = map as MutableMap<String, Any>
        override fun string(key: String) = map[key] as? String
        override fun boolean(key: String) = map[key] as? Boolean
        @Suppress("UNCHECKED_CAST")
        override fun stringSet(key: String) = map[key] as? Set<String>
        override fun setString(key: String, value: String) { mutable[key] = value }
        override fun setBoolean(key: String, value: Boolean) { mutable[key] = value }
        override fun setStringSet(key: String, value: Set<String>) { mutable[key] = value }
        override fun remove(key: String) { mutable.remove(key) }
    }
}
