@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.techgv.cineverse.di.BrowserApplication
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.search.RecentQueryStore
import com.techgv.cineverse.data.settings.SettingsStore
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.util.GenerationCounter
import com.techgv.cineverse.ui.web.WebApp
import com.techgv.cineverse.ui.theme.ProvideWebBundledFonts
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val application = BrowserApplication()
    onPageExit(application::stop)
    // Resolve the graph once at the composition root; screen logic receives typed constructors.
    val catalog = application.graph.koin.get<CatalogRepository>()
    val generation = application.graph.koin.get<GenerationCounter>()
    val repository = application.graph.koin.get<SettingsRepository>()
    val recent = RecentQueryStore(application.graph.koin.get<SettingsStore>())
    ComposeViewport(document.getElementById("cineverse-root")!!) {
        ProvideWebBundledFonts {
            WebApp(application.runtime, catalog, generation, repository.settings, recent, settingsRepository = repository)
        }
    }
}

// A bfcache page retains its live graph and catches up via C3 visibility/focus signals when restored.
@JsFun("close => window.addEventListener('pagehide', event => { if (!event.persisted) close(); })")
private external fun onPageExit(close: () -> Unit)
