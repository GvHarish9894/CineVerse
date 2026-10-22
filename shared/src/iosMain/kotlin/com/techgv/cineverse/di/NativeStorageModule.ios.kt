package com.techgv.cineverse.di

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.techgv.cineverse.data.auth.KeychainTokenCipher
import com.techgv.cineverse.data.auth.TokenCipher
import com.techgv.cineverse.data.library.LibraryDatabase
import com.techgv.cineverse.data.library.LibraryDatabaseConstructor
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

internal actual fun nativeStorageModule(): Module = module {
    single<HttpClientEngine> { Darwin.create() }
    single<TokenCipher> { KeychainTokenCipher() }
    dataStoreFiles { applicationSupport() }
    single {
        Room.databaseBuilder<LibraryDatabase>(
            name = (applicationSupport() / "library.db").toString(),
            factory = { LibraryDatabaseConstructor.initialize() },
        ).setDriver(BundledSQLiteDriver()).build()
    }
}

/** The app's Application Support directory (created on first use): app data, not visible in Files. */
@OptIn(ExperimentalForeignApi::class)
private fun applicationSupport(): Path = NSFileManager.defaultManager.URLForDirectory(
    directory = NSApplicationSupportDirectory, inDomain = NSUserDomainMask, appropriateForURL = null, create = true, error = null,
)!!.path!!.toPath()
