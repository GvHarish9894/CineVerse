@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.data.storage

import com.techgv.cineverse.data.library.BrowserGuestLibraryStore
import com.techgv.cineverse.data.library.BrowserImportStore
import com.techgv.cineverse.data.library.GuestLibraryRow
import com.techgv.cineverse.data.library.ImportOfferRecord
import com.techgv.cineverse.data.library.ImportStepState
import com.techgv.cineverse.data.settings.BrowserSettingsStore
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import kotlinx.browser.localStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserStorageTest {
    private val movie = MediaRef(MediaType.Movie, 7)
    private val tv = MediaRef(MediaType.Tv, 7)
    private val rating = LibraryKind.RATING

    private class Fixture {
        val name = "cineverse.test." + uniqueId()
        val prefix = "$name.legacy."
        val channel = "$name.changes"
        val database = BrowserDatabase(name)
        val changes = BrowserChanges(channel)
        val settings = BrowserSettingsStore(prefix, database, changes)
        val guest = BrowserGuestLibraryStore(database, changes)
        val imports = BrowserImportStore(database)
        suspend fun close() {
            database.close(); changes.close()
            deleteDatabase(name).await()
            val keys = (0 until localStorage.length).mapNotNull { localStorage.key(it) }
            keys.filter { it.startsWith(prefix) || it == "__$channel.revision" }.forEach(localStorage::removeItem)
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }

    @Test
    fun committedSnapshotsSurviveCloseAndReopen() = runTest { fixture { f ->
        f.settings.edit { it.setString("language", "hi-IN"); it.setBoolean("motion", false) }
        val row = GuestLibraryRow(movie, rating, 8.5, Long.MAX_VALUE, "{\"title\":\"A\"}")
        f.guest.upsert(row)
        f.imports.put(ImportOfferRecord("account", true, false))
        f.database.close()
        val reopened = BrowserDatabase(f.name)
        try {
            assertEquals(row, BrowserGuestLibraryStore(reopened, f.changes).all().single())
            assertEquals("hi-IN", BrowserSettingsStore(f.prefix, reopened, f.changes).data.first().string("language"))
            assertEquals(ImportOfferRecord("account", true, false), BrowserImportStore(reopened).offer("account"))
        } finally { reopened.close() }
    } }

    @Test
    fun attemptingIsCommittedBeforeSimulatedPostAndSurvivesReconnect() = runTest { fixture { f ->
        f.imports.put("account", movie, rating, ImportStepState.Attempting)
        // A fresh connection must see Attempting BEFORE the simulated network side effect.
        val reconnected = BrowserDatabase(f.name)
        var posted = false
        try {
            assertEquals(ImportStepState.Attempting, BrowserImportStore(reconnected).state("account", movie, rating))
            posted = true
        } finally { reconnected.close() }
        assertTrue(posted)
        f.database.close()
        val afterCrash = BrowserDatabase(f.name)
        try { assertEquals(ImportStepState.Attempting, BrowserImportStore(afterCrash).state("account", movie, rating)) }
        finally { afterCrash.close() }
    } }

    @Test
    fun settingsMigrationRetainsLegacyAndAtomicEditsPreserveConcurrentKeys() = runTest { fixture { f ->
        localStorage.setItem(f.prefix + "language", "\"ta-IN\"")
        localStorage.setItem(f.prefix + "motion", "false")
        assertEquals("ta-IN", f.settings.data.first().string("language"))
        assertEquals("\"ta-IN\"", localStorage.getItem(f.prefix + "language"))
        val secondDb = BrowserDatabase(f.name)
        val second = BrowserSettingsStore(f.prefix, secondDb, f.changes)
        try {
            f.settings.edit { it.setString("counter", "0") }
            val peer = createRealm()
            val peerWriter = realmIncrement(peer, f.name, f.prefix)
            val writers = (1..20).map { i -> async {
                (if (i % 2 == 0) f.settings else second).edit {
                    val count = it.string("counter")!!.toInt() + 1
                    it.setString("counter", count.toString()); it.setString("copy", count.toString())
                    it.setString("writer$i", "kept")
                }
            } }
            try { writers.forEach { it.await() }; peerWriter.await() } finally { removeRealm(peer) }
            val values = second.data.first()
            assertEquals("40", values.string("counter")); assertEquals("40", values.string("copy"))
            assertEquals("ta-IN", values.string("language")); assertEquals(false, values.boolean("motion"))
            (1..20).forEach { assertEquals("kept", values.string("writer$it")) }
            assertFailsWith<IllegalStateException> {
                f.settings.edit { it.setString("counter", "broken"); it.remove("language"); error("abort") }
            }
            assertEquals("40", second.data.first().string("counter"))
            assertEquals("ta-IN", second.data.first().string("language"))
        } finally { secondDb.close() }
    } }

    @Test
    fun guestCompositeUpsertDeleteAndNewestFirstAreIsolated() = runTest { fixture { f ->
        val rows = listOf(
            GuestLibraryRow(movie, rating, 4.5, 1, "movie rating"),
            GuestLibraryRow(tv, rating, 9.0, 2, "tv rating"),
            GuestLibraryRow(movie, LibraryKind.WATCHLIST, null, 3, "movie watchlist"),
        )
        rows.forEach { f.guest.upsert(it) }
        assertEquals(rows.reversed(), f.guest.all())
        f.guest.upsert(rows[0].copy(value = 8.5, addedAt = 4))
        assertEquals(3, f.guest.all().size)
        assertEquals(8.5, f.guest.all().first().value)
        assertEquals(2, f.guest.observeTitle(movie).first().size)
        assertEquals(listOf(rows[1]), f.guest.observeKind(rating, MediaType.Tv).first())
        f.guest.delete(movie, rating)
        assertEquals(listOf(rows[2], rows[1]), f.guest.all())
    } }

    @Test
    fun ledgerAndOffersPreserveAccountRefAndKindIsolation() = runTest { fixture { f ->
        f.imports.put("a:movie", movie, rating, ImportStepState.Attempting)
        f.imports.put("a", movie, rating, ImportStepState.Done)
        f.imports.put("a", tv, rating, ImportStepState.Attempting)
        f.imports.put("a", movie, LibraryKind.FAVOURITE, ImportStepState.Attempting)
        f.imports.put(ImportOfferRecord("a", true, false)); f.imports.put(ImportOfferRecord("b", false, true))
        assertEquals(ImportStepState.Attempting, f.imports.state("a:movie", movie, rating))
        assertEquals(ImportStepState.Done, f.imports.state("a", movie, rating))
        assertEquals(ImportStepState.Attempting, f.imports.state("a", tv, rating))
        assertEquals(ImportStepState.Attempting, f.imports.state("a", movie, LibraryKind.FAVOURITE))
        assertNull(f.imports.state("b", movie, rating))
        assertEquals(ImportOfferRecord("a", true, false), f.imports.offer("a"))
        assertEquals(ImportOfferRecord("b", false, true), f.imports.offer("b"))
        assertNull(f.imports.offer("missing"))
    } }

    @Test
    fun corruptLegacyAndDurableRowsAreVisibleNotEmpty() = runTest { fixture { f ->
        localStorage.setItem(f.prefix + "bad", "not json")
        assertEquals(BrowserStorageFailure.Corrupt, assertFailsWith<BrowserStorageException> { f.settings.data.first() }.failure)
        assertEquals("not json", localStorage.getItem(f.prefix + "bad"))
        localStorage.removeItem(f.prefix + "bad")
        f.database.edit("guest", "bad") { "{}" }
        assertEquals(BrowserStorageFailure.Corrupt, assertFailsWith<BrowserStorageException> { f.guest.all() }.failure)
    } }

    @Test
    fun separateRealmCommitAndBroadcastReloadTheDurableSnapshot() = runTest { fixture { f ->
        f.settings.edit { it.setString("language", "en-US") }
        val peer = createRealm()
        try {
            val initial = CompletableDeferred<Unit>()
            val observed = async { f.settings.data.first {
                if (it.string("language") == "en-US") initial.complete(Unit)
                it.string("language") == "fr-FR"
            } }
            initial.await()
            realmWrite(peer, f.name, "settings", f.prefix, "{\"language\":\"fr-FR\"}", f.channel, "Settings", true).await()
            assertEquals("fr-FR", observed.await().string("language"))
            assertTrue(f.changes.changes.value.settings > 1)
        } finally { removeRealm(peer) }
    } }

    @Test
    fun missedNotificationIsRecoveredByFocusAndVisibility() = runTest { fixture { f ->
        f.settings.edit { it.setString("language", "en-US") }
        val peer = createRealm()
        try {
            val initial = CompletableDeferred<Unit>()
            val observed = async { f.settings.data.first {
                if (it.string("language") == "en-US") initial.complete(Unit)
                it.string("language") == "de-DE"
            } }
            initial.await()
            realmWrite(peer, f.name, "settings", f.prefix, "{\"language\":\"de-DE\"}", f.channel, "Settings", false).await()
            tick().await(); assertFalse(observed.isCompleted)
            focusCatchUp()
            assertEquals("de-DE", observed.await().string("language"))
            val before = f.changes.changes.value.revalidate
            visibilityCatchUp()
            assertTrue(f.changes.changes.value.revalidate > before)
        } finally { removeRealm(peer) }
    } }

    @Test
    fun storageMarkerFallbackWorksAcrossRealmsWithoutBroadcastChannel() = runTest {
        val restore = disableBroadcastChannel()
        try { fixture { f ->
            f.settings.edit { it.setString("language", "en-US") }
            val peer = createRealm()
            try {
                val changed = async { f.changes.changes.first { it.session > 0 } }
                realmMarker(peer, "__${f.channel}.revision").await()
                assertTrue(changed.await().session > 0)
                f.changes.publish(BrowserChangeTopic.Session)
                val marker = localStorage.getItem("__${f.channel}.revision")!!
                assertTrue(marker.contains("Session")); assertFalse(marker.contains("account"))
            } finally { removeRealm(peer) }
        } } finally { restoreGlobals(restore) }
    }

    @Test
    fun importLockSerializesConnectionsAndCanceledWaitersDoNotLeak() = runTest { fixture { f ->
        val lock = BrowserImportLock(f.database)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = launch { lock.withLock { entered.complete(Unit); release.await() } }
        entered.await()
        var secondEntered = false
        val canceled = launch { BrowserImportLock(f.database).withLock { error("canceled waiter entered") } }
        tick().await(); canceled.cancelAndJoin()
        val second = async { BrowserImportLock(f.database).withLock { secondEntered = true } }
        tick().await(); assertFalse(secondEntered)
        release.complete(Unit); first.join(); second.await(); assertTrue(secondEntered)
    } }

    @Test
    fun separateRealmImportLockExcludesLocalImporter() = runTest { fixture { f ->
        val peer = createRealm()
        val held = realmHoldLock(peer, f.name + ".import").await()
        try {
            var entered = false
            val importer = async { BrowserImportLock(f.database).withLock { entered = true } }
            tick().await(); assertFalse(entered)
            releaseRealmLock(held)
            importer.await(); assertTrue(entered)
        } finally { releaseRealmLock(held); removeRealm(peer) }
    } }

    @Test
    fun realBlockedUpgradeEventIsVisibleAndLateConnectionIsClosed() = runTest { fixture { f ->
        f.settings.edit { it.setString("language", "old") }
        f.database.close()
        // Force a future-version upgrade while a real old connection holds it blocked.
        val blocker = blockUpgrade(f.name).await()
        val upgrading = BrowserDatabase(f.name)
        try {
            assertEquals(BrowserStorageFailure.Blocked, assertFailsWith<BrowserStorageException> {
                BrowserSettingsStore(f.prefix, upgrading, f.changes).edit { it.setString("language", "new") }
            }.failure)
        } finally { upgrading.close(); restoreGlobals(blocker) }
        // The queued open finishes after blocker.close(); BrowserDatabase must close that late connection.
    } }

    @Test
    fun storedNullIsCorruptAndLegacyStoragePolicyFailureIsVisible() = runTest { fixture { f ->
        localStorage.setItem(f.prefix + "language", "\"old\"")
        val restore = denyLegacyReads()
        try {
            assertEquals(BrowserStorageFailure.Unavailable, assertFailsWith<BrowserStorageException> { f.settings.data.first() }.failure)
        } finally { restoreGlobals(restore) }
        assertEquals("old", f.settings.data.first().string("language"))
        // Use the peer to corrupt a persisted blob; JSON null must not be treated as an absent/migratable key.
        val peer = createRealm()
        try { realmWrite(peer, f.name, "settings", f.prefix, "null", f.channel, "Settings", false).await() }
        finally { removeRealm(peer) }
        assertEquals(BrowserStorageFailure.Corrupt, assertFailsWith<BrowserStorageException> {
            f.settings.edit { it.setString("language", "new") }
        }.failure)
    } }

    @Test
    fun cancellationAfterRealCommitStillInvalidatesLiveSettingsObservers() = runTest { fixture { f ->
        f.settings.edit { it.setString("language", "old") }
        val initial = CompletableDeferred<Unit>()
        var last: String? = null
        val observer = backgroundScope.launch {
            f.settings.data.collect {
                last = it.string("language")
                if (last == "old") initial.complete(Unit)
            }
        }
        var restore: JsAny? = null
        try {
            initial.await()
            val before = f.changes.changes.value.settings
            val committed = CompletableDeferred<Unit>()
            lateinit var writer: Job
            restore = cancelOnRealCommit(f.name, "settings") { writer.cancel(); committed.complete(Unit) }
            var returned = false
            writer = launch { f.settings.edit { it.setString("language", "new") }; returned = true }
            committed.await(); writer.join()
            restoreGlobals(restore); restore = null
            assertTrue(writer.isCancelled); assertFalse(returned)
            val reopened = BrowserDatabase(f.name)
            try {
                assertEquals("new", BrowserSettingsStore(f.prefix, reopened, f.changes).data.first().string("language"))
            } finally { reopened.close() }
            tick().await()
            assertTrue(f.changes.changes.value.settings > before, "Commit must invalidate even when its caller cannot resume")
            assertEquals("new", last)
        } finally { restore?.let(::restoreGlobals); observer.cancelAndJoin() }
    } }

    @Test
    fun cancellationAfterRealCommitStillInvalidatesLiveGuestUpsertObservers() = canceledGuestCommit(delete = false)

    @Test
    fun cancellationAfterRealCommitStillInvalidatesLiveGuestDeleteObservers() = canceledGuestCommit(delete = true)

    private fun canceledGuestCommit(delete: Boolean) = runTest { fixture { f ->
        val old = GuestLibraryRow(movie, rating, 4.5, 1, "old")
        val new = old.copy(value = 8.5, titleJson = "new")
        val expected = if (delete) emptyList() else listOf(new)
        f.guest.upsert(old)
        val initial = CompletableDeferred<Unit>()
        var last: List<GuestLibraryRow>? = null
        val observer = backgroundScope.launch {
            f.guest.observeTitle(movie).collect {
                last = it
                if (it == listOf(old)) initial.complete(Unit)
            }
        }
        var restore: JsAny? = null
        try {
            initial.await()
            val before = f.changes.changes.value.library
            val committed = CompletableDeferred<Unit>()
            lateinit var writer: Job
            restore = cancelOnRealCommit(f.name, "guest") { writer.cancel(); committed.complete(Unit) }
            var returned = false
            writer = launch {
                if (delete) f.guest.delete(movie, rating) else f.guest.upsert(new)
                returned = true
            }
            committed.await(); writer.join()
            restoreGlobals(restore); restore = null
            assertTrue(writer.isCancelled); assertFalse(returned)
            val reopened = BrowserDatabase(f.name)
            try { assertEquals(expected, BrowserGuestLibraryStore(reopened, f.changes).all()) }
            finally { reopened.close() }
            tick().await()
            assertTrue(f.changes.changes.value.library > before, "Commit must invalidate even when its caller cannot resume")
            assertEquals(expected, last)
        } finally { restore?.let(::restoreGlobals); observer.cancelAndJoin() }
    } }

    @Test
    fun requestSuccessFollowedByAbortDoesNotReturnSuccessOrPublish() = runTest { fixture { f ->
        f.settings.edit { it.setString("language", "old"); it.setBoolean("motion", false) }
        val before = f.changes.changes.value
        val restore = abortAfterRequestSuccess()
        try {
            assertEquals(BrowserStorageFailure.Aborted, assertFailsWith<BrowserStorageException> {
                f.settings.edit { it.setString("language", "new"); it.setBoolean("motion", true) }
            }.failure)
        } finally { restoreGlobals(restore) }
        assertEquals(before, f.changes.changes.value)
        assertEquals("old", f.settings.data.first().string("language"))
        assertEquals(false, f.settings.data.first().boolean("motion"))
    } }

    @Test
    fun publishUpdatesWriterAndPeerAndKeepsAllTopicRevisions() = runTest { fixture { f ->
        val peer = BrowserChanges(f.channel)
        try {
            val received = async { peer.changes.first { it.settings > 0 && it.library > 0 && it.session > 0 } }
            f.changes.publish(BrowserChangeTopic.Settings)
            f.changes.publish(BrowserChangeTopic.Library)
            f.changes.publish(BrowserChangeTopic.Session)
            assertEquals(BrowserRevisions(1, 1, 1, 0), f.changes.changes.value)
            assertEquals(BrowserRevisions(1, 1, 1, 0), received.await())
        } finally { peer.close() }
    } }

    @Test
    fun canceledOpenClosesItsLateConnection() = runTest { fixture { f ->
        val restore = delayOpenSuccess()
        try {
            val opening = launch { f.guest.all() }
            tick().await()
            opening.cancelAndJoin()
            f.database.close()
            // Cleanup deletes the isolated database; a leaked late connection would block it and fail the test.
            tick().await(); tick().await(); tick().await(); tick().await()
        } finally { restoreGlobals(restore) }
        assertEquals(BrowserStorageFailure.Closed, assertFailsWith<BrowserStorageException> { f.guest.all() }.failure)
    } }

    @Test
    fun closeDisposesSignalsAndRejectsFurtherStorageAccess() = runTest { fixture { f ->
        f.settings.edit { it.setString("language", "en-US") }
        f.changes.close()
        val before = f.changes.changes.value
        focusCatchUp(); visibilityCatchUp()
        f.settings.edit { it.setString("language", "committed after bus disposal") }
        assertEquals("committed after bus disposal", f.settings.data.first().string("language"))
        assertEquals(before, f.changes.changes.value)
        f.database.close()
        assertEquals(BrowserStorageFailure.Closed, assertFailsWith<BrowserStorageException> { f.guest.all() }.failure)
    } }

    /** Injected platform failures, not evidence of a real full disk or browser policy setting. */
    @Test
    fun injectedUnsupportedBlockedAndQuotaFailuresAreTypedAndNeverVolatile() = runTest {
        for ((mode, expected) in listOf("unsupported" to BrowserStorageFailure.Unsupported, "blocked" to BrowserStorageFailure.Blocked, "quota" to BrowserStorageFailure.Quota)) {
            fixture { f ->
                localStorage.setItem(f.prefix + "language", "\"legacy\"")
                if (mode == "quota") f.settings.data.first() // open/migrate before transaction fault injection
                val restore = injectFailure(mode)
                try {
                    assertEquals(expected, assertFailsWith<BrowserStorageException> { f.settings.edit { it.setString("language", "new") } }.failure)
                    assertEquals("\"legacy\"", localStorage.getItem(f.prefix + "language"))
                } finally { restoreGlobals(restore) }
                if (mode == "quota") assertEquals("legacy", f.settings.data.first().string("language"))
            }
        }
    }

    @Test
    fun injectedNoWebLocksRejectsImportWithoutRunningBlock() = runTest { fixture { f ->
        val restore = disableLocks()
        try {
            var ran = false
            assertEquals(BrowserStorageFailure.Unsupported, assertFailsWith<BrowserStorageException> {
                BrowserImportLock(f.database).withLock { ran = true }
            }.failure)
            assertFalse(ran)
        } finally { restoreGlobals(restore) }
    } }
}

@JsFun("() => crypto.randomUUID()")
private external fun uniqueId(): String

@JsFun("name => new Promise((resolve, reject) => { const r = indexedDB.deleteDatabase(name); r.onsuccess = () => resolve(null); r.onerror = () => reject(r.error); r.onblocked = () => reject(new Error('test cleanup blocked')); })")
private external fun deleteDatabase(name: String): Promise<JsAny?>

@JsFun("() => new Promise(resolve => setTimeout(() => resolve(null), 25))")
private external fun tick(): Promise<JsAny?>

@JsFun("() => { const frame = document.createElement('iframe'); document.body.appendChild(frame); return frame; }")
private external fun createRealm(): JsAny
@JsFun("frame => frame.remove()")
private external fun removeRealm(frame: JsAny)

@JsFun("""(frame, name, store, key, value, channel, topic, notify) => {
    const run = new frame.contentWindow.Function('args', `return new Promise((resolve, reject) => {
        const [name, store, key, value, channel, topic, notify] = args;
        const request = indexedDB.open(name, 1);
        request.onerror = () => reject(request.error);
        request.onsuccess = () => {
            const db = request.result, tx = db.transaction(store, 'readwrite');
            tx.objectStore(store).put(value, key);
            tx.onabort = () => { db.close(); reject(tx.error); };
            tx.oncomplete = () => {
                db.close();
                if (notify) { const bus = new BroadcastChannel(channel); bus.postMessage({topic, revision:crypto.randomUUID()}); bus.close(); }
                resolve(null);
            };
        };
    });`);
    return run([name, store, key, value, channel, topic, notify]);
}""")
private external fun realmWrite(frame: JsAny, name: String, store: String, key: String, value: String, channel: String, topic: String, notify: Boolean): Promise<JsAny?>

@JsFun("""(frame, key) => {
    const run = new frame.contentWindow.Function('key', "localStorage.setItem(key, JSON.stringify({topic:'Session', revision:crypto.randomUUID()})); return Promise.resolve(null);");
    return run(key);
}""")
private external fun realmMarker(frame: JsAny, key: String): Promise<JsAny?>

@JsFun("() => window.dispatchEvent(new Event('focus'))")
private external fun focusCatchUp()
@JsFun("() => { Object.defineProperty(document, 'visibilityState', {configurable:true, value:'visible'}); document.dispatchEvent(new Event('visibilitychange')); delete document.visibilityState; }")
private external fun visibilityCatchUp()

@JsFun("""() => {
    const old = globalThis.BroadcastChannel;
    globalThis.BroadcastChannel = undefined;
    return {restore:() => { globalThis.BroadcastChannel = old; }};
}""")
private external fun disableBroadcastChannel(): JsAny

@JsFun("""mode => {
    if (mode === 'unsupported') {
        const old = Object.getOwnPropertyDescriptor(globalThis, 'indexedDB');
        Object.defineProperty(globalThis, 'indexedDB', {configurable:true, value:undefined});
        return {restore:() => { if (old) Object.defineProperty(globalThis, 'indexedDB', old); else delete globalThis.indexedDB; }};
    }
    if (mode === 'blocked') {
        const old = IDBFactory.prototype.open;
        IDBFactory.prototype.open = function() { const request = {}; setTimeout(() => request.onblocked(), 0); return request; };
        return {restore:() => { IDBFactory.prototype.open = old; }};
    }
    const old = IDBDatabase.prototype.transaction;
    IDBDatabase.prototype.transaction = function() { throw new DOMException('injected', 'QuotaExceededError'); };
    return {restore:() => { IDBDatabase.prototype.transaction = old; }};
}""")
private external fun injectFailure(mode: String): JsAny

@JsFun("""() => {
    const old = Object.getOwnPropertyDescriptor(navigator, 'locks');
    Object.defineProperty(navigator, 'locks', {configurable:true, value:undefined});
    return {restore:() => { if (old) Object.defineProperty(navigator, 'locks', old); else delete navigator.locks; }};
}""")
private external fun disableLocks(): JsAny

@JsFun("state => state.restore()")
private external fun restoreGlobals(state: JsAny)

@JsFun("""(frame, name, key) => {
    const run = new frame.contentWindow.Function('name', 'key', `return new Promise((resolve, reject) => {
        const request = indexedDB.open(name, 1);
        request.onerror = () => reject(request.error);
        request.onsuccess = () => {
            const db = request.result;
            let remaining = 20;
            const next = () => {
                const tx = db.transaction('settings', 'readwrite'), store = tx.objectStore('settings'), read = store.get(key);
                read.onsuccess = () => {
                    const values = JSON.parse(read.result), count = Number(values.counter) + 1;
                    values.counter = String(count); values.copy = String(count); values.peer = 'kept';
                    store.put(JSON.stringify(values), key);
                };
                tx.onabort = () => { db.close(); reject(tx.error); };
                tx.oncomplete = () => { if (--remaining) next(); else { db.close(); resolve(null); } };
            };
            next();
        };
    });`);
    return run(name, key);
}""")
private external fun realmIncrement(frame: JsAny, name: String, key: String): Promise<JsAny?>

@JsFun("""() => {
    const old = IDBObjectStore.prototype.put;
    IDBObjectStore.prototype.put = function(...args) {
        const request = old.apply(this, args), tx = this.transaction;
        request.addEventListener('success', () => tx.abort());
        return request;
    };
    return {restore:() => { IDBObjectStore.prototype.put = old; }};
}""")
private external fun abortAfterRequestSuccess(): JsAny

@JsFun("""(frame, name) => {
    const run = new frame.contentWindow.Function('name', `return new Promise((resolve, reject) => {
        navigator.locks.request(name, () => new Promise(release => resolve({release}))).catch(reject);
    });`);
    return run(name);
}""")
private external fun realmHoldLock(frame: JsAny, name: String): Promise<JsAny>
@JsFun("held => held.release()")
private external fun releaseRealmLock(held: JsAny)

@JsFun("""name => new Promise((resolve, reject) => {
    const original = IDBFactory.prototype.open, request = original.call(indexedDB, name, 1);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => {
        const blocker = request.result;
        IDBFactory.prototype.open = function(dbName, version) { return original.call(this, dbName, dbName === name ? 2 : version); };
        resolve({restore:() => { IDBFactory.prototype.open = original; blocker.close(); }});
    };
})""")
private external fun blockUpgrade(name: String): Promise<JsAny>

@JsFun("""() => {
    const old = Storage.prototype.getItem;
    Storage.prototype.getItem = function() { throw new DOMException('injected policy', 'SecurityError'); };
    return {restore:() => { Storage.prototype.getItem = old; }};
}""")
private external fun denyLegacyReads(): JsAny

@JsFun("""() => {
    const old = IDBFactory.prototype.open;
    IDBFactory.prototype.open = function(...args) {
        const request = old.apply(this, args);
        return {
            get result() { return request.result; }, get error() { return request.error; },
            set onupgradeneeded(callback) { request.onupgradeneeded = callback; },
            set onblocked(callback) { request.onblocked = callback; },
            set onerror(callback) { request.onerror = callback; },
            set onsuccess(callback) { request.onsuccess = () => setTimeout(callback, 75); }
        };
    };
    return {restore:() => { IDBFactory.prototype.open = old; }};
}""")
private external fun delayOpenSuccess(): JsAny

@JsFun("""(name, store, cancel) => {
    const old = IDBDatabase.prototype.transaction;
    IDBDatabase.prototype.transaction = function(...args) {
        const tx = old.apply(this, args);
        if (this.name === name && args[0] === store && args[1] === 'readwrite') {
            // Runs on real commit, before the production completion handler can resume the canceled writer.
            tx.addEventListener('complete', () => cancel(), {once:true});
        }
        return tx;
    };
    return {restore:() => { IDBDatabase.prototype.transaction = old; }};
}""")
private external fun cancelOnRealCommit(name: String, store: String, cancel: () -> Unit): JsAny
