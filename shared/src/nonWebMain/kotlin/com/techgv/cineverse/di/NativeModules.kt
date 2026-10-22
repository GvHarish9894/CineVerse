package com.techgv.cineverse.di

import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.CredentialStore
import com.techgv.cineverse.data.auth.DataStoreCredentialStore
import com.techgv.cineverse.data.auth.NativeAuthRepository
import com.techgv.cineverse.data.library.AccountLibrarySource
import com.techgv.cineverse.data.library.GuestLibraryStore
import com.techgv.cineverse.data.library.ImportLedgerStore
import com.techgv.cineverse.data.library.ImportOfferStore
import com.techgv.cineverse.data.library.LibraryDatabase
import com.techgv.cineverse.data.library.RoomGuestLibraryStore
import com.techgv.cineverse.data.library.RoomImportStore
import com.techgv.cineverse.data.library.TmdbAccountLibrarySource
import com.techgv.cineverse.data.settings.DataStoreSettingsStore
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.settings.SettingsStore
import com.techgv.cineverse.data.tmdb.KtorTmdbApi
import com.techgv.cineverse.data.tmdb.ReadToken
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.data.tmdb.Transport
import com.techgv.cineverse.data.tmdb.tmdbHttpClient
import io.ktor.client.HttpClientConfig
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

/** What a native entry point (TV, Android phone, iOS) supplies: its read token, plus e.g. debug HTTP logging. */
class NativeConfig(val readToken: ReadToken, val configureHttp: HttpClientConfig<*>.() -> Unit = {})

/** The Preferences DataStore files (TV v1 names: `settings`, `credentials`), provided per target. */
val SettingsDataStore = named("settings")
val CredentialsDataStore = named("credentials")

/** Every shared module a native entry point starts Koin with. */
fun sharedModules(config: NativeConfig): List<Module> =
    listOf(module { single { config } }, coreModule(), libraryModule(), platformModule())

actual fun platformModule(): Module = module {
    includes(nativeStorageModule())
    single<TmdbApi> {
        val config = get<NativeConfig>()
        KtorTmdbApi(tmdbHttpClient(get(), Transport.Direct(config.readToken), config.configureHttp))
    }
    single {
        val scope = this
        TmdbRepository(
            get(), get(), settings = get<SettingsRepository>()::current,
            onSessionExpired = { scope.get<NativeAuthRepository>().expireSession(it) },
        )
    }
    // Views of the facade: one TMDB cache.
    single { get<TmdbRepository>().catalog }
    single { get<TmdbRepository>().account }
    single { NativeAuthRepository(api = get(), store = get(), onAccountChanged = get<TmdbRepository>()::onAccountChanged) } bind
        AuthRepository::class
    single<SettingsStore> { DataStoreSettingsStore(get(SettingsDataStore)) }
    single<CredentialStore> { DataStoreCredentialStore(get(CredentialsDataStore), get()) }
    single<AccountLibrarySource> { TmdbAccountLibrarySource(get(), get<NativeAuthRepository>()::credentialsFor) }
    single<GuestLibraryStore> { RoomGuestLibraryStore(get<LibraryDatabase>().libraryDao()) }
    single { RoomImportStore(get<LibraryDatabase>().importLedgerDao()) } binds arrayOf(ImportLedgerStore::class, ImportOfferStore::class)
}

/**
 * Per target: the Ktor `HttpClientEngine`, the `TokenCipher`, both Preferences DataStores ([SettingsDataStore],
 * [CredentialsDataStore]) and the `LibraryDatabase` (`library.db`).
 */
internal expect fun nativeStorageModule(): Module
