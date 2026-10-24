@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.data.storage

import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.js.Promise

/** Failures are not empty snapshots. UI can distinguish recovery actions without exposing stored payloads. */
enum class BrowserStorageFailure { Unsupported, Blocked, Quota, Corrupt, Closed, Aborted, Unavailable }

class BrowserStorageException(val failure: BrowserStorageFailure) : Exception("Browser storage: $failure")

/** One shared database per browser composition root; close after its stores have stopped collecting. */
class BrowserDatabase(val name: String = "cineverse") {
    private var connection: JsAny? = null
    private var closed = false

    private suspend fun connection(): JsAny {
        if (closed) throw BrowserStorageException(BrowserStorageFailure.Closed)
        connection?.let { return it }
        val opening = openDatabase(name)
        val opened = try { opening.await() } catch (failure: Throwable) {
            closeWhenOpened(opening)
            throw failure
        }
        val error = openError(opened)
        if (error != null) throw storageFailure(error)
        if (closed) {
            closeDatabase(opened)
            throw BrowserStorageException(BrowserStorageFailure.Closed)
        }
        // Concurrent first callers can open separate connections; retain only one.
        connection?.let { closeDatabase(opened); return it }
        connection = opened
        return opened
    }

    internal suspend fun read(store: String, key: String): String? =
        result(databaseRead(connection(), store, key, false).await())

    internal suspend fun all(store: String): String =
        result(databaseRead(connection(), store, "", true).await())!!

    /** Transform runs in the transaction; commit notification must not depend on the cancellable caller resuming. */
    internal suspend fun edit(store: String, key: String, onCommitted: () -> Unit = {}, transform: (String?) -> String?) {
        var transformFailure: Throwable? = null
        val response = databaseEdit(connection(), store, key, onCommitted) { old ->
            try {
                (transform(old?.toString()) ?: "null").toJsString()
            } catch (failure: Throwable) {
                transformFailure = failure
                // A malformed JSON result makes the bridge abort, leaving the previous snapshot intact.
                "!abort".toJsString()
            }
        }.await()
        transformFailure?.let { throw it }
        result(response)
    }

    fun close() {
        closed = true
        connection?.let(::closeDatabase)
        connection = null
    }

    @Serializable
    private data class Result(val value: String? = null, val error: String? = null)

    private fun result(raw: JsString): String? {
        val result = try { Json.decodeFromString<Result>(raw.toString()) }
        catch (_: Exception) { throw BrowserStorageException(BrowserStorageFailure.Corrupt) }
        result.error?.let { throw storageFailure(it) }
        return result.value
    }
}

internal fun storageFailure(name: String): BrowserStorageException = BrowserStorageException(when (name) {
    "Unsupported" -> BrowserStorageFailure.Unsupported
    "Blocked" -> BrowserStorageFailure.Blocked
    "QuotaExceededError" -> BrowserStorageFailure.Quota
    "Corrupt" -> BrowserStorageFailure.Corrupt
    "InvalidStateError", "Closed" -> BrowserStorageFailure.Closed
    "AbortError" -> BrowserStorageFailure.Aborted
    else -> BrowserStorageFailure.Unavailable
})

internal inline fun <reified T> decodeStorage(raw: String): T = try { Json.decodeFromString<T>(raw) }
catch (_: Exception) { throw BrowserStorageException(BrowserStorageFailure.Corrupt) }

@JsFun("""(name) => new Promise(resolve => {
    let request, settled = false;
    const fail = name => { if (!settled) { settled = true; resolve({failure:name}); } };
    try {
        if (!globalThis.indexedDB) { fail('Unsupported'); return; }
        request = indexedDB.open(name, 1);
        request.onupgradeneeded = () => {
            for (const store of ['settings', 'guest', 'ledger', 'offers']) {
                if (!request.result.objectStoreNames.contains(store)) request.result.createObjectStore(store);
            }
        };
        request.onblocked = () => fail('Blocked');
        request.onerror = () => fail(request.error?.name || 'Unavailable');
        request.onsuccess = () => {
            const db = request.result;
            if (settled) { db.close(); return; }
            if (!['settings', 'guest', 'ledger', 'offers'].every(store => db.objectStoreNames.contains(store))) {
                db.close(); fail('Corrupt'); return;
            }
            db.onversionchange = () => db.close();
            settled = true; resolve(db);
        };
    } catch (e) { fail(e.name); }
})""")
private external fun openDatabase(name: String): Promise<JsAny>

@JsFun("promise => { promise.then(db => { if (!db.failure) db.close(); }); }")
private external fun closeWhenOpened(promise: Promise<JsAny>)

@JsFun("(db) => db.failure || null")
private external fun openError(db: JsAny): String?

@JsFun("(db) => db.close()")
private external fun closeDatabase(db: JsAny)

@JsFun("""(db, store, key, all) => new Promise(resolve => {
    let tx, value = null, failure;
    const fail = name => resolve(JSON.stringify({error:name}));
    try {
        tx = db.transaction(store, 'readonly');
        tx.oncomplete = () => resolve(JSON.stringify({value}));
        tx.onabort = () => fail(failure || tx.error?.name || 'AbortError');
        const request = all ? tx.objectStore(store).getAll() : tx.objectStore(store).get(key);
        request.onsuccess = () => {
            try {
                if (all) {
                    if (!request.result.every(v => typeof v === 'string')) throw new Error();
                    value = JSON.stringify(request.result);
                } else if (request.result !== undefined) {
                    if (typeof request.result !== 'string') throw new Error();
                    value = request.result;
                }
            } catch (_) { failure = 'Corrupt'; tx.abort(); }
        };
    } catch (e) { fail(e.name); }
})""")
private external fun databaseRead(db: JsAny, store: String, key: String, all: Boolean): Promise<JsString>

@JsFun("""(db, store, key, onCommitted, transform) => new Promise(resolve => {
    let tx, failure;
    const fail = name => resolve(JSON.stringify({error:name}));
    try {
        // strict requests physical durability where supported; older Safari uses its default commit policy.
        try { tx = db.transaction(store, 'readwrite', {durability:'strict'}); }
        catch (e) { if (e instanceof TypeError) tx = db.transaction(store, 'readwrite'); else throw e; }
        tx.oncomplete = () => { try { onCommitted(); } finally { resolve(JSON.stringify({value:null})); } };
        tx.onabort = () => fail(failure || tx.error?.name || 'AbortError');
        const objectStore = tx.objectStore(store), request = objectStore.get(key);
        request.onsuccess = () => {
            try {
                if (request.result !== undefined && typeof request.result !== 'string') {
                    failure = 'Corrupt'; tx.abort(); return;
                }
                const value = transform(request.result === undefined ? null : request.result);
                if (value === 'null') objectStore.delete(key);
                else { JSON.parse(value); objectStore.put(value, key); }
            } catch (e) { failure = e.name || 'Corrupt'; tx.abort(); }
        };
    } catch (e) { fail(e.name); }
})""")
private external fun databaseEdit(db: JsAny, store: String, key: String, onCommitted: () -> Unit, transform: (JsString?) -> JsString): Promise<JsString>
