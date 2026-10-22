package com.techgv.cineverse.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.window
import org.w3c.dom.events.Event

@Composable
actual fun rememberSystemReduceMotion(): State<Boolean> {
    val query = remember { window.matchMedia("(prefers-reduced-motion: reduce)") }
    val reduce = remember(query) { mutableStateOf(query.matches) }
    DisposableEffect(query) {
        val listener: (Event) -> Unit = { reduce.value = query.matches }
        query.addEventListener("change", listener)
        onDispose { query.removeEventListener("change", listener) }
    }
    return reduce
}
