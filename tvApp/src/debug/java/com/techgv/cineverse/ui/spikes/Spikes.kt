package com.techgv.cineverse.ui.spikes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.techgv.cineverse.AppContainer
import com.techgv.cineverse.ui.components.PlaceholderScreen
import com.techgv.cineverse.ui.nav.Route
import kotlinx.serialization.Serializable

/** Phase-1 spikes, debug builds only (reached from Home). */
@Serializable data object SpikesMenu
@Serializable data object AuthSpike
@Serializable data object FocusSpike

val spikesMenuRoute: Any? = SpikesMenu

fun NavGraphBuilder.spikeDestinations(nav: NavController, container: AppContainer) {
    composable<SpikesMenu> { SpikesMenuScreen(onOpen = { nav.navigate(it) }) }
    composable<AuthSpike> { AuthSpikeScreen(container.authRepository) }
    composable<FocusSpike> { FocusSpikeScreen(onOpen = { nav.navigate(it) }) }
}

@Composable
private fun SpikesMenuScreen(onOpen: (Any) -> Unit) {
    val first = remember { FocusRequester() }
    PlaceholderScreen("Spikes", "Phase-1 spikes (debug build only)") {
        Button(onClick = { onOpen(AuthSpike) }, modifier = Modifier.focusRequester(first)) { Text("a · Auth round-trip") }
        Button(onClick = { onOpen(Route.Player("dQw4w9WgXcQ", "Never Gonna Give You Up", "Spike b · Trailer")) }) {
            Text("b · Player")
        }
        Button(onClick = { onOpen(Route.Player("invalidKey0", "Broken video key", "Spike b · Error path")) }) {
            Text("b · Player error path")
        }
        Button(onClick = { onOpen(FocusSpike) }) { Text("c · Focus restore") }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}
