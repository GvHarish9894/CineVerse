package com.techgv.cineverse.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.techgv.cineverse.data.auth.AndroidKeystoreKeys
import com.techgv.cineverse.data.auth.CredentialCipher
import com.techgv.cineverse.data.auth.DataStoreCredentialStore
import com.techgv.cineverse.data.auth.TokenCipher
import com.techgv.cineverse.data.library.LibraryDatabase
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.module.Module
import org.koin.dsl.module

// TV v1 files `files/datastore/{settings,credentials}.preferences_pb`; the delegates keep one DataStore per file per
// process, even when a test builds a second Koin graph.
private val Context.settingsDataStore by preferencesDataStore(name = "settings")
private val Context.credentialDataStore by preferencesDataStore(name = DataStoreCredentialStore.FILE_NAME)

/** Needs the application `Context`, declared by the entry point (`single<Context> { application }`). */
internal actual fun nativeStorageModule(): Module = module {
    single<HttpClientEngine> { OkHttp.create() }
    single<TokenCipher> { CredentialCipher(AndroidKeystoreKeys()) }
    single(SettingsDataStore) { get<Context>().settingsDataStore }
    single(CredentialsDataStore) { get<Context>().credentialDataStore }
    single { Room.databaseBuilder(get<Context>(), LibraryDatabase::class.java, "library.db").build() }
}
