package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.util.GenerationCounter

/** §3.4 cache key. [ids] identifies the subject (e.g. "movie:550", a query); [accountObjectId] marks account data. */
data class CacheKey(
    val endpoint: String,
    val ids: String = "",
    val language: String? = null,
    val region: String? = null,
    val timezone: String? = null,
    val includeAdult: Boolean? = null,
    val page: Int? = null,
    val accountObjectId: String? = null,
)

/**
 * In-memory LRU + TTL cache. Commits carry the app generation they started under; a commit from an
 * older generation (auth / settings changed meanwhile) is dropped. Bump + purge happen under the same
 * lock as commits, so no stale commit can land after an invalidation.
 */
class TmdbCache(
    private val appGeneration: GenerationCounter,
    private val maxEntries: Int = 256,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val value: Any, val expiresAt: Long)

    private val entries = object : LinkedHashMap<CacheKey, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Entry>) = size > maxEntries
    }

    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: CacheKey): T? {
        val entry = entries[key] ?: return null
        if (now() >= entry.expiresAt) {
            entries.remove(key)
            return null
        }
        return entry.value as T
    }

    /** Returns false (and stores nothing) when [generation] is no longer current. */
    @Synchronized
    fun put(key: CacheKey, value: Any, ttlMillis: Long, generation: Long): Boolean {
        if (!appGeneration.isCurrent(generation)) return false
        val expiresAt = if (ttlMillis == Long.MAX_VALUE) Long.MAX_VALUE else now() + ttlMillis
        entries[key] = Entry(value, expiresAt)
        return true
    }

    /** Removes matching entries; with [bumpGeneration] also invalidates every in-flight commit. */
    @Synchronized
    fun invalidate(bumpGeneration: Boolean, predicate: (CacheKey) -> Boolean) {
        if (bumpGeneration) appGeneration.bump()
        entries.keys.removeAll(predicate)
    }

    companion object {
        const val LIST_TTL = 10 * 60 * 1000L
        const val DETAIL_TTL = 60 * 60 * 1000L
        const val PROCESS_LIFETIME = Long.MAX_VALUE
    }
}
