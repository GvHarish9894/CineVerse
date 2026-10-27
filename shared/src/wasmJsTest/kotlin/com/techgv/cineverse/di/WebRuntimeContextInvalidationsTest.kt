@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.di

import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.settings.*
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.web.*
import com.techgv.cineverse.util.GenerationCounter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.js.Promise
import kotlin.test.*

/** Real graph with only the remote server faked: every runtime cache-generation bump reaches contextInvalidations. */
class WebRuntimeContextInvalidationsTest {
    @Test fun everyRuntimeGenerationBumpAndSessionOrStorageChangeEmitsWithoutPolling() = runTest {
        val name = "cineverse.invalidations.test." + invalidationId()
        val prefix = "$name.legacy."
        val channel = "$name.bus"
        val database = BrowserDatabase(name)
        val bus = BrowserChanges(channel)
        var identity: AuthSession? = AuthSession(AccountObjectId("a"), "A", Long.MAX_VALUE)
        var sessionReads = 0
        val client = HttpClient(MockEngine { request ->
            val body = if (request.url.encodedPath == "/api/session") {
                sessionReads++
                document.cookie = "cv_csrf=invalidations; Path=/; SameSite=Strict"
                identity?.let { """{"signedIn":true,"sessionGeneration":"${it.sessionGeneration}","accountObjectId":"${it.accountObjectId.value}","displayName":"${it.displayName}"}""" }
                    ?: """{"signedIn":false,"sessionGeneration":"0","accountObjectId":null,"displayName":null}"""
            } else """{"page":1,"results":[],"total_pages":1,"total_results":0}"""
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val app = koinApplication { modules(sharedModules(WebConfig("/api")) + module {
            single { database }
            single { bus }
            single<HttpClient> { client }
            single { BrowserSettingsStore(prefix, database, bus) }
            single<SettingsStore> { get<BrowserSettingsStore>() }
        }) }
        val runtime = app.koin.get<WebRuntime>()
        val generation = app.koin.get<GenerationCounter>()
        val settings = app.koin.get<SettingsRepository>()
        val auth = app.koin.get<WebAuthRepository>()
        val observer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var received = 0
        try {
            observer.launch(start = CoroutineStart.UNDISPATCHED) { runtime.contextInvalidations.collect { received++ } }
            runtime.start()
            runtime.session.first { it == WebSessionState.Ready(identity) }
            settings.current()
            settle()
            suspend fun emitsAfterBump(bumps: Boolean, trigger: suspend () -> Unit) {
                val count = received; val before = generation.current
                trigger()
                invalidationUntil { received > count && (!bumps || generation.current > before) }
                settle()
            }
            emitsAfterBump(bumps = true) { settings.update { it.copy(language = "ta-IN") } }
            emitsAfterBump(bumps = true) { runtime.revalidateSession() }
            runtime.session.first { it == WebSessionState.Ready(identity) }
            settle()
            // Focus/visibility/peer-session hints with an unchanged identity: a background read only.
            val sessions = mutableListOf<WebSessionState>()
            val recorder = observer.launch(start = CoroutineStart.UNDISPATCHED) { runtime.session.collect { sessions += it } }
            val quiet = received; val quietGeneration = generation.current; val reads = sessionReads
            invalidationFocus(); invalidationVisible(); bus.publish(BrowserChangeTopic.Session)
            invalidationUntil { sessionReads > reads }
            settle()
            assertEquals(quiet, received, "an unchanged identity emits no invalidation")
            assertEquals(quietGeneration, generation.current)
            assertEquals(listOf<WebSessionState>(WebSessionState.Ready(identity)), sessions, "a hint never shows Checking")
            // A changed verified identity takes the hard transition and bumps the generation exactly once.
            identity = AuthSession(AccountObjectId("b"), "B", 7)
            invalidationFocus()
            runtime.session.first { it == WebSessionState.Ready(identity) }
            settle()
            recorder.cancel()
            assertEquals(quietGeneration + 1, generation.current)
            assertTrue(received > quiet)
            emitsAfterBump(bumps = false) { runtime.reportStorageFailure(BrowserStorageException(BrowserStorageFailure.Quota)) }
            emitsAfterBump(bumps = false) { runtime.retryStorage() }
            assertNull(runtime.storageFailure.value)
            identity = null // Verified identity change plus sessionExpired, both of which bump the cache generation.
            emitsAfterBump(bumps = true) { auth.recover(WebProxyException("session_expired", 401, null, WebWriteOutcome.NotApplicable, TmdbError.Http(401, null))) }
            assertEquals(WebSessionState.Ready(null), runtime.session.value)
            val idle = received
            repeat(3) { invalidationTick().await() }
            assertEquals(idle, received, "no polling or fake counter")
        } finally {
            observer.coroutineContext.job.cancelAndJoin()
            runtime.close(); app.close()
            invalidationDeleteDatabase(name).await()
            (0 until localStorage.length).mapNotNull { localStorage.key(it) }
                .filter { it.startsWith(prefix) || it == "__$channel.revision" }.forEach(localStorage::removeItem)
            document.cookie = "cv_csrf=; Max-Age=0; Path=/"
        }
    }

    private suspend fun settle() { repeat(2) { invalidationTick().await() } }
}

private suspend fun invalidationUntil(condition: () -> Boolean) {
    repeat(120) { if (condition()) return; invalidationTick().await() }
    error("context invalidation did not arrive")
}

@JsFun("() => crypto.randomUUID()")
private external fun invalidationId(): String
@JsFun("() => window.dispatchEvent(new Event('focus'))")
private external fun invalidationFocus()
@JsFun("() => { Object.defineProperty(document, 'visibilityState', {configurable:true,value:'visible'}); document.dispatchEvent(new Event('visibilitychange')); delete document.visibilityState; }")
private external fun invalidationVisible()
@JsFun("() => new Promise(resolve => setTimeout(() => resolve(null), 20))")
private external fun invalidationTick(): Promise<JsAny?>
@JsFun("name => new Promise((resolve, reject) => { const r = indexedDB.deleteDatabase(name); r.onsuccess = () => resolve(null); r.onerror = () => reject(r.error); r.onblocked = () => reject(new Error('isolated cleanup blocked')); })")
private external fun invalidationDeleteDatabase(name: String): Promise<JsAny?>
