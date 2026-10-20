package com.techgv.cineverse.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.techgv.cineverse.ui.components.PlaceholderScreen
import com.techgv.cineverse.ui.nav.HomeBackHandle
import com.techgv.cineverse.ui.spikes.spikesMenuRoute

/** Phase 2 replaces this placeholder. Debug builds expose the Phase-1 spikes from here. */
@Composable
fun HomeScreen(onOpen: (Any) -> Unit, backHandle: HomeBackHandle) {
    val route = spikesMenuRoute
    val focus = remember { FocusRequester() }
    var originFocused by remember { mutableStateOf(false) }
    // The placeholder's only focusable (none in release) is its origin.
    DisposableEffect(backHandle) {
        val unregister = backHandle.register(
            atOrigin = { route == null || originFocused },
            focusOrigin = { focus.requestFocus() },
        )
        onDispose { unregister() }
    }
    PlaceholderScreen("Home", "Rows and hero arrive in Phase 2.") {
        if (route == null) return@PlaceholderScreen
        Button(
            onClick = { onOpen(route) },
            modifier = Modifier.focusRequester(focus).onFocusChanged { originFocused = it.isFocused },
        ) { Text("Spikes (debug)") }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
