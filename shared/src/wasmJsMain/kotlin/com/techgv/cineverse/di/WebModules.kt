package com.techgv.cineverse.di

import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.WebAuthRepositoryStub
import com.techgv.cineverse.data.settings.BrowserSettingsStore
import com.techgv.cineverse.data.settings.SettingsStore
import com.techgv.cineverse.data.tmdb.Transport
import org.koin.core.module.Module
import org.koin.dsl.module

/** What the web entry point supplies: the same-origin proxy (plan §5). Never a token. */
class WebConfig(val proxyBaseUrl: String)

/**
 * Every shared module the web entry point starts Koin with. No `libraryModule` and no catalog yet: browser library
 * stores and the proxy TMDB / account client arrive in P9.
 */
fun sharedModules(config: WebConfig): List<Module> = listOf(module { single { config } }, coreModule(), platformModule())

actual fun platformModule(): Module = module {
    single<SettingsStore> { BrowserSettingsStore() }
    single<Transport.Proxy> {
        val baseUrl = get<WebConfig>().proxyBaseUrl
        object : Transport.Proxy {
            override val baseUrl = baseUrl
        }
    }
    single<AuthRepository> { WebAuthRepositoryStub() }
}
