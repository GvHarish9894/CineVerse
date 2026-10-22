package com.techgv.cineverse.di

import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.NativeAuthRepository
import com.techgv.cineverse.data.library.ImportLedgerStore
import com.techgv.cineverse.data.library.ImportOfferStore
import com.techgv.cineverse.data.library.LibraryImporter
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.AccountRepository
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.ReadToken
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import org.koin.core.Koin
import kotlin.reflect.KClass
import kotlin.test.assertSame
import kotlin.test.assertTrue

val testNativeConfig = NativeConfig(ReadToken("test-read-token"))

/** Every definition of the native graph resolves, and the views / bindings are the same singletons. */
fun Koin.assertNativeGraph(skip: Set<KClass<*>> = emptySet()) {
    val resolved = resolveEveryDefinition(skip)
    val expected = listOf(
        GenerationCounter::class, CoroutineScope::class, SettingsRepository::class, TmdbRepository::class,
        NativeAuthRepository::class, LibraryRepository::class, LibraryImporter::class,
    )
    assertTrue(resolved.containsAll(expected), "missing ${expected - resolved.toSet()}")
    val tmdb = get<TmdbRepository>()
    assertSame(tmdb.catalog, get<CatalogRepository>())
    assertSame(tmdb.account, get<AccountRepository>())
    assertSame<AuthRepository>(get<NativeAuthRepository>(), get<AuthRepository>())
    assertSame<Any>(get<ImportLedgerStore>(), get<ImportOfferStore>())
}
