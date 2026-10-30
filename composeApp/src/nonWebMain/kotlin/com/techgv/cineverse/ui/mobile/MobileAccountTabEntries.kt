package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry

/** Library tab root: entry-owned model, activated only through the root's catalog binding like Person/Season. */
@Composable
internal fun MobileLibraryTabEntry(
    graph: MobileApplication,
    entry: NavBackStackEntry,
    binding: MobileCatalogRootBinding,
    scrollToTop: Int,
    navigator: MobileNavigator,
    onExplore: () -> Unit,
) {
    val model = viewModel(viewModelStoreOwner = entry) { graph.library(createSavedStateHandle()) }
    DisposableEffect(entry, model, binding) {
        binding.attach(entry, model)
        onDispose { binding.detach(entry, model) }
    }
    MobileLibraryScreen(graph, model, scrollToTop, navigator, graph.account::onSignIn, onExplore)
}

/** Profile tab root: sign-in and the exact rendered session's sign-out go to the app-lifetime account owner. */
@Composable
internal fun MobileProfileTabEntry(graph: MobileApplication, entry: NavBackStackEntry, binding: MobileCatalogRootBinding) {
    val model = viewModel(viewModelStoreOwner = entry) { graph.profile(createSavedStateHandle()) }
    DisposableEffect(entry, model, binding) {
        binding.attach(entry, model)
        onDispose { binding.detach(entry, model) }
    }
    MobileProfileScreen(model, graph.account::onSignIn, graph.account::onSignOut)
}
