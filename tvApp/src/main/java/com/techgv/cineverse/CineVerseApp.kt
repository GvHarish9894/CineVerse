package com.techgv.cineverse

import android.app.Application
import android.content.Context
import com.techgv.cineverse.data.auth.NativeAuthRepository
import com.techgv.cineverse.data.library.LibraryImporter
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.ReadToken
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.data.tmdb.installDebugHttpLogging
import com.techgv.cineverse.di.AppScope
import com.techgv.cineverse.di.NativeConfig
import com.techgv.cineverse.di.sharedModules
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** TV-only definitions: the application [Context] the shared Android storage needs. */
fun tvModule(context: Context): Module = module { single<Context> { context } }

/** The TV's whole graph; the app and the test constructor of [AppContainer] both start from this list. */
fun tvModules(context: Context): List<Module> =
    sharedModules(NativeConfig(ReadToken(BuildConfig.TMDB_READ_ACCESS_TOKEN)) { installDebugHttpLogging() }) + tvModule(context)

/**
 * Facade over Koin (plan K4) with the properties screens, VM factories and spikes read. Coil uses its own default
 * OkHttpClient, which never carries the TMDB bearer (§1.5).
 */
class AppContainer(val koin: Koin) {
    /** Instrumented tests: an isolated graph (no `startKoin`) whose TMDB client is [tmdbApi]. */
    constructor(context: Context, tmdbApi: TmdbApi) :
        this(koinApplication { modules(tvModules(context) + module { single { tmdbApi } }) }.koin)

    val appScope: CoroutineScope = koin.get(AppScope)

    /** App-wide generation (§1.1): bumped on auth and content-settings changes. */
    val appGeneration: GenerationCounter = koin.get()

    val settingsRepository: SettingsRepository = koin.get()

    val tmdbRepository: TmdbRepository = koin.get()

    val authRepository: NativeAuthRepository = koin.get()

    val libraryRepository: LibraryRepository = koin.get()

    val libraryImporter: LibraryImporter = koin.get()

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
        container = AppContainer(startKoin { modules(tvModules(this@CineVerseApp)) }.koin)
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as CineVerseApp).container
