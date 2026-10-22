package com.techgv.cineverse.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.techgv.cineverse.data.auth.InMemoryPreferencesStore
import com.techgv.cineverse.data.library.FakeImportLedgerDao
import com.techgv.cineverse.data.library.FakeLibraryDao
import com.techgv.cineverse.data.library.GuestLibraryStore
import com.techgv.cineverse.data.library.ImportLedgerStore
import com.techgv.cineverse.data.library.ImportOfferStore
import com.techgv.cineverse.data.library.LibraryDatabase
import com.techgv.cineverse.data.library.RoomGuestLibraryStore
import com.techgv.cineverse.data.library.RoomImportStore
import org.koin.dsl.binds
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test

/**
 * The real Android graph, except the leaves that need an application `Context` (DataStore files, `library.db`): the
 * host JVM has none. tvApp's instrumented `KoinGraphTest` resolves those too, on a device.
 */
class AndroidSharedModulesTest {
    @Test
    fun everyDefinitionResolvesWithTheAndroidPlatformModule() {
        val contextLeaves = module {
            single<DataStore<Preferences>>(SettingsDataStore) { InMemoryPreferencesStore() }
            single<DataStore<Preferences>>(CredentialsDataStore) { InMemoryPreferencesStore() }
            single<GuestLibraryStore> { RoomGuestLibraryStore(FakeLibraryDao()) }
            single { RoomImportStore(FakeImportLedgerDao()) } binds arrayOf(ImportLedgerStore::class, ImportOfferStore::class)
        }
        val app = koinApplication { modules(sharedModules(testNativeConfig) + contextLeaves) }
        try {
            app.koin.assertNativeGraph(skip = setOf(LibraryDatabase::class))
        } finally {
            app.close()
        }
    }
}
