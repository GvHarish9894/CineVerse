@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.data.storage

import kotlinx.coroutines.await
import kotlin.js.Promise

/** Wrap the entire importer run (including reconciliation/POSTs), not individual ledger writes. */
class BrowserImportLock(database: BrowserDatabase) {
    private val name = database.name + ".import"

    /** Unsupported browsers reject imports; revision markers do not provide mutual exclusion. */
    suspend fun <T> withLock(block: suspend () -> T): T {
        val request = requestLock(name)
        try {
            val error = awaitLock(request).await().toString()
            if (error.isNotEmpty()) throw storageFailure(error)
            return block()
        } finally {
            releaseLock(request)
        }
    }
}

@JsFun("""name => {
    const request = {controller:new AbortController(), release:null, canceled:false};
    request.promise = new Promise(resolve => {
        try {
            if (!navigator.locks) { resolve('Unsupported'); return; }
            navigator.locks.request(name, {mode:'exclusive', signal:request.controller.signal}, () => {
                if (request.canceled) return;
                return new Promise(release => { request.release = release; resolve(''); });
            }).catch(error => resolve(error.name || 'Unavailable'));
        } catch (error) { resolve(error.name || 'Unavailable'); }
    });
    return request;
}""")
private external fun requestLock(name: String): JsAny

@JsFun("request => request.promise")
private external fun awaitLock(request: JsAny): Promise<JsString>

@JsFun("""request => {
    request.canceled = true;
    request.controller.abort();
    request.release?.();
}""")
private external fun releaseLock(request: JsAny)
