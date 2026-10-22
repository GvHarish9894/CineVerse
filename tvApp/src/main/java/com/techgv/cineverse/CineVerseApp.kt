package com.techgv.cineverse

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.DataStoreCredentialStore
import com.techgv.cineverse.data.auth.AndroidKeystoreKeys
import com.techgv.cineverse.data.auth.CredentialCipher
import com.techgv.cineverse.data.library.AccountLibrarySource
import com.techgv.cineverse.data.library.GuestLibrarySource
import com.techgv.cineverse.data.library.LibraryDatabase
import com.techgv.cineverse.data.library.LibraryImporter
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.ReadToken
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TmdbClient
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.settingsDataStore by preferencesDataStore(name = "settings")
private val Context.credentialDataStore by preferencesDataStore(name = DataStoreCredentialStore.FILE_NAME)

/** Manual DI (§1). Coil uses its own default OkHttpClient, which never carries the TMDB bearer (§1.5). */
class AppContainer(
    context: Context,
    private val tmdbApi: TmdbApi = TmdbClient.create(ReadToken(BuildConfig.TMDB_READ_ACCESS_TOKEN)),
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** App-wide generation (§1.1): bumped on auth and content-settings changes. */
    val appGeneration = GenerationCounter()

    val settingsRepository = SettingsRepository(context.settingsDataStore)

    val tmdbRepository: TmdbRepository = TmdbRepository(
        tmdbApi, appGeneration, settings = settingsRepository::current,
        onSessionExpired = { authRepository.expireSession(it) },
    )

    val authRepository: AuthRepository = AuthRepository(
        api = tmdbApi,
        store = DataStoreCredentialStore(context.credentialDataStore, CredentialCipher(AndroidKeystoreKeys())),
        onAccountChanged = tmdbRepository::onAccountChanged,
    )

    private val libraryDatabase = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db").build()
    private val guestLibrary = GuestLibrarySource(libraryDatabase.libraryDao())
    private val accountLibrary = AccountLibrarySource(tmdbRepository)

    val libraryRepository = LibraryRepository(authRepository.credentials, guestLibrary, accountLibrary)

    val libraryImporter = LibraryImporter(libraryDatabase.importLedgerDao(), guestLibrary, accountLibrary, libraryRepository)

    init {
        appScope.launch {
            settingsRepository.settings
                .map { Triple(it.language, it.region, it.includeAdult) }
                .distinctUntilChanged()
                .drop(1)
                .collect { tmdbRepository.onContentSettingsChanged() }
        }
    }
}

class CineVerseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as CineVerseApp).container
