package com.techgv.cineverse.di

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.techgv.cineverse.data.auth.TokenCipher
import com.techgv.cineverse.data.library.LibraryDatabase
import com.techgv.cineverse.data.library.LibraryDatabaseConstructor
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import okio.Path.Companion.toOkioPath
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File

/** jvm (tests, tooling) has no platform key store or app directory: the caller declares `single { JvmStorage(…) }`. */
class JvmStorage(val directory: File, val cipher: TokenCipher)

internal actual fun nativeStorageModule(): Module = module {
    single<HttpClientEngine> { Java.create() }
    // Created at start: a graph without JvmStorage fails when Koin starts, not at the first storage read.
    single<TokenCipher>(createdAtStart = true) { get<JvmStorage>().cipher }
    dataStoreFiles { get<JvmStorage>().directory.toOkioPath() }
    single {
        Room.databaseBuilder<LibraryDatabase>(
            name = File(get<JvmStorage>().directory, "library.db").path,
            factory = { LibraryDatabaseConstructor.initialize() },
        ).setDriver(BundledSQLiteDriver()).build()
    }
}
