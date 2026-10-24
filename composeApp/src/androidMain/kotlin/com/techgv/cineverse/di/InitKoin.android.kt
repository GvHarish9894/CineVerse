package com.techgv.cineverse.di

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.techgv.cineverse.auth.AndroidCustomTabsOpener
import com.techgv.cineverse.auth.CustomTabsApprovalFlow
import com.techgv.cineverse.data.auth.ApprovalFlow
import com.techgv.cineverse.data.auth.DataStorePendingAttemptStore
import com.techgv.cineverse.data.auth.PendingAttemptStore
import com.techgv.cineverse.data.tmdb.ReadToken
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.dsl.onClose

private val Context.pendingApprovalDataStore by preferencesDataStore(
    name = DataStorePendingAttemptStore.FILE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Android phone entry: call once from `Application.onCreate`. */
fun initKoin(application: Context, nativeReadToken: String) {
    startKoin {
        modules(sharedModules(NativeConfig(ReadToken(nativeReadToken))) + module {
            single<Context> { application.applicationContext }
            single<PendingAttemptStore> { DataStorePendingAttemptStore(get<Context>().pendingApprovalDataStore, get()) }
            single { AndroidCustomTabsOpener() }
            single { CustomTabsApprovalFlow(get(), get(), get<AndroidCustomTabsOpener>()) } onClose { it?.coordinator?.close() }
            single<ApprovalFlow> { get<CustomTabsApprovalFlow>() }
            single { get<CustomTabsApprovalFlow>().coordinator }
        } + mobileModule())
    }
}
