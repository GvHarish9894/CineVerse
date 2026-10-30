package com.techgv.cineverse.data.search

import com.techgv.cineverse.data.settings.InMemorySettingsStore
import com.techgv.cineverse.data.settings.MutableSettingsValues
import com.techgv.cineverse.data.settings.SettingsStore
import com.techgv.cineverse.data.settings.SettingsValues
import com.techgv.cineverse.data.storage.StorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class RecentQueryStoreTest {
    @Test
    fun missingKeyIsEmpty() = runTest {
        assertEquals(emptyList(), RecentQueryStore(InMemorySettingsStore()).observe().first())
    }

    @Test
    fun trimmedExactDuplicatesMoveToFrontAndCaseIsDistinct() = runTest {
        val store = RecentQueryStore(InMemorySettingsStore())
        store.record("  Alien \n")
        store.record("alien")
        store.record("Arrival")
        store.record("\tAlien ")
        assertEquals(listOf("Alien", "Arrival", "alien"), store.observe().first())
    }

    @Test
    fun keepsOnlyTenNewestDistinctQueries() = runTest {
        val store = RecentQueryStore(InMemorySettingsStore())
        repeat(12) { store.record("query-$it") }
        assertEquals((11 downTo 2).map { "query-$it" }, store.observe().first())
        store.record("query-5")
        assertEquals(listOf("query-5") + (11 downTo 2).filterNot { it == 5 }.map { "query-$it" }, store.observe().first())
    }

    @Test
    fun budgetCountsKotlinStringUnitsAfterTrimmingWithoutTruncation() = runTest {
        val store = RecentQueryStore(InMemorySettingsStore())
        val boundary = "\uD83C\uDFAC".repeat(100)
        assertEquals(200, boundary.length)
        store.record(" $boundary ")
        assertFailsWith<IllegalArgumentException> { store.record(boundary + "a") }
        assertEquals(listOf(boundary), store.observe().first())
    }

    @Test
    fun invalidInputNeverEntersEditOrRevealsInput() = runTest {
        var edits = 0
        val settings = object : SettingsStore by InMemorySettingsStore() {
            override suspend fun edit(transform: (MutableSettingsValues) -> Unit) { edits++ }
        }
        val store = RecentQueryStore(settings)
        for (input in listOf("", " \n\t", "private".repeat(30))) {
            assertEquals("Invalid recent query", assertFailsWith<IllegalArgumentException> { store.record(input) }.message)
        }
        assertEquals(0, edits)
    }

    @Test
    fun jsonRoundTripAndNewRepositoryPreserveEscapesAndUnicode() = runTest {
        val settings = InMemorySettingsStore()
        val query = "film \"quoted\" \\ sequel\n日本語 🎬"
        RecentQueryStore(settings).record(query)
        assertEquals(Json.encodeToString(listOf(query)), settings.data.first().string(RECENT_QUERIES_KEY))
        assertEquals(listOf(query), RecentQueryStore(settings).observe().first())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun observesCommittedChangesWithoutUnrelatedSettingsEmissions() = runTest {
        val settings = InMemorySettingsStore()
        val store = RecentQueryStore(settings)
        val observed = mutableListOf<List<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.observe().collect { observed.add(it) } }
        store.record("one")
        settings.edit { it.setBoolean("unrelated", true) }
        store.record("two")
        store.clear()
        assertEquals(listOf(emptyList(), listOf("one"), listOf("two", "one"), emptyList()), observed)
    }

    @Test
    fun malformedShapeAndNoncanonicalValuesRemainUntilExplicitClear() = runTest {
        val corruptValues = listOf(
            "not-json-private", "[", "{}", "null", "\"private\"", "[1]", "[true]", "[null]", "[{}]", "[[]]",
            "[\"\"]", "[\"   \"]", "[\" private\"]", "[\"private\\n\"]", "[\"private\",\"private\"]",
            Json.encodeToString(listOf("x".repeat(201))), Json.encodeToString((1..11).map { "q$it" }),
        )
        for (raw in corruptValues) {
            val settings = InMemorySettingsStore()
            settings.edit { it.setString(RECENT_QUERIES_KEY, raw); it.setString("unrelated", "kept") }
            val store = RecentQueryStore(settings)
            val readFailure = assertFailsWith<StorageException> { store.observe().first() }
            assertEquals("Corrupt recent-query storage", readFailure.message)
            assertNull(readFailure.cause)
            assertFailsWith<StorageException> { store.record("new") }
            assertEquals(raw, settings.data.first().string(RECENT_QUERIES_KEY))
            store.clear()
            assertNull(settings.data.first().string(RECENT_QUERIES_KEY))
            assertEquals("kept", settings.data.first().string("unrelated"))
            store.record("recovered")
            assertEquals(listOf("recovered"), store.observe().first())
        }
    }

    @Test
    fun readableWrongTypesAreNotMissingAndCanBeExplicitlyCleared() = runTest {
        val seeds: List<(MutableSettingsValues) -> Unit> = listOf(
            { it.setBoolean(RECENT_QUERIES_KEY, false) },
            { it.setStringSet(RECENT_QUERIES_KEY, emptySet()) },
        )
        for (seed in seeds) {
            val settings = InMemorySettingsStore()
            settings.edit(seed)
            val before = settings.data.first()
            val store = RecentQueryStore(settings)
            assertFailsWith<StorageException> { store.observe().first() }
            assertFailsWith<StorageException> { store.record("new") }
            assertEquals(before.boolean(RECENT_QUERIES_KEY), settings.data.first().boolean(RECENT_QUERIES_KEY))
            assertEquals(before.stringSet(RECENT_QUERIES_KEY), settings.data.first().stringSet(RECENT_QUERIES_KEY))
            store.clear()
            assertEquals(emptyList(), store.observe().first())
        }
    }

    @Test
    fun readAndEditStorageFailuresPropagateWithoutVolatileSuccess() = runTest {
        val readFailure = StorageException("Read unavailable")
        val readBroken = object : SettingsStore by InMemorySettingsStore() {
            override val data = flow<SettingsValues> { throw readFailure }
            override suspend fun edit(transform: (MutableSettingsValues) -> Unit) { throw readFailure }
        }
        assertSame(readFailure, assertFailsWith<StorageException> { RecentQueryStore(readBroken).observe().first() })
        assertSame(readFailure, assertFailsWith<StorageException> { RecentQueryStore(readBroken).record("new") })

        val underlying = InMemorySettingsStore()
        RecentQueryStore(underlying).record("old")
        val writeFailure = StorageException("Write unavailable")
        val writeBroken = object : SettingsStore by underlying {
            override suspend fun edit(transform: (MutableSettingsValues) -> Unit) {
                // Run the transform against a throwaway snapshot, then simulate a failed commit.
                val staged = InMemorySettingsStore()
                val raw = underlying.data.first().string(RECENT_QUERIES_KEY)!!
                staged.edit { it.setString(RECENT_QUERIES_KEY, raw) }
                staged.edit(transform)
                throw writeFailure
            }
        }
        val store = RecentQueryStore(writeBroken)
        assertSame(writeFailure, assertFailsWith<StorageException> { store.record("new") })
        assertEquals(listOf("old"), store.observe().first())
        assertSame(writeFailure, assertFailsWith<StorageException> { store.clear() })
        assertEquals(listOf("old"), store.observe().first())
    }

    @Test
    fun programmerAndCancellationFailuresAreNotCaught() = runTest {
        for (failure in listOf(IllegalStateException("programmer"), CancellationException("cancelled"))) {
            val settings = object : SettingsStore by InMemorySettingsStore() {
                override val data = flow<SettingsValues> { throw failure }
                override suspend fun edit(transform: (MutableSettingsValues) -> Unit) { throw failure }
            }
            val store = RecentQueryStore(settings)
            assertSame(failure, assertFailsWith<Exception> { store.observe().first() })
            assertSame(failure, assertFailsWith<Exception> { store.record("new") })
            assertSame(failure, assertFailsWith<Exception> { store.clear() })
        }
    }

    @Test
    fun concurrentRecordsReadInsideAtomicEditAndPreserveOtherKeys() = runTest {
        val underlying = InMemorySettingsStore()
        underlying.edit { it.setString("language", "hi-IN"); it.setBoolean("haptics", false); it.setStringSet("resolved", setOf("a")) }
        val settings = object : SettingsStore by underlying {
            override val data get() = error("record must read the edit snapshot, not the observation flow")
            override suspend fun edit(transform: (MutableSettingsValues) -> Unit) {
                yield()
                underlying.edit(transform)
            }
        }
        repeat(10) { index -> launch { RecentQueryStore(settings).record("q$index") } }
        testScheduler.runCurrent()
        assertEquals((9 downTo 0).map { "q$it" }, RecentQueryStore(underlying).observe().first())
        val after = underlying.data.first()
        assertEquals("hi-IN", after.string("language"))
        assertEquals(false, after.boolean("haptics"))
        assertEquals(setOf("a"), after.stringSet("resolved"))
    }
}
