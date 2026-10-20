package com.techgv.cineverse.ui.spikes

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import com.techgv.cineverse.AppContainer

/** Release builds ship no spikes. */
val spikesMenuRoute: Any? = null

@Suppress("UnusedReceiverParameter", "unused")
fun NavGraphBuilder.spikeDestinations(nav: NavController, container: AppContainer) = Unit
