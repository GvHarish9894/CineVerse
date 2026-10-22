package com.techgv.cineverse.smoke

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // The browser never receives a TMDB token; the proxy (server) holds it.
    ComposeViewport(document.body!!) { App(nativeReadToken = null) }
}
