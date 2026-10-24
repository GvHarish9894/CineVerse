package com.techgv.cineverse.di

import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.settings.*
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.data.web.*
import com.techgv.cineverse.presentation.ImportLibraryStateHolder
import com.techgv.cineverse.presentation.SettingsStateHolder
import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import org.koin.dsl.onClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import com.techgv.cineverse.data.auth.AuthSession

/** The browser factory is fixed same-origin /api; other deployments are not supported. Never a token. */
class WebConfig(val proxyBaseUrl: String) {
    init { require(proxyBaseUrl == "/api") { "Browser proxy must be same-origin /api" } }
}

/** Fresh modules and graph-owned objects on every call, including isolated test graphs. */
fun sharedModules(config: WebConfig): List<Module> = listOf(
    module { single { config } }, coreModule(), libraryModule { get<ImportOperationGuard>() }, platformModule(),
)

actual fun platformModule(): Module = module {
    single { BrowserDatabase() }
    single { BrowserChanges() }
    single { BrowserImportLock(get()) }
    single<ImportOperationGuard> {
        val lock = get<BrowserImportLock>()
        ImportOperationGuard { operation -> lock.withLock(operation) }
    }
    single<SettingsStore> { BrowserSettingsStore(database = get(), changes = get()) }
    single<GuestLibraryStore> { BrowserGuestLibraryStore(get(), get()) }
    single { BrowserImportStore(get()) } binds arrayOf(ImportLedgerStore::class, ImportOfferStore::class)
    single<Transport.Proxy> {
        val base = get<WebConfig>().proxyBaseUrl
        object : Transport.Proxy { override val baseUrl = base }
    }
    single<HttpClient> { browserProxyHttpClient() }
    single { WebProxyHttp(get(), ::browserCsrfCookie) }
    single { WebAuthRepository(get()) }
    single { BrowserAuthRepository(get(), get()) } bind AuthRepository::class
    single<TmdbApi> { ProxyTmdbApi(get()) }
    single { TmdbCache(get()) }
    single { CatalogRepository(get(), get(), get<SettingsRepository>()::current, cache = get()) }
    single { WebAccountLibrarySource(get(), get(), get<SettingsRepository>()::current) }
    single { BrowserAccountLibrarySource(get(), get(), get()) } bind AccountLibrarySource::class
    single { WebRuntime(get(AppScope), get(), get(), get(), get(), get(), get(), get(), get()) } onClose { it?.stop() }
    single<SettingsStateHolder> { get<WebRuntime>().settingsHolder }
    single<ImportLibraryStateHolder> { get<WebRuntime>().importController }
}

/** Only local mutation outcomes publish. Explicit/incoming invalidations never echo to peers. */
internal class BrowserAccountLibrarySource(
    private val source: WebAccountLibrarySource,
    private val changes: BrowserChanges,
    private val auth: BrowserAuthRepository,
) : AccountLibrarySource by source {
    override suspend fun page(session: AuthSession, kind: com.techgv.cineverse.domain.LibraryKind,
                              media: com.techgv.cineverse.domain.MediaType, page: Int): LibraryPage =
        observeVerification { source.page(session, kind, media, page) }

    override suspend fun states(session: AuthSession, ref: com.techgv.cineverse.domain.MediaRef) =
        observeVerification { source.states(session, ref) }

    private suspend fun <T> observeVerification(operation: suspend () -> T): T = try { operation() }
    catch (failure: WebProxyException) {
        if (failure.code in setOf("stale_session", "session_expired", "not_signed_in")) auth.requestVerification()
        throw failure
    }
    override suspend fun write(session: com.techgv.cineverse.data.auth.AuthSession, ref: com.techgv.cineverse.domain.MediaRef,
                               kind: com.techgv.cineverse.domain.LibraryKind, target: com.techgv.cineverse.domain.LibraryState) {
        try { observeVerification { source.write(session, ref, kind, target) } }
        finally { changes.publish(BrowserChangeTopic.Library) } // Includes possibly-landed and canceled writes.
    }
}

internal class BrowserAuthRepository(
    val web: WebAuthRepository,
    private val changes: BrowserChanges,
) : AuthRepository by web {
    override val session: StateFlow<AuthSession?> get() = web.session
    private val verificationRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val verification = verificationRequests.asSharedFlow()
    fun requestVerification() {
        val current = web.session.value ?: return
        // A dropped A result is not evidence against an already verified B (or a library-only invalidation).
        try { web.capture(current); return } catch (_: WebProxyException) { }
        verificationRequests.tryEmit(Unit)
    }
    override suspend fun signOut(onSignedOutLocally: () -> Unit) {
        try { web.signOut(onSignedOutLocally) }
        finally { changes.publish(BrowserChangeTopic.Session) }
    }
}
