package com.techgv.cineverse.di

import com.techgv.cineverse.data.tmdb.ReadToken
import org.koin.core.context.startKoin

/** iOS entry: call once from the Swift `App` initializer (Swift: `InitKoin_iosKt.doInitKoin(nativeReadToken:)`). */
fun initKoin(nativeReadToken: String?) {
    startKoin { modules(sharedModules(NativeConfig(ReadToken(nativeReadToken.orEmpty()))) + mobileModule()) }
}
