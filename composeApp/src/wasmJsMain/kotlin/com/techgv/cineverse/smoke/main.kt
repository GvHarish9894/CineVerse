package com.techgv.cineverse.smoke

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.techgv.cineverse.di.WebConfig
import com.techgv.cineverse.di.sharedModules
import com.techgv.cineverse.di.webModule
import kotlinx.browser.document
import org.koin.core.context.startKoin

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Same-origin server proxy (plan §5): the API lives under /api next to the static bundle.
    startKoin { modules(sharedModules(WebConfig(proxyBaseUrl = "/api")) + webModule()) }
    // The browser never receives a TMDB token; the proxy (server) holds it.
    ComposeViewport(document.body!!) { App(nativeReadToken = null) }
}
