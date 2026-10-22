package com.techgv.cineverse.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.techgv.cineverse.AppContainer
import com.techgv.cineverse.CineVerseApp
import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.library.LibraryDatabase
import com.techgv.cineverse.data.library.LibraryImporter
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.Koin
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.GlobalContext
import java.io.File
import java.lang.reflect.Proxy

/** The TV's real Koin graph on a device: every definition resolves, the facade is that graph, TV v1 files are kept. */
@OptIn(KoinInternalApi::class)
@RunWith(AndroidJUnit4::class)
class KoinGraphTest {
    private val app = ApplicationProvider.getApplicationContext<CineVerseApp>()
    private val koin = GlobalContext.get()

    @Test
    fun everyDefinitionOfTheAppGraphResolves() {
        val definitions = koin.instanceRegistry.instances.values.map { it.beanDefinition }.distinct()
        definitions.forEach { koin.get<Any>(it.primaryType, it.qualifier) }
        assertTrue(definitions.size >= 20)
    }

    @Test
    fun theFacadeReadsTheStartedGraph() {
        val container = app.container
        assertSame(koin, container.koin)
        assertSame(koin.get<CoroutineScope>(AppScope), container.appScope)
        assertSame(koin.get<GenerationCounter>(), container.appGeneration)
        assertSame(koin.get<SettingsRepository>(), container.settingsRepository)
        assertSame(koin.get<TmdbRepository>(), container.tmdbRepository)
        assertSame(koin.get<AuthRepository>(), container.authRepository)
        assertSame(koin.get<LibraryRepository>(), container.libraryRepository)
        assertSame(koin.get<LibraryImporter>(), container.libraryImporter)
    }

    @Test
    fun theTestConstructorBuildsTheSameDefinitionsWithItsOwnTmdbApi() {
        val api = Proxy.newProxyInstance(TmdbApi::class.java.classLoader, arrayOf(TmdbApi::class.java)) { _, _, _ ->
            throw UnsupportedOperationException()
        } as TmdbApi
        val isolated = AppContainer(app, api)
        try {
            assertEquals(koin.definitionKeys(), isolated.koin.definitionKeys())
            assertSame(api, isolated.koin.get<TmdbApi>())
            assertNotSame(koin.get<TmdbRepository>(), isolated.tmdbRepository)
            assertNotSame(app.container.appScope, isolated.appScope)
            // Both graphs share the process-wide DataStore of each file (two DataStores on one file would throw).
            assertSame(koin.get<DataStore<Preferences>>(SettingsDataStore), isolated.koin.get<DataStore<Preferences>>(SettingsDataStore))
        } finally {
            isolated.appScope.cancel()
            isolated.koin.get<LibraryDatabase>().close()
            isolated.koin.close()
        }
        // Closing the isolated graph leaves the app's singletons alone.
        assertTrue(app.container.appScope.isActive)
        assertSame(app.container.appScope, koin.get<CoroutineScope>(AppScope))
    }

    @Test
    fun storageKeepsTheTvV1FilePaths() = runBlocking {
        assertWritesTo(koin.get(SettingsDataStore), File(app.filesDir, "datastore/settings.preferences_pb"))
        assertWritesTo(koin.get(CredentialsDataStore), File(app.filesDir, "datastore/credentials.preferences_pb"))
        assertEquals(app.getDatabasePath("library.db").path, koin.get<LibraryDatabase>().openHelper.readableDatabase.path)
    }

    private suspend fun assertWritesTo(store: DataStore<Preferences>, file: File) {
        val probe = stringPreferencesKey("p5_path_probe")
        store.edit { it[probe] = "x" }
        try {
            assertTrue("$file has the probe key", file.readBytes().toString(Charsets.ISO_8859_1).contains(probe.name))
        } finally {
            store.edit { it.remove(probe) }
        }
    }

    private fun Koin.definitionKeys() = instanceRegistry.instances.keys
}
