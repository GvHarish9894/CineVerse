package com.techgv.cineverse

import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.CredentialCipher
import com.techgv.cineverse.data.auth.InMemoryPreferencesStore
import com.techgv.cineverse.data.auth.NativeAuthRepository
import com.techgv.cineverse.data.auth.SoftwareKeys
import com.techgv.cineverse.data.auth.TokenCipher
import com.techgv.cineverse.data.library.FakeImportLedgerDao
import com.techgv.cineverse.data.library.FakeLibraryDao
import com.techgv.cineverse.data.library.GuestLibraryStore
import com.techgv.cineverse.data.library.ImportLedgerStore
import com.techgv.cineverse.data.library.ImportOfferStore
import com.techgv.cineverse.data.library.LibraryImporter
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.library.RoomGuestLibraryStore
import com.techgv.cineverse.data.library.RoomImportStore
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.data.tmdb.unusedTmdbApi
import com.techgv.cineverse.di.AppScope
import com.techgv.cineverse.di.CredentialsDataStore
import com.techgv.cineverse.di.SettingsDataStore
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.assertSame
import org.junit.Test
import org.koin.dsl.binds
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** The facade hands out exactly the singletons Koin holds (the Context-backed storage leaves are faked on the JVM). */
class AppContainerTest {
    @Test
    fun facadePropertiesAreTheKoinSingletons() {
        val leaves = module {
            single<TmdbApi> { unusedTmdbApi }
            single<TokenCipher> { CredentialCipher(SoftwareKeys()) }
            single<DataStore<Preferences>>(SettingsDataStore) { InMemoryPreferencesStore() }
            single<DataStore<Preferences>>(CredentialsDataStore) { InMemoryPreferencesStore() }
            single<GuestLibraryStore> { RoomGuestLibraryStore(FakeLibraryDao()) }
            single { RoomImportStore(FakeImportLedgerDao()) } binds arrayOf(ImportLedgerStore::class, ImportOfferStore::class)
        }
        val koin = koinApplication { modules(tvModules(ContextWrapper(null)) + leaves) }.koin
        val container = AppContainer(koin)
        try {
            assertSame(koin.get<CoroutineScope>(AppScope), container.appScope)
            assertSame(koin.get<GenerationCounter>(), container.appGeneration)
            assertSame(koin.get<SettingsRepository>(), container.settingsRepository)
            assertSame(koin.get<TmdbRepository>(), container.tmdbRepository)
            assertSame(koin.get<NativeAuthRepository>(), container.authRepository)
            assertSame(koin.get<AuthRepository>(), container.authRepository)
            assertSame(koin.get<LibraryRepository>(), container.libraryRepository)
            assertSame(koin.get<LibraryImporter>(), container.libraryImporter)
            assertSame(koin.get<CatalogRepository>(), container.tmdbRepository.catalog)
        } finally {
            container.appScope.cancel()
            koin.close()
        }
    }
}
