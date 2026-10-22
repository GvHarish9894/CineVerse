package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.util.GenerationCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The LRU / TTL / generation contract of [TmdbCache] on every target (the repository-level cases are in TmdbRepositoryCacheTest). */
class TmdbCacheTest {
    private var now = 0L
    private val generation = GenerationCounter()
    private val cache = TmdbCache(generation, maxEntries = 3, now = { now })

    private fun key(id: Int, account: String? = null) = CacheKey("detail", ids = "movie:$id", accountObjectId = account)

    private fun put(key: CacheKey, value: String, ttl: Long = TmdbCache.DETAIL_TTL) =
        cache.put(key, value, ttl, generation.current, cache.revision)

    @Test
    fun leastRecentlyUsedEntryIsEvicted() {
        (1..3).forEach { put(key(it), "v$it") }
        assertEquals("v1", cache.get<String>(key(1))) // touch 1: 2 is now the eldest
        put(key(4), "v4")
        assertNull(cache.get<String>(key(2)))
        assertEquals(listOf("v1", "v3", "v4"), listOf(1, 3, 4).map { cache.get<String>(key(it)) })
    }

    @Test
    fun rePutRefreshesRecency() {
        (1..3).forEach { put(key(it), "v$it") }
        put(key(1), "v1b")
        put(key(4), "v4")
        assertNull(cache.get<String>(key(2)))
        assertEquals("v1b", cache.get<String>(key(1)))
    }

    @Test
    fun entriesExpireAtTheirTtlAndProcessLifetimeNeverDoes() {
        put(key(1), "list", ttl = TmdbCache.LIST_TTL)
        put(key(2), "config", ttl = TmdbCache.PROCESS_LIFETIME)
        now = TmdbCache.LIST_TTL - 1
        assertEquals("list", cache.get<String>(key(1)))
        now = TmdbCache.LIST_TTL
        assertNull(cache.get<String>(key(1)))
        now = Long.MAX_VALUE - 1
        assertEquals("config", cache.get<String>(key(2)))
    }

    @Test
    fun aResultStartedBeforeAnInvalidationIsNotCommitted() {
        // An in-flight load captures generation + revision, then an invalidation lands before its commit.
        val startedGeneration = generation.current
        val startedRevision = cache.revision
        cache.invalidate(bumpGeneration = false) { false } // e.g. a write elsewhere
        assertFalse(cache.put(key(1), "stale", TmdbCache.DETAIL_TTL, startedGeneration, startedRevision))
        assertNull(cache.get<String>(key(1)))

        val beforeAccountChange = generation.current
        val revision = cache.revision
        cache.invalidate(bumpGeneration = true) { it.accountObjectId != null } // sign-in / sign-out
        assertFalse(cache.put(key(2), "stale", TmdbCache.DETAIL_TTL, beforeAccountChange, revision))
        assertTrue(put(key(2), "fresh")) // a load started after the invalidation commits
        assertEquals("fresh", cache.get<String>(key(2)))
    }

    @Test
    fun invalidateRemovesMatchingEntriesSynchronously() {
        put(key(1), "catalog")
        put(key(2, account = "aoid"), "account")
        val before = generation.current
        cache.invalidate(bumpGeneration = true) { it.accountObjectId != null }
        assertEquals(before + 1, generation.current)
        assertEquals("catalog", cache.get<String>(key(1)))
        assertNull(cache.get<String>(key(2, account = "aoid")))
    }
}
