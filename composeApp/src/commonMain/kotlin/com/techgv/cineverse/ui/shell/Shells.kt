package com.techgv.cineverse.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.techgv.cineverse.ui.components.LocalToasts
import com.techgv.cineverse.ui.components.ToastHost
import com.techgv.cineverse.ui.components.ToastState
import com.techgv.cineverse.ui.design.Tokens

/**
 * Phone/tablet app chrome (Android + iOS). P7: hosts [content] edge-to-edge with the toast host above where the
 * floating tab bar sits. P8 adds the 5 tab back stacks, hero pager, sheets and predictive back here.
 */
@Composable
fun MobileShell(content: @Composable () -> Unit) {
    val toasts = remember { ToastState() }
    CompositionLocalProvider(LocalToasts provides toasts) {
        Box(Modifier.fillMaxSize().background(Tokens.Colors.Background)) {
            content()
            ToastHost(toasts)
        }
    }
}

/**
 * Browser chrome (wasm). P7: hosts [content] with the bottom-left toast host. P9 adds header/menus/footer,
 * the URL model and `/`-to-search here.
 */
@Composable
fun WebShell(content: @Composable () -> Unit) {
    val toasts = remember { ToastState() }
    CompositionLocalProvider(LocalToasts provides toasts) {
        Box(Modifier.fillMaxSize().background(Tokens.Colors.Background)) {
            content()
            ToastHost(toasts)
        }
    }
}
