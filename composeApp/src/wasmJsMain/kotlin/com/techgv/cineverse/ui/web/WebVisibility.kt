@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui.web

import androidx.compose.runtime.*
import kotlinx.browser.document
import org.w3c.dom.events.Event

@Composable
internal fun rememberPageVisible(): Boolean {
    var visible by remember { mutableStateOf(pageVisible()) }
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = { visible = pageVisible() }
        document.addEventListener("visibilitychange", listener)
        onDispose { document.removeEventListener("visibilitychange", listener) }
    }
    return visible
}

@JsFun("() => !document.hidden")
private external fun pageVisible(): Boolean
