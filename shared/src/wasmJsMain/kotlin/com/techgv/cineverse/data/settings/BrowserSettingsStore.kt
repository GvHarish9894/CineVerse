@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.data.settings

import com.techgv.cineverse.data.storage.BrowserChangeTopic
import com.techgv.cineverse.data.storage.BrowserChanges
import com.techgv.cineverse.data.storage.BrowserDatabase
import com.techgv.cineverse.data.storage.BrowserStorageException
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.storage.decodeStorage
import com.techgv.cineverse.data.storage.storageFailure
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Atomic settings blob; the legacy per-key localStorage snapshot is copied on first access and never deleted. */
class BrowserSettingsStore(
    private val keyPrefix: String = "cineverse.",
    database: BrowserDatabase? = null,
    changes: BrowserChanges? = null,
) : SettingsStore {
    private val ownsDatabase = database == null
    private val ownsChanges = changes == null
    private val database = database ?: BrowserDatabase()
    private val changes = changes ?: BrowserChanges()

    /** Closes only default-owned resources; injected database/bus belong to the composition root. */
    fun close() {
        if (ownsDatabase) database.close()
        if (ownsChanges) changes.close()
    }
    override val data: Flow<SettingsValues> = this.changes.changes
        .map { it.settings to it.revalidate }.distinctUntilChanged()
        .map { JsonValues(snapshot()) }.distinctUntilChanged()

    override suspend fun edit(transform: (MutableSettingsValues) -> Unit) {
        database.edit("settings", keyPrefix) { stored ->
            val values = MutableJsonValues(read(stored).toMutableMap())
            transform(values)
            Json.encodeToString(values.map)
        }
        changes.publish(BrowserChangeTopic.Settings)
    }

    private suspend fun snapshot(): Map<String, JsonElement> {
        database.read("settings", keyPrefix)?.let { return read(it) }
        // Recheck inside the write transaction: another tab may already have migrated/edited.
        database.edit("settings", keyPrefix) { stored -> Json.encodeToString(read(stored)) }
        return read(database.read("settings", keyPrefix)!!)
    }

    private fun read(stored: String?): Map<String, JsonElement> {
        val values: Map<String, JsonElement> = if (stored != null) decodeStorage(stored) else {
            val legacy = decodeStorage<LegacySnapshot>(legacySnapshot(keyPrefix))
            legacy.error?.let { throw storageFailure(it) }
            decodeStorage(legacy.value!!)
        }
        if (values.values.any { !valid(it) }) throw BrowserStorageException(BrowserStorageFailure.Corrupt)
        return values
    }

    @Serializable
    private data class LegacySnapshot(val value: String? = null, val error: String? = null)

    private fun valid(value: JsonElement): Boolean = when (value) {
        is JsonPrimitive -> value.isString || value.booleanOrNull != null
        is JsonArray -> value.all { it is JsonPrimitive && it.isString }
        else -> false
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

@JsFun("""prefix => {
    try {
        const values = Object.create(null);
        for (let i = 0; i < localStorage.length; i++) {
            const key = localStorage.key(i);
            if (key?.startsWith(prefix)) {
                const raw = localStorage.getItem(key);
                if (raw !== null) values[key.slice(prefix.length)] = JSON.parse(raw);
            }
        }
        return JSON.stringify({value:JSON.stringify(values)});
    } catch (error) { return JSON.stringify({error:error instanceof SyntaxError ? 'Corrupt' : error.name}); }
}""")
private external fun legacySnapshot(prefix: String): String
