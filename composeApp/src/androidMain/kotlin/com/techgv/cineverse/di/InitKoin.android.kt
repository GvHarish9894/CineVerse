package com.techgv.cineverse.di

import android.content.Context
import com.techgv.cineverse.data.tmdb.ReadToken
import org.koin.core.context.startKoin
import org.koin.dsl.module

/** Android phone entry: call once from `Application.onCreate`. */
fun initKoin(application: Context, nativeReadToken: String) {
    startKoin {
        modules(sharedModules(NativeConfig(ReadToken(nativeReadToken))) + module { single<Context> { application } } + mobileModule())
    }
}
