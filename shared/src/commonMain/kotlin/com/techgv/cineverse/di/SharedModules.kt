package com.techgv.cineverse.di

import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.library.GuestLibrarySource
import com.techgv.cineverse.data.library.ImportOperationGuard
import com.techgv.cineverse.data.library.LibraryImporter
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.scope.Scope
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

// Koin wires the composition roots only (plan K4): logic classes keep plain constructors and never call Koin.
// Modules are functions, not vals: a Module object holds its singletons, so one loaded into two Koin graphs (e.g. a
// test's isolated graph next to the app's) would share them, and closing either graph would drop them for both.

/** The app-lifetime [CoroutineScope]. */
val AppScope = named("appScope")

/** Transport-agnostic singletons; [SettingsRepository] reads the platform's `SettingsStore`. */
fun coreModule(): Module = module {
    /** App-wide generation (§1.1): bumped on auth and content-settings changes. */
    single { GenerationCounter() }
    single(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { SettingsRepository(get()) }
}

/** Guest / account library over the platform's storage contracts and `AccountLibrarySource`. */
fun libraryModule(importGuard: Scope.() -> ImportOperationGuard = { ImportOperationGuard { it() } }): Module = module {
    single { GuestLibrarySource(get()) }
    single { LibraryRepository(get<AuthRepository>().session, get(), get()) }
    single { LibraryImporter(get(), get(), get(), get(), get(), importGuard()) }
}

/** Storage, transport and auth of this platform; the entry point's config is declared by `sharedModules`. */
expect fun platformModule(): Module
