@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.smoke

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.techgv.cineverse.di.BrowserApplication
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val application = BrowserApplication()
    onPageExit(application::stop)
    // The separate Coil image client remains credential-free; no product UI is introduced here.
    ComposeViewport(document.body!!) { App(nativeReadToken = null) }
}

// A bfcache page retains its live graph and catches up via C3 visibility/focus signals when restored.
@JsFun("close => window.addEventListener('pagehide', event => { if (!event.persisted) close(); })")
private external fun onPageExit(close: () -> Unit)
