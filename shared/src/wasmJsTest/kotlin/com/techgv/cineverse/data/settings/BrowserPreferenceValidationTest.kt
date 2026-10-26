@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.data.settings

import com.techgv.cineverse.data.search.RECENT_QUERIES_KEY
import com.techgv.cineverse.data.search.RecentQueryStore
import com.techgv.cineverse.data.storage.*
import kotlinx.browser.localStorage
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.js.Promise
import kotlin.test.*

/** Real IndexedDB transactions and owned legacy keys, not permissive repository/UI fakes. */
class BrowserPreferenceValidationTest {
    private class Fixture {
        val name = "cineverse.g5.preferences." + preferenceId()
        val prefix = "$name.legacy."
        val database = BrowserDatabase(name)
        val peer = BrowserDatabase(name)
        val changes = BrowserChanges("$name.bus")
        val store = BrowserSettingsStore(prefix, database, changes)
        val strict = store.preferenceStore
        val repository = SettingsRepository(strict)
        val recents = RecentQueryStore(store)
        suspend fun write(raw: String) = peer.edit("settings", prefix) { raw }
        suspend fun raw() = database.read("settings", prefix)
        suspend fun close() {
            store.close() // Borrowed resources must remain usable until their owner closes them.
            database.close(); peer.close(); changes.close()
            deletePreferenceDatabase(name).await()
            (0 until localStorage.length).mapNotNull { localStorage.key(it) }
                .filter { it.startsWith(prefix) || it == "__${name}.bus.revision" }
                .forEach(localStorage::removeItem)
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { f.close() }
    }

    private val valid = mapOf(
        "haptics" to JsonPrimitive(false), "strength" to JsonPrimitive("Strong"),
        "sounds" to JsonPrimitive(true), "glow" to JsonPrimitive(false), "motion" to JsonPrimitive(true),
        "language" to JsonPrimitive("ta-IN"), "region" to JsonPrimitive("GB"), "include_adult" to JsonPrimitive(true),
    )
    private val booleans = setOf("haptics", "sounds", "glow", "motion", "include_adult")
    private fun invalidPreferences(): List<Pair<String, JsonElement>> = valid.keys.flatMap { key ->
        val wrongTypes = listOf("42", "null", "[]", "[\"private-payload\"]", "[false]", "[1]", "[\"x\",1]", "{}") +
            if (key in booleans) listOf("\"false\"", "\"true\"") else listOf("false", "true")
        val strings = if (key in booleans) emptyList() else listOf(
            "", "private-payload", valid.getValue(key).jsonPrimitive.content.lowercase(),
            " ${valid.getValue(key).jsonPrimitive.content}", "${valid.getValue(key).jsonPrimitive.content} ",
        ).filter { it != valid.getValue(key).jsonPrimitive.content }.map { JsonPrimitive(it) }
        wrongTypes.map { key to Json.parseToJsonElement(it) } + strings.map { key to it }
    }

    private suspend fun corrupt(block: suspend () -> Unit) {
        val failure = assertFailsWith<BrowserStorageException> { block() }
        assertEquals(BrowserStorageFailure.Corrupt, failure.failure)
        assertEquals("Browser storage: Corrupt", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressedExceptions.isEmpty())
    }

    @Test fun missingAndEverySupportedPreferenceReadWithoutNormalization() = runTest { fixture { f ->
        assertSame(f.strict, f.store.preferenceStore)
        assertNotSame(f.store, f.strict)
        assertEquals(Settings(), f.repository.current())
        val allValid = Settings(false, HapticStrength.Strong, true, false, true, "ta-IN", "GB", true)
        val defaults = Settings()
        for (key in valid.keys) {
            f.write(Json.encodeToString(valid - key))
            val expected = when (key) {
                "haptics" -> allValid.copy(haptics = defaults.haptics)
                "strength" -> allValid.copy(strength = defaults.strength)
                "sounds" -> allValid.copy(sounds = defaults.sounds)
                "glow" -> allValid.copy(glow = defaults.glow)
                "motion" -> allValid.copy(motion = defaults.motion)
                "language" -> allValid.copy(language = defaults.language)
                "region" -> allValid.copy(region = defaults.region)
                else -> allValid.copy(includeAdult = defaults.includeAdult)
            }
            assertEquals(expected, f.repository.current())
        }
        f.write(Json.encodeToString(valid))
        assertEquals(allValid, f.repository.current())
        for (value in listOf(false, true)) {
            f.strict.edit { p -> booleans.forEach { p.setBoolean(it, value) } }
            val s = f.repository.current()
            assertEquals(listOf(value, value, value, value, value), listOf(s.haptics, s.sounds, s.glow, s.motion, s.includeAdult))
        }
        for (strength in HapticStrength.entries) {
            f.repository.update { it.copy(strength = strength) }
            assertEquals(strength, f.repository.current().strength)
        }
        for (language in Settings.LANGUAGES) {
            f.repository.update { it.copy(language = language) }
            assertEquals(language, f.repository.current().language)
        }
        for (region in Settings.REGIONS) {
            f.repository.update { it.copy(region = region) }
            assertEquals(region, f.repository.current().region)
        }
        f.store.close()
        assertEquals(Settings.REGIONS.last(), f.repository.current().region)
    } }

