package com.techgv.cineverse

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.CredentialStore
import com.techgv.cineverse.data.auth.AndroidKeystoreKeys
import com.techgv.cineverse.data.auth.CredentialCipher
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.ReadToken
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
private val Context.credentialDataStore by preferencesDataStore(name = CredentialStore.FILE_NAME)

/** Manual DI (§1). Coil uses its own default OkHttpClient, which never carries the TMDB bearer (§1.5). */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** App-wide generation (§1.1): bumped on auth and content-settings changes. */
    val appGeneration = GenerationCounter()

    val settingsRepository = SettingsRepository(context.settingsDataStore)

    private val tmdbApi = TmdbClient.create(ReadToken(BuildConfig.TMDB_READ_ACCESS_TOKEN))

    val tmdbRepository = TmdbRepository(tmdbApi, appGeneration, settings = settingsRepository::current)

    val authRepository = AuthRepository(
        api = tmdbApi,
        store = CredentialStore(context.credentialDataStore, CredentialCipher(AndroidKeystoreKeys())),
        onAccountChanged = tmdbRepository::onAccountChanged,
    )

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
