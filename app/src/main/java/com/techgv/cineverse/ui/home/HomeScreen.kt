package com.techgv.cineverse.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.techgv.cineverse.ui.components.PlaceholderScreen
import com.techgv.cineverse.ui.spikes.spikesMenuRoute

/** Phase 2 replaces this placeholder. Debug builds expose the Phase-1 spikes from here. */
@Composable
fun HomeScreen(onOpen: (Any) -> Unit) {
    PlaceholderScreen("Home", "Rows and hero arrive in Phase 2.") {
        val route = spikesMenuRoute ?: return@PlaceholderScreen
        val focus = remember { FocusRequester() }
        Button(onClick = { onOpen(route) }, modifier = Modifier.focusRequester(focus)) { Text("Spikes (debug)") }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