    @Test fun eachInvalidTypeAndDomainFailsReadsAndInputBeforeTransformWithoutPublication() = runTest { fixture { f ->
        for ((key, value) in invalidPreferences()) {
            val raw = " { \"$key\" : $value, \"unknown\" : \"private-payload\" } "
            f.write(raw)
            val revision = f.changes.changes.value
            corrupt { f.strict.data.first() }
            corrupt { f.repository.current() }
            var transformed = false
            corrupt { f.repository.update { transformed = true; it.copy(language = "hi-IN") } }
            corrupt { f.strict.edit { transformed = true; it.remove(key) } }
            assertFalse(transformed, "$key = $value")
            assertEquals(raw, f.raw())
            assertEquals(revision, f.changes.changes.value)
        }
    } }

    @Test fun invalidOutputAndCallerThrowAbortOriginalBytesAndNeverPublish() = runTest { fixture { f ->
        val raw = " { \"language\" : \"en-US\", \"unknown\" : \"kept\" } "
        f.write(raw)
        val revision = f.changes.changes.value
        for (key in valid.keys) {
            corrupt { f.strict.edit { it.setString(key, "private-payload") } }
            assertEquals(raw, f.raw())
            assertEquals(revision, f.changes.changes.value)
        }
        corrupt { f.repository.update { it.copy(language = "en", region = "us") } }
        assertFailsWith<IllegalStateException> {
            f.strict.edit { it.setString("language", "ta-IN"); it.remove("unknown"); error("caller abort") }
        }
        assertEquals(raw, f.raw())
        assertEquals(revision, f.changes.changes.value)
        f.repository.offerAccountDefaults(AccountDefaults("owned", "unsupported", "US", false))
        val offered = f.raw()
        val offeredRevision = f.changes.changes.value
        corrupt { f.repository.resolveAccountDefaults("owned", apply = true) }
        assertEquals(offered, f.raw())
        assertEquals(offeredRevision, f.changes.changes.value)
    } }

    @Test fun editValidatesTransactionCurrentInputAfterValidReadAndQueuedPeerWrite() = runTest { fixture { f ->
        f.write("{}")
        assertEquals(Settings(), f.repository.current())
        val bad = "{\"motion\":{\"private-payload\":1}}"
        var transformed = false
        f.write(bad) // Separate connection commits after the valid read.
        corrupt { f.repository.update { transformed = true; it.copy(language = "hi-IN") } }
        assertFalse(transformed)
        f.write("{}")
        assertEquals(Settings(), f.repository.current())
        val hook = beforePreferenceWrite(f.name, f.prefix, bad) {}
        val revision = f.changes.changes.value
        try {
            corrupt { f.repository.update { transformed = true; it.copy(language = "hi-IN") } }
            assertTrue(preferenceHookUsed(hook))
        } finally { restorePreferenceHook(hook) }
        assertFalse(transformed)
        assertEquals(bad, f.raw())
        assertEquals(revision, f.changes.changes.value)
    } }

    @Test fun concurrentStrictRepositoryUpdatesKeepBothCommittedFields() = runTest { fixture { f ->
        f.write("{}")
        val second = SettingsRepository(BrowserSettingsStore(f.prefix, f.peer, f.changes).preferenceStore)
        val first = async { f.repository.update { it.copy(language = "ml-IN") } }
        val other = async { second.update { it.copy(region = "US") } }
        first.await(); other.await()
        assertEquals(Settings(language = "ml-IN", region = "US"), f.repository.current())
        assertEquals(2L, f.changes.changes.value.settings)
    } }

