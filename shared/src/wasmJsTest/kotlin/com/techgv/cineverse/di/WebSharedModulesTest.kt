@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.di

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.settings.*
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.data.web.*
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.util.GenerationCounter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import kotlinx.browser.document
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.js.Promise
import kotlin.test.*

/** Real graph/stores/bus/locks with only the remote server replaced. All persisted names are test-local. */
class WebSharedModulesTest {
    private class Fixture(val name: String = "cineverse.c4.test." + uniqueId()) {
        val prefix = "$name.legacy."
        val channel = "$name.bus"
        val database = BrowserDatabase(name)
        val bus = BrowserChanges(channel)
        val calls = mutableListOf<HttpRequestData>()
        var identity: AuthSession? = session("a", generation = Long.MAX_VALUE)
        var sessionStatus = HttpStatusCode.OK
        var gatePath: String? = null
        var gate: CompletableDeferred<Unit>? = null
        var postCheck: suspend () -> Unit = {}
        var postStatus = HttpStatusCode.OK
        var landed = false
        var onCancel: () -> Unit = {}
        val client = HttpClient(MockEngine { request ->
            calls += request
            val path = request.url.encodedPath
            try { if (path == gatePath) gate?.await() }
            catch (canceled: CancellationException) { onCancel(); throw canceled }
            val response = when {
                path == "/api/session" -> {
                    document.cookie = "cv_csrf=c4fixture; Path=/; SameSite=Strict"
                    if (sessionStatus == HttpStatusCode.OK) identity.json() else """{"error":"upstream_error"}"""
                }
                path == "/api/auth/start" -> {
                    assertEquals("c4fixture", request.headers["X-CV-CSRF"])
                    """{"approveUrl":"https://www.themoviedb.org/auth/access?request_token=fixture","attemptId":"fixture"}"""
                }
                request.method != HttpMethod.Get -> {
                    postCheck()
                    if (postStatus == HttpStatusCode.OK) landed = true
                    """{"status_code":1,"success":true}"""
                }
                path.startsWith("/api/account/states/") -> """{"watchlist":$landed,"favorite":false,"rated":false}"""
                path.startsWith("/api/tmdb/genre/") -> """{"genres":[]}"""
                else -> """{"page":1,"results":[],"total_pages":1,"total_results":0}"""
            }
            respond(response, if (path == "/api/session") sessionStatus else if (request.method != HttpMethod.Get) postStatus else HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val app = koinApplication { modules(sharedModules(WebConfig("/api")) + module {
            single { database }
            single { bus }
            single<HttpClient> { client }
            single<SettingsStore> { BrowserSettingsStore(prefix, database, bus) }
        }) }
        val koin get() = app.koin
        val runtime = koin.get<WebRuntime>()
        val auth = koin.get<WebAuthRepository>()
        val settings = koin.get<SettingsRepository>()
        val importer = koin.get<LibraryImporter>()
        val library = koin.get<LibraryRepository>()
        val scope = koin.get<CoroutineScope>(AppScope)
        fun count(path: String) = calls.count { it.url.encodedPath == path }
        suspend fun start() { runtime.start(); runtime.session.first { it is WebSessionState.Ready } }
        suspend fun close(delete: Boolean = true) {
            runtime.close(); app.close()
            if (delete) deleteDatabase(name).await()
            document.cookie = "cv_csrf=; Max-Age=0; Path=/"
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { f.close() }
    }

    @Test fun everyDefinitionResolvesAndGraphsShareOnlyDurableData() = runTest {
        val a = Fixture(); val b = Fixture(a.name)
        try {
            val resolved = a.koin.resolveEveryDefinition()
            assertTrue(resolved.containsAll(listOf(WebRuntime::class, LibraryImporter::class, BrowserImportStore::class,
                CatalogRepository::class, TmdbCache::class, WebAuthRepository::class, HttpClient::class)))
            assertTrue(resolved.none { it.simpleName.orEmpty().contains("Credential") || it.simpleName.orEmpty().contains("Room") })
            assertSame(a.runtime.settingsHolder, a.koin.get<SettingsStateHolder>())
            assertSame(a.runtime.importController, a.koin.get<ImportLibraryStateHolder>())
            assertSame(a.koin.get<BrowserImportStore>(), a.koin.get<ImportLedgerStore>())
            assertSame<Any>(a.koin.get<ImportLedgerStore>(), a.koin.get<ImportOfferStore>())
            assertSame(a.koin.get<BrowserAuthRepository>(), a.koin.get<AuthRepository>())
            assertSame(a.koin.get<BrowserAccountLibrarySource>(), a.koin.get<AccountLibrarySource>())
            assertNotSame(a.database, b.database); assertNotSame(a.bus, b.bus)
            assertNotSame(a.runtime, b.runtime); assertNotSame(a.auth, b.auth)
            assertNotSame(a.scope, b.scope); assertNotSame(a.koin.get<TmdbCache>(), b.koin.get<TmdbCache>())
            assertNotSame(a.koin.get<GenerationCounter>(), b.koin.get<GenerationCounter>())
            a.start(); b.start()
            a.settings.update { it.copy(language = "ta-IN") }
            assertEquals("ta-IN", b.settings.current().language)
            a.runtime.close(); a.app.close()
            val closedRevision = a.bus.changes.value
            val before = b.count("/api/session")
            focus()
            until { b.count("/api/session") > before }
            assertEquals(closedRevision, a.bus.changes.value)
            assertTrue(b.scope.isActive)
            b.settings.update { it.copy(region = "US") }
            assertEquals("US", b.settings.current().region)
        } finally { a.close(delete = false); b.close() }
    }

    @Test fun fixedSameOriginConfigRejectsPretendBaseUrls() {
        assertEquals("/api", WebConfig("/api").proxyBaseUrl)
        listOf("/api/", "https://other.invalid/api", "/custom").forEach {
            assertFailsWith<IllegalArgumentException> { WebConfig(it) }
        }
    }

    @Test fun startupIsAuthoritativeIdempotentAndBootstrapsInitialCsrf() = runTest { fixture { f ->
        assertEquals(WebSessionState.Checking, f.runtime.session.value)
        f.start(); f.runtime.start()
        assertEquals(WebSessionState.Ready(f.identity), f.runtime.session.value)
        assertEquals(1, f.count("/api/session"))
        assertEquals("fixture", f.runtime.startApproval().attemptId)
        assertEquals(1, f.count("/api/auth/start"))
        assertEquals(f.identity, f.auth.session.value)
        assertFailsWith<UnsupportedOperationException> { f.koin.get<AuthRepository>().createRequestCode() }
    } }

    @Test fun startupFailureIsNotSignedOutAndExplicitRetryFetchesTruth() = runTest { fixture { f ->
        f.sessionStatus = HttpStatusCode.ServiceUnavailable
        f.runtime.start()
        f.runtime.session.first { it is WebSessionState.Failed }
        assertFalse(f.runtime.session.value is WebSessionState.Ready)
        assertNull(f.auth.session.value)
        f.sessionStatus = HttpStatusCode.OK
        f.runtime.revalidateSession()
        assertEquals(WebSessionState.Ready(f.identity), f.runtime.session.first { it is WebSessionState.Ready })
    } }

    @Test fun localAndPeerContentSettingsInvalidateCacheGenerationAndVisibleHolderWithoutEcho() = runTest { fixture { f ->
        f.identity = null; f.start()
        f.settings.current()
        val generation = f.koin.get<GenerationCounter>()
        val holder = SearchStateHolder(f.koin.get(), generation, f.settings.settings,
            SavedStateHandleStateSaver(SavedStateHandle()), f.scope)
        holder.results.first { it is SectionState.Content || it is SectionState.Empty }
        val before = generation.current
        val trending = "/api/tmdb/trending/all/week"
        val initial = f.count(trending)
        f.settings.update { it.copy(language = "ta-IN", region = "US", includeAdult = true) }
        until { generation.current > before && f.count(trending) > initial }
        assertEquals("ta-IN", f.calls.last { it.url.encodedPath == trending }.url.parameters["language"])
        val peer = realm()
        try {
            val current = generation.current
            val revision = f.bus.changes.value.settings
            peerWrite(peer, f.name, "settings", f.prefix, """{"language":"hi-IN","region":"GB","include_adult":false}""", f.channel, "Settings").await()
            until { generation.current > current && f.bus.changes.value.settings > revision }
            until { f.calls.last { it.url.encodedPath == trending }.url.parameters["language"] == "hi-IN" }
            val stable = f.bus.changes.value
            val fetched = f.count(trending)
            tick().await(); tick().await()
            assertEquals(stable, f.bus.changes.value)
            assertEquals(fetched, f.count(trending))
        } finally { removeRealm(peer) }
    } }

    @Test fun incomingSessionFocusAndVisibilityHintsDropSignedLongStaleReadsAndWritesWithoutEcho() = runTest { fixture { f ->
        f.start()
        val source = f.koin.get<AccountLibrarySource>()
        val a = f.identity!!
        val path = "/api/account/states/movie/7"
        f.gatePath = path; f.gate = CompletableDeferred()
        val pending = async { assertFailsWith<WebProxyException> { source.states(a, MediaRef(MediaType.Movie, 7)) } }
        until { f.count(path) == 1 }
        f.identity = session("b", generation = Long.MIN_VALUE)
        val peer = realm()
        try {
            peerHint(peer, f.channel, "Session").await()
            until { f.auth.session.value == f.identity }
            f.gate!!.complete(Unit)
            assertEquals("stale_session", pending.await().code)
            val posts = f.calls.count { it.method != HttpMethod.Get }
            assertFailsWith<WebProxyException> { source.write(a, MediaRef(MediaType.Movie, 7), LibraryKind.WATCHLIST,
                LibraryState(true, true, false, null, emptySet())) }
            assertEquals(posts, f.calls.count { it.method != HttpMethod.Get })
            val current = f.identity!!
            source.write(current, MediaRef(MediaType.Movie, 7), LibraryKind.WATCHLIST,
                LibraryState(true, true, false, null, emptySet()))
            assertEquals(Long.MIN_VALUE.toString(), f.calls.last { it.method == HttpMethod.Post }.headers["X-CV-Expected-Session-Generation"])
            until { f.runtime.session.value is WebSessionState.Ready }
            val count = f.count("/api/session")
            focus(); until { f.count("/api/session") > count }
            val afterFocus = f.count("/api/session")
            visibility(); until { f.count("/api/session") > afterFocus }
            tick().await(); val stable = f.bus.changes.value; val calls = f.count("/api/session")
            tick().await(); tick().await()
            assertEquals(stable, f.bus.changes.value); assertEquals(calls, f.count("/api/session"))
        } finally { f.gate?.complete(Unit); removeRealm(peer) }
    } }

    @Test fun externalLibraryHintsReloadBoundLibraryWithoutRebroadcastAndUnknownLocalWritesNotifyPeers() = runTest { fixture { f ->
        f.start()
        val holder = f.runtime.libraryHolder(SavedStateHandleStateSaver(SavedStateHandle()))
        holder.state.first { it.movies.section is SectionState.Empty }
        val path = "/api/account/movie/watchlist"
        val initial = f.count(path)
        val revision = f.library.listRevision.value
        val peer = BrowserChanges(f.channel)
        try {
            peer.publish(BrowserChangeTopic.Library)
            until { f.count(path) > initial && f.library.listRevision.value > revision }
            tick().await(); val stable = peer.changes.value
            tick().await(); tick().await()
            assertEquals(stable, peer.changes.value)
            f.postStatus = HttpStatusCode.ServiceUnavailable
            val notice = async { peer.changes.first { it.library > stable.library } }
            assertFailsWith<WebProxyException> { f.koin.get<AccountLibrarySource>().write(f.identity!!,
                MediaRef(MediaType.Movie, 7), LibraryKind.WATCHLIST, LibraryState(true, true, false, null, emptySet())) }
            notice.await()
            val settled = peer.changes.value
            tick().await(); tick().await()
            assertEquals(settled, peer.changes.value)
        } finally { peer.close() }
    } }

    @Test fun actualBoundControllerWaitsForPeerLockBeforeAnyImportReadAndCancellationReleasesQueuedAndHeldLocks() = runTest { fixture { f ->
        f.koin.get<GuestLibrarySource>().write(title(7), LibraryKind.WATCHLIST, LibraryState(true, true, false, null, emptySet()))
        f.start()
        val controller = f.koin.get<ImportLibraryStateHolder>()
        controller.prompt.first { it is ImportPrompt.Ask }
        val peer = realm()
        val held = holdLock(peer, f.name + ".import").await()
        try {
            controller.import()
            tick().await()
            assertEquals(0, f.calls.count { it.url.encodedPath.startsWith("/api/account/") })
            assertNull(f.koin.get<ImportOfferStore>().offer("a"))
            controller.later() // Queued lock request is canceled, not leaked.
            tick().await()
            releaseLock(held)
            tick().await()
            assertEquals(0, f.calls.count { it.url.encodedPath.startsWith("/api/account/") })
            var attempted = false
            f.postCheck = {
                val connection = BrowserDatabase(f.name)
                try {
                    assertEquals(ImportStepState.Attempting, BrowserImportStore(connection).state("a", MediaRef(MediaType.Movie, 7), LibraryKind.WATCHLIST))
                    attempted = true
                } finally { connection.close() }
                awaitCancellation()
            }
            controller.import()
            until { attempted }
            controller.later() // Cancel after Attempting, while POST outcome is unknown.
            tick().await()
            assertEquals(ImportStepState.Attempting, f.koin.get<ImportLedgerStore>().state("a", MediaRef(MediaType.Movie, 7), LibraryKind.WATCHLIST))
            val acquired = holdLock(peer, f.name + ".import").await()
            releaseLock(acquired)
            // A direct caller of the ACTUAL bound importer is guarded too, not just the controller.
            val second = holdLock(peer, f.name + ".import").await()
            val count = f.calls.size
            val direct = async { f.importer.import(f.identity!!) }
            tick().await(); assertEquals(count, f.calls.size)
            direct.cancelAndJoin(); releaseLock(second)
        } finally { releaseLock(held); removeRealm(peer) }
    } }

    @Test fun boundImporterPersistsAttemptingBeforePostAndReopenReconcilesWithoutBlindReplay() = runTest { fixture { f ->
        f.koin.get<GuestLibrarySource>().write(title(7), LibraryKind.WATCHLIST, LibraryState(true, true, false, null, emptySet()))
        f.start()
        f.postCheck = {
            val connection = BrowserDatabase(f.name)
            try { assertEquals(ImportStepState.Attempting, BrowserImportStore(connection).state("a", MediaRef(MediaType.Movie, 7), LibraryKind.WATCHLIST)) }
            finally { connection.close() }
        }
        assertEquals(1, (f.importer.import(f.identity!!) as ImportResult.Done).written)
        assertEquals(ImportStepState.Done, f.koin.get<ImportLedgerStore>().state("a", MediaRef(MediaType.Movie, 7), LibraryKind.WATCHLIST))
        f.runtime.close(); f.app.close()
        val reopened = Fixture(f.name)
        try {
            reopened.start()
            assertEquals(0, (reopened.importer.import(reopened.identity!!) as ImportResult.Done).written)
            assertEquals(0, reopened.calls.count { it.method != HttpMethod.Get })
            assertEquals(1, reopened.koin.get<GuestLibraryStore>().all().size)
        } finally { reopened.close(delete = false) }
    } }

    @Test fun blockedSettingsReadStaysUnknownThenRetryReadsRealValuesAndQuotaWriteRetainsThem() = runTest { fixture { f ->
        val blocked = failOpen(f.name)
        val observed = f.scope.launch { f.runtime.settingsHolder.state.collect() }
        try {
            f.runtime.start()
            f.runtime.storageFailure.first { it == BrowserStorageFailure.Blocked }
            assertNull(f.runtime.settingsHolder.state.value)
        } finally { restore(blocked) }
        f.runtime.retryStorage()
        until { f.runtime.settingsHolder.state.value != null }
        f.runtime.settingsHolder.cycle(Choice.Language, 1)
        until { f.runtime.settingsHolder.state.value?.settings?.language == "hi-IN" }
        val quota = failTransactions(f.name, "settings")
        try {
            f.runtime.settingsHolder.toggle(Toggle.Adult)
            f.runtime.storageFailure.first { it == BrowserStorageFailure.Quota }
            assertFalse(f.runtime.settingsHolder.state.value!!.settings.includeAdult)
        } finally { restore(quota) }
        f.runtime.retryStorage()
        f.runtime.settingsHolder.toggle(Toggle.Adult)
        until { f.runtime.settingsHolder.state.value?.settings?.includeAdult == true }
        observed.cancelAndJoin()
    } }

    @Test fun quotaGuestFlowAndMutationSurfaceTypedFailureAndExplicitRetryRestartsActualConsumers() = runTest { fixture { f ->
        f.identity = null; f.start()
        f.koin.get<GuestLibrarySource>().write(title(7), LibraryKind.WATCHLIST, LibraryState(true, true, false, null, emptySet()))
        val quota = failTransactions(f.name, "guest")
        val holder = f.runtime.libraryHolder(SavedStateHandleStateSaver(SavedStateHandle()))
        val outcomes = mutableListOf<LibraryWriteOutcome>()
        val actions = f.runtime.libraryActions(outcomes::add)
        try {
            holder.state.first { it.movies.section is SectionState.Error }
            assertEquals(SectionState.Error(SectionErrorKind.Generic), holder.state.value.movies.section)
            assertEquals(BrowserStorageFailure.Quota, f.runtime.storageFailure.value)
            actions.toggleFavourite(title(7))
            until { outcomes.isNotEmpty() }
            assertTrue(outcomes.single().result is LibraryWriteResult.Failed)
        } finally { restore(quota) }
        holder.retry(MediaType.Movie)
        holder.state.first { it.movies.section is SectionState.Content }
        actions.toggleFavourite(title(7))
        until { outcomes.size == 2 }
        assertTrue(outcomes.last().result is LibraryWriteResult.Done)
        assertEquals(2, f.koin.get<GuestLibraryStore>().all().size)
    } }

    @Test fun importOfferAndKeepSeparateStorageFailuresRetryOnlyTheFailedOperationWithoutFalseSuccess() = runTest { fixture { f ->
        f.koin.get<GuestLibrarySource>().write(title(7), LibraryKind.WATCHLIST, LibraryState(true, true, false, null, emptySet()))
        val quota = failTransactions(f.name, "offers")
        try {
            f.start()
            f.runtime.importController.prompt.first { it == ImportPrompt.Failed }
            assertEquals(BrowserStorageFailure.Quota, f.runtime.storageFailure.value)
        } finally { restore(quota) }
        f.runtime.importController.retryStorage()
        f.runtime.importController.prompt.first { it is ImportPrompt.Ask }
        val failedWrite = failTransactions(f.name, "offers")
        try {
            f.runtime.importController.keepSeparate()
            f.runtime.importController.prompt.first { it == ImportPrompt.Failed }
        } finally { restore(failedWrite) }
        assertNull(f.koin.get<ImportOfferStore>().offer("a"))
        f.runtime.importController.retryStorage()
        f.runtime.importController.prompt.first { it == null }
        assertEquals(ImportOfferRecord("a", false, true), f.koin.get<ImportOfferStore>().offer("a"))
        assertEquals(0, f.calls.count { it.method != HttpMethod.Get })
    } }

    @Test fun missingWebLocksFailsTheBoundImportVisiblyAndRetryWorksAfterRecovery() = runTest { fixture { f ->
        f.koin.get<GuestLibrarySource>().write(title(7), LibraryKind.WATCHLIST, LibraryState(true, true, false, null, emptySet()))
        f.start()
        f.runtime.importController.prompt.first { it is ImportPrompt.Ask }
        val unavailable = disableLocks()
        try {
            f.runtime.importController.import()
            f.runtime.importController.prompt.first { it == ImportPrompt.Failed }
            assertEquals(BrowserStorageFailure.Unsupported, f.runtime.storageFailure.value)
            assertEquals(0, f.calls.count { it.method != HttpMethod.Get })
        } finally { restore(unavailable) }
        f.runtime.importController.retryStorage()
        f.runtime.importController.prompt.first { it == null }
        assertEquals(1, f.calls.count { it.method == HttpMethod.Post })
    } }

    @Test fun closingCancelsInflightHttpBeforeClosingDatabaseAndBusAndLeavesOtherGraphAlive() = runTest {
        val a = Fixture(); val b = Fixture()
        try {
            a.gatePath = "/api/session"; a.gate = CompletableDeferred()
            var canceled = false
            a.onCancel = { canceled = true }
            a.runtime.start(); until { a.count("/api/session") == 1 }
            b.start()
            a.runtime.close()
            assertTrue(canceled); assertFalse(a.scope.isActive)
            val revision = a.bus.changes.value
            focus(); tick().await()
            assertEquals(revision, a.bus.changes.value)
            assertFailsWith<BrowserStorageException> { a.settings.current() }
            assertTrue(b.scope.isActive)
            b.settings.update { it.copy(language = "ml-IN") }
            assertEquals("ml-IN", b.settings.current().language)
        } finally { a.gate?.complete(Unit); a.close(); b.close() }
    }
}

private fun AuthSession?.json(): String = if (this == null)
    """{"signedIn":false,"sessionGeneration":"0","accountObjectId":null,"displayName":null}"""
else """{"signedIn":true,"sessionGeneration":"$sessionGeneration","accountObjectId":"${accountObjectId.value}","displayName":"$displayName"}"""

private suspend fun until(condition: () -> Boolean) {
    repeat(120) { if (condition()) return; tick().await() }
    error("C4 integration condition did not settle")
}

@JsFun("() => crypto.randomUUID()")
private external fun uniqueId(): String
@JsFun("() => new Promise(resolve => setTimeout(() => resolve(null), 20))")
private external fun tick(): Promise<JsAny?>
@JsFun("name => new Promise((resolve, reject) => { const r = indexedDB.deleteDatabase(name); r.onsuccess = () => resolve(null); r.onerror = () => reject(r.error); r.onblocked = () => reject(new Error('isolated cleanup blocked')); })")
private external fun deleteDatabase(name: String): Promise<JsAny?>
@JsFun("() => { const f = document.createElement('iframe'); document.body.appendChild(f); return f; }")
private external fun realm(): JsAny
@JsFun("f => f.remove()")
private external fun removeRealm(f: JsAny)
@JsFun("() => window.dispatchEvent(new Event('focus'))")
private external fun focus()
@JsFun("() => { Object.defineProperty(document, 'visibilityState', {configurable:true,value:'visible'}); document.dispatchEvent(new Event('visibilitychange')); delete document.visibilityState; }")
private external fun visibility()
@JsFun("state => state.restore()")
private external fun restore(state: JsAny)
@JsFun("held => held.release()")
private external fun releaseLock(held: JsAny)
@JsFun("(f, name) => new f.contentWindow.Function('name', `return new Promise((resolve,reject) => navigator.locks.request(name, () => new Promise(release => resolve({release}))).catch(reject));`)(name)")
private external fun holdLock(f: JsAny, name: String): Promise<JsAny>
@JsFun("(f, channel, topic) => new f.contentWindow.Function('channel','topic', `const bus = new BroadcastChannel(channel); bus.postMessage({topic,revision:crypto.randomUUID()}); bus.close(); return Promise.resolve(null);`)(channel,topic)")
private external fun peerHint(f: JsAny, channel: String, topic: String): Promise<JsAny?>
@JsFun("""(f, name, store, key, value, channel, topic) => new f.contentWindow.Function('name','store','key','value','channel','topic', `return new Promise((resolve,reject) => {
    const r = indexedDB.open(name,1); r.onerror = () => reject(r.error); r.onsuccess = () => {
        const db = r.result, tx = db.transaction(store,'readwrite'); tx.objectStore(store).put(value,key);
        tx.onabort = () => { db.close(); reject(tx.error); }; tx.oncomplete = () => {
            db.close(); const bus = new BroadcastChannel(channel); bus.postMessage({topic,revision:crypto.randomUUID()}); bus.close(); resolve(null);
        };
    };
});`)(name,store,key,value,channel,topic)""")
private external fun peerWrite(f: JsAny, name: String, store: String, key: String, value: String, channel: String, topic: String): Promise<JsAny?>
@JsFun("""(name, store) => {
    const old = IDBDatabase.prototype.transaction;
    IDBDatabase.prototype.transaction = function(...args) { if (this.name === name && args[0] === store) throw new DOMException('fixture','QuotaExceededError'); return old.apply(this,args); };
    return {restore:() => { IDBDatabase.prototype.transaction = old; }};
}""")
private external fun failTransactions(name: String, store: String): JsAny
@JsFun("""name => {
    const old = IDBFactory.prototype.open;
    IDBFactory.prototype.open = function(...args) { if (args[0] !== name) return old.apply(this,args); const request = {}; setTimeout(() => request.onblocked(),0); return request; };
    return {restore:() => { IDBFactory.prototype.open = old; }};
}""")
private external fun failOpen(name: String): JsAny
@JsFun("""() => {
    const old = Object.getOwnPropertyDescriptor(navigator,'locks'); Object.defineProperty(navigator,'locks',{configurable:true,value:undefined});
    return {restore:() => { if (old) Object.defineProperty(navigator,'locks',old); else delete navigator.locks; }};
}""")
private external fun disableLocks(): JsAny
