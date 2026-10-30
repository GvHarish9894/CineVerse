package com.techgv.cineverse.data.search

import com.techgv.cineverse.data.settings.SettingsStore
import com.techgv.cineverse.data.settings.SettingsValues
import com.techgv.cineverse.data.storage.StorageException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal const val RECENT_QUERIES_KEY = "search.recentQueries"
private const val MAX_QUERIES = 10
private const val MAX_QUERY_UNITS = 200

/** Local committed searches only: callers must not record every keystroke. No account or network history. */
class RecentQueryStore(private val settings: SettingsStore) {
    /** Missing data is empty; corrupt data throws [StorageException] and is left intact until [clear]. */
    fun observe(): Flow<List<String>> = settings.data.map(::read).distinctUntilChanged()

    /** Trims input, rejects blank or over-budget input, and atomically moves an exact match to the front. */
    suspend fun record(query: String) {
        val trimmed = query.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= MAX_QUERY_UNITS) { "Invalid recent query" }
        settings.edit { values ->
            val previous = read(values)
            values.setString(RECENT_QUERIES_KEY, Json.encodeToString((listOf(trimmed) + previous.filterNot { it == trimmed }).take(MAX_QUERIES)))
        }
    }

    /** Explicit recovery also removes corrupt data, without reading it or touching other settings. */
    suspend fun clear() {
        settings.edit { it.remove(RECENT_QUERIES_KEY) }
    }

    private fun read(values: SettingsValues): List<String> {
        val stored = values.string(RECENT_QUERIES_KEY) ?: run {
            if (values.boolean(RECENT_QUERIES_KEY) != null || values.stringSet(RECENT_QUERIES_KEY) != null) corrupt()
            return emptyList()
        }
        val array = try {
            Json.parseToJsonElement(stored)
        } catch (_: SerializationException) {
            // Parser diagnostics may contain private input; do not retain them as a cause.
            corrupt()
        } as? JsonArray ?: corrupt()
        if (array.size > MAX_QUERIES) corrupt()
        val queries = array.map { element ->
            val primitive = element as? JsonPrimitive ?: corrupt()
            if (!primitive.isString) corrupt()
            primitive.content.also { query ->
                if (query.isBlank() || query != query.trim() || query.length > MAX_QUERY_UNITS) corrupt()
            }
        }
        if (queries.distinct().size != queries.size) corrupt()
        return queries
    }

    private fun corrupt(): Nothing = throw StorageException("Corrupt recent-query storage")
}