    @Test fun realRecentsObserveRecordClearPreserveEveryBadPreferenceAndBookkeeping() = runTest { fixture { f ->
        val unrelated = mapOf(
            "unknown" to JsonPrimitive("{not parsed"), "flag" to JsonPrimitive(true),
            "account_defaults_pending" to JsonPrimitive("{not parsed either"),
            "account_defaults_resolved" to JsonArray(listOf(JsonPrimitive("owned"))),
        )
        for ((key, value) in invalidPreferences()) {
            f.write(Json.encodeToString(unrelated + (key to value) + (RECENT_QUERIES_KEY to JsonPrimitive("[\"kept\"]"))))
            assertEquals(listOf("kept"), f.recents.observe().first())
            val values = f.store.data.first()
            assertEquals((value as? JsonPrimitive)?.takeIf { it.isString }?.content, values.string(key))
            assertEquals((value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull, values.boolean(key))
            val stringSet = (value as? JsonArray)?.takeIf { a -> a.all { it is JsonPrimitive && it.isString } }
                ?.map { it.jsonPrimitive.content }?.toSet()
            assertEquals(stringSet, values.stringSet(key))
            f.recents.record("new")
            assertEquals(listOf("new", "kept"), f.recents.observe().first())
            f.recents.clear()
            assertEquals(emptyList(), f.recents.observe().first())
            assertEquals(unrelated + (key to value), Json.decodeFromString<Map<String, JsonElement>>(f.raw()!!))
            corrupt { f.repository.current() }
        }
    } }

    @Test fun explicitClearRepairsOnlyBadRecentJsonEvenWithBadPreference() = runTest { fixture { f ->
        val preserved = mapOf("motion" to JsonNull, "account_defaults_pending" to JsonPrimitive("{private-payload"))
        f.write(Json.encodeToString(preserved + (RECENT_QUERIES_KEY to JsonPrimitive("[private-payload"))))
        val before = f.raw()
        assertFailsWith<StorageException> { f.recents.observe().first() }
        assertFailsWith<StorageException> { f.recents.record("new") }
        assertEquals(before, f.raw())
        f.recents.clear()
        assertEquals(emptyList(), f.recents.observe().first())
        assertEquals(preserved, Json.decodeFromString<Map<String, JsonElement>>(f.raw()!!))
    } }

    @Test fun unknownStructuralCorruptionAndWholeOrLegacySyntaxStillRejectWithoutReset() = runTest { fixture { f ->
        for (key in listOf("unknown", "account_defaults_pending", RECENT_QUERIES_KEY)) {
            val raw = "{\"$key\":null}"
            f.write(raw)
            corrupt { f.store.data.first() }
            corrupt { f.recents.clear() }
            assertEquals(raw, f.raw())
        }
        rawPreferenceWrite(f.name, f.prefix, "{private-payload").await()
        corrupt { f.repository.current() }
        corrupt { f.recents.clear() }
        assertEquals("{private-payload", f.raw())
        f.peer.edit("settings", f.prefix) { null }
        localStorage.setItem(f.prefix + "motion", "{private-payload")
        corrupt { f.repository.current() }
        corrupt { f.recents.clear() }
        assertNull(f.raw())
        assertEquals("{private-payload", localStorage.getItem(f.prefix + "motion"))
    } }

    @Test fun strictLegacyValidationPrecedesWritesGenericMigrationPreservesAndLegacyIsRetained() = runTest { fixture { f ->
        localStorage.setItem(f.prefix + "motion", "null")
        val hook = beforePreferenceWrite(f.name, f.prefix, null) {}
        try {
            corrupt { f.repository.current() }
            assertFalse(preferenceHookUsed(hook), "invalid legacy must not even open a migration write")
        } finally { restorePreferenceHook(hook) }
        assertNull(f.raw())
        assertEquals("null", localStorage.getItem(f.prefix + "motion"))
        var transformed = false
        corrupt { f.strict.edit { transformed = true; it.remove("motion") } }
        assertFalse(transformed)
        assertNull(f.raw())
        assertEquals(0L, f.changes.changes.value.settings)
        f.recents.record("owned") // Generic migration/edit retains the invalid element.
        assertEquals(JsonNull, Json.parseToJsonElement(f.raw()!!).jsonObject["motion"])
        assertEquals("null", localStorage.getItem(f.prefix + "motion"))
        corrupt { f.repository.current() }
    } }

    @Test fun strictMigrationRechecksTransactionCurrentStoredCandidateAndLegacyCandidate() = runTest { fixture { f ->
        localStorage.setItem(f.prefix + "language", "\"ta-IN\"")
        val bad = "{\"strength\":\"private-payload\"}"
        var hook = beforePreferenceWrite(f.name, f.prefix, bad) {}
        try {
            corrupt { f.repository.current() }
            assertTrue(preferenceHookUsed(hook))
        } finally { restorePreferenceHook(hook) }
        assertEquals(bad, f.raw())
        assertEquals("\"ta-IN\"", localStorage.getItem(f.prefix + "language"))
        f.peer.edit("settings", f.prefix) { null }
        hook = beforePreferenceWrite(f.name, f.prefix, null) {
            localStorage.setItem(f.prefix + "language", "false")
        }
        try { corrupt { f.repository.current() }; assertTrue(preferenceHookUsed(hook)) }
        finally { restorePreferenceHook(hook) }
        assertNull(f.raw())
        assertEquals("false", localStorage.getItem(f.prefix + "language"))
        assertEquals(0L, f.changes.changes.value.settings)
        localStorage.setItem(f.prefix + "language", "\"ta-IN\"")
        assertEquals(Settings(language = "ta-IN"), f.repository.current())
        assertEquals("\"ta-IN\"", localStorage.getItem(f.prefix + "language"))
    } }

    @Test fun validStrictEditsPreserveUnknownAndUnparsedBookkeeping() = runTest { fixture { f ->
        val unrelated = mapOf(
            "account_defaults_pending" to JsonPrimitive("{not parsed"),
            "account_defaults_resolved" to JsonArray(listOf(JsonPrimitive("owned"))),
            "unknown" to JsonPrimitive("[not parsed"),
            RECENT_QUERIES_KEY to JsonPrimitive("[\"real history\"]"),
        )
        f.write(Json.encodeToString(unrelated))
        f.repository.update { it.copy(language = "hi-IN") }
        val after = Json.decodeFromString<Map<String, JsonElement>>(f.raw()!!)
        unrelated.forEach { (key, value) -> assertEquals(value, after[key]) }
        assertEquals(listOf("real history"), f.recents.observe().first())
        assertEquals("hi-IN", f.repository.current().language)
    } }
}

@JsFun("() => crypto.randomUUID()")
private external fun preferenceId(): String
@JsFun("name => new Promise((resolve,reject) => { const r = indexedDB.deleteDatabase(name); r.onsuccess = () => resolve(null); r.onerror = () => reject(r.error); r.onblocked = () => reject(new Error('owned preference cleanup blocked')); })")
private external fun deletePreferenceDatabase(name: String): Promise<JsAny?>
@JsFun("(name,key,raw) => new Promise((resolve,reject) => { const r = indexedDB.open(name,1); r.onerror = () => reject(r.error); r.onsuccess = () => { const db = r.result, tx = db.transaction('settings','readwrite'); tx.objectStore('settings').put(raw,key); tx.oncomplete = () => { db.close(); resolve(null); }; tx.onabort = () => { db.close(); reject(tx.error); }; }; })")
private external fun rawPreferenceWrite(name: String, key: String, raw: String): Promise<JsAny?>

// Queue a real competing write before the target transaction is created, after any read/legacy preflight.
@JsFun("""(name,key,candidate,before) => {
    const old = IDBDatabase.prototype.transaction, state = {used:false,restore:() => { IDBDatabase.prototype.transaction = old; }};
    IDBDatabase.prototype.transaction = function(...args) {
        if (!state.used && this.name === name && args[0] === 'settings' && args[1] === 'readwrite') {
            state.used = true; before();
            if (candidate !== null) old.call(this,'settings','readwrite').objectStore('settings').put(candidate,key);
        }
        return old.apply(this,args);
    };
    return state;
}""")
private external fun beforePreferenceWrite(name: String, key: String, candidate: String?, before: () -> Unit): JsAny
@JsFun("state => state.used")
private external fun preferenceHookUsed(state: JsAny): Boolean
@JsFun("state => state.restore()")
private external fun restorePreferenceHook(state: JsAny)
