package com.techgv.cineverse.data.settings

import kotlinx.browser.localStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// DataStore 1.3.0's wasm storage is sessionStorage-only (b/441511612), so web settings use localStorage directly:
// one item per key, the value as JSON (string / boolean / string array) so types round-trip.
// shortcut: same-tab change notification only, no cross-tab `storage` events; upgrade with the web shell (P9).
class BrowserSettingsStore(private val keyPrefix: String = "cineverse.") : SettingsStore {
    private val writes = MutableStateFlow(0)

    override val data: Flow<SettingsValues> = writes.map { JsonValues(read()) }.distinctUntilChanged()

    // Single-threaded: nothing runs between the read and the writes, so the edit is atomic within this tab.
    override suspend fun edit(transform: (MutableSettingsValues) -> Unit) {
        val before = read()
        val values = MutableJsonValues(before.toMutableMap())
        transform(values)
        (before.keys - values.map.keys).forEach { localStorage.removeItem(keyPrefix + it) }
        values.map.forEach { (key, value) -> if (before[key] != value) localStorage.setItem(keyPrefix + key, value.toString()) }
        writes.update { it + 1 }
    }

    private fun read(): Map<String, JsonElement> = buildMap {
        for (i in 0 until localStorage.length) {
            val item = localStorage.key(i) ?: continue
            if (!item.startsWith(keyPrefix)) continue
            val value = localStorage.getItem(item)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: continue
            put(item.removePrefix(keyPrefix), value)
        }
    }

    private open class JsonValues(val map: Map<String, JsonElement>) : SettingsValues {
        override fun string(key: String) = (map[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        override fun boolean(key: String) = (map[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        override fun stringSet(key: String) = (map[key] as? JsonArray)
            ?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }?.toSet()
        override fun equals(other: Any?) = other is JsonValues && other.map == map
        override fun hashCode() = map.hashCode()
    }

    private class MutableJsonValues(private val mutable: MutableMap<String, JsonElement>) : JsonValues(mutable), MutableSettingsValues {
        override fun setString(key: String, value: String) { mutable[key] = JsonPrimitive(value) }
        override fun setBoolean(key: String, value: Boolean) { mutable[key] = JsonPrimitive(value) }
        override fun setStringSet(key: String, value: Set<String>) { mutable[key] = JsonArray(value.map(::JsonPrimitive)) }
        override fun remove(key: String) { mutable.remove(key) }
    }
}
