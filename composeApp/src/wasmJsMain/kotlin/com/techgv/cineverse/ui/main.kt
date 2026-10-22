package com.techgv.cineverse.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.techgv.cineverse.ui.adaptive.ShellStyle
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // The browser never receives a TMDB token; the proxy (server) holds it.
    ComposeViewport(document.body!!) { CineVerseApp(ShellStyle.Web, nativeReadToken = null) }
}
