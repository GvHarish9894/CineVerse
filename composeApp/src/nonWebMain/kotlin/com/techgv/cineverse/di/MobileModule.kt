package com.techgv.cineverse.di

import com.techgv.cineverse.data.search.RecentQueryStore
import com.techgv.cineverse.ui.mobile.MobileApplication
import com.techgv.cineverse.ui.mobile.MobileExternalActions
import com.techgv.cineverse.ui.mobile.mobileExternalActions
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.koin.mp.KoinPlatform

/** Eager app-lifetime settings/cache observer; entrypoints only retrieve this graph. */
fun nativeMobileApplication(): MobileApplication = KoinPlatform.getKoin().get()

fun nativeMobileExternalActions(): MobileExternalActions = KoinPlatform.getKoin().get()

fun mobileModule(): Module = module {
    single(createdAtStart = true) { MobileApplication(get(), get(), get(), get(), get(), RecentQueryStore(get()), get(AppScope)) } onClose { it?.close() }
    single { mobileExternalActions() }
}
