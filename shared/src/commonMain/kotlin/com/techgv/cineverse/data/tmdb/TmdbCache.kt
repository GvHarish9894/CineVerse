package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.util.GenerationCounter
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock

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
 * In-memory LRU + TTL cache. Commits carry the app generation and the cache [revision] they started
 * under; a commit from an older generation (auth / settings changed meanwhile) or from before any
 * invalidation (e.g. a write) is dropped. Bump + purge happen under the same lock as commits, so no
 * stale commit can land after an invalidation.
 *
 * Threading contract: every member is synchronous and runs under one [SynchronizedObject] lock (reentrant on
 * JVM / Android / native; a no-op on wasm, which runs single-threaded). Callers read a cached snapshot
 * synchronously ([get], e.g. `imageBaseUrl()`) and invalidate synchronously ([invalidate], e.g. `onAccountChanged()`
 * inside the auth transition): when the call returns, the generation is bumped and the entries are gone. Loads
 * are async and happen outside the lock; only their commit ([put]) takes it.
 */
class TmdbCache(
    private val appGeneration: GenerationCounter,
    private val maxEntries: Int = 256,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private class Entry(val value: Any, val expiresAt: Long)

    private val lock = SynchronizedObject()

    /** Iteration order = recency: [get] and [put] move a key to the end, eviction drops the first. */
    private val entries = LinkedHashMap<CacheKey, Entry>()

    /** Bumped by every [invalidate]. */
    private var invalidations = 0L

    // shortcut: one revision for all resources, so a write also drops unrelated in-flight commits (a later
    // cache miss, never stale data); upgrade to per-resource revisions if writes become frequent.
    val revision: Long get() = synchronized(lock) { invalidations }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: CacheKey): T? = synchronized(lock) {
        val entry = entries.remove(key) ?: return null
        if (now() >= entry.expiresAt) return null
        entries[key] = entry
        entry.value as T
    }

    /** Returns false (and stores nothing) when [generation] is no longer current or [revision] changed. */
    fun put(key: CacheKey, value: Any, ttlMillis: Long, generation: Long, revision: Long): Boolean = synchronized(lock) {
        if (!appGeneration.isCurrent(generation) || revision != invalidations) return false
        val expiresAt = if (ttlMillis == Long.MAX_VALUE) Long.MAX_VALUE else now() + ttlMillis
        entries.remove(key)
        entries[key] = Entry(value, expiresAt)
        if (entries.size > maxEntries) entries.remove(entries.keys.first())
        true
    }

    /** Removes matching entries; with [bumpGeneration] also invalidates every in-flight commit. */
    fun invalidate(bumpGeneration: Boolean, predicate: (CacheKey) -> Boolean): Unit = synchronized(lock) {
        if (bumpGeneration) appGeneration.bump()
        invalidations++
        entries.keys.removeAll(predicate)
    }

    companion object {
        const val LIST_TTL = 10 * 60 * 1000L
        const val DETAIL_TTL = 60 * 60 * 1000L
        const val PROCESS_LIFETIME = Long.MAX_VALUE
    }
}
