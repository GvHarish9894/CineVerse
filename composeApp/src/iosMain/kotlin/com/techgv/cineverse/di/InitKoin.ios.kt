@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.techgv.cineverse.di

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import com.techgv.cineverse.auth.IosWebAuthenticationSessionOpener
import com.techgv.cineverse.auth.WebAuthenticationSessionApprovalFlow
import com.techgv.cineverse.data.auth.ApprovalFlow
import com.techgv.cineverse.data.auth.DataStorePendingAttemptStore
import com.techgv.cineverse.data.auth.PendingAttemptStore
import com.techgv.cineverse.data.tmdb.ReadToken
import okio.Path.Companion.toPath
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.dsl.onClose
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

// One instance per file/process, including when a test constructs a second composition root.
private val pendingApprovalDataStore by lazy {
    val manager = NSFileManager.defaultManager
    val support = checkNotNull(manager.URLForDirectory(
        NSApplicationSupportDirectory, NSUserDomainMask, null, true, null,
    ))
    val directory = checkNotNull(support.URLByAppendingPathComponent("native_approval", true))
    check(manager.createDirectoryAtURL(directory, true, null, null)) { "Cannot create pending approval directory" }
    check(directory.setResourceValue(NSNumber(bool = true), NSURLIsExcludedFromBackupKey, null)) {
        "Cannot exclude pending approval from backup"
    }
    PreferenceDataStoreFactory.createWithPath(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    ) { checkNotNull(directory.path).toPath() / "${DataStorePendingAttemptStore.FILE_NAME}.preferences_pb" }
}

/** iOS entry: call once from the Swift App initializer. */
fun initKoin(nativeReadToken: String?) {
    startKoin {
        modules(sharedModules(NativeConfig(ReadToken(nativeReadToken.orEmpty()))) + mobileModule() + module {
            single<PendingAttemptStore> { DataStorePendingAttemptStore(pendingApprovalDataStore, get()) }
            single { IosWebAuthenticationSessionOpener() }
            single { WebAuthenticationSessionApprovalFlow(get(), get(), get<IosWebAuthenticationSessionOpener>()) } onClose { it?.coordinator?.close() }
            single<ApprovalFlow> { get<WebAuthenticationSessionApprovalFlow>() }
            single { get<WebAuthenticationSessionApprovalFlow>().coordinator }
        })
    }
}
