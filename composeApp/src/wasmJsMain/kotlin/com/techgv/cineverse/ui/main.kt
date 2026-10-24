@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.techgv.cineverse.di.BrowserApplication
import com.techgv.cineverse.ui.adaptive.ShellStyle
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val application = BrowserApplication()
    onPageExit(application::stop)
    // The browser never receives a TMDB token; the proxy (server) holds it.
    ComposeViewport(document.body!!) { CineVerseApp(ShellStyle.Web, nativeReadToken = null) }
}

// A bfcache page retains its live graph and catches up via C3 visibility/focus signals when restored.
@JsFun("close => window.addEventListener('pagehide', event => { if (!event.persisted) close(); })")
private external fun onPageExit(close: () -> Unit)
