package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.toRoute

internal interface MobileCatalogEntryModel {
    fun setActive(value: Boolean)
}

/** Entry visibility authority shared by Person and Season; ViewModel stores alone own final close. */
internal class MobileCatalogRootBinding {
    private var current: NavBackStackEntry? = null
    private var attachedEntry: NavBackStackEntry? = null
    private var attached: MobileCatalogEntryModel? = null
    private var resumed = false

    private fun active() = resumed && current === attachedEntry &&
        current?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true

    private fun reconcile() {
        attached?.setActive(active())
    }

    fun leave() {
        current = null
        reconcile()
    }

    fun setResumed(value: Boolean) {
        resumed = value
        reconcile()
    }

    fun currentChanged(entry: NavBackStackEntry?) {
        if (current !== entry) {
            current = entry
            attached?.setActive(false)
        }
        reconcile()
    }

    fun clearCurrent(entry: NavBackStackEntry?) {
        if (current === entry) leave()
    }

    fun entryStateChanged(entry: NavBackStackEntry) {
        if (current === entry) reconcile()
    }

    fun attach(entry: NavBackStackEntry, model: MobileCatalogEntryModel) {
        val previous = attached
        attachedEntry = entry
        attached = model
        if (previous !== model) previous?.setActive(false)
        reconcile()
    }

    fun detach(entry: NavBackStackEntry, model: MobileCatalogEntryModel) {
        if (attachedEntry !== entry || attached !== model) return
        attachedEntry = null
        attached = null
        model.setActive(false)
    }

    fun close() {
        current = null
        resumed = false
        attached?.setActive(false)
        attachedEntry = null
        attached = null
    }
}

@Composable
internal fun rememberMobileCatalogRootBinding(controller: NavHostController): MobileCatalogRootBinding {
    val binding = remember { MobileCatalogRootBinding() }
    DisposableEffect(binding) { onDispose { binding.close() } }
    DisposableEffect(binding, controller) {
        var observed: NavBackStackEntry? = null
        var observer: LifecycleEventObserver? = null
        val listener = NavController.OnDestinationChangedListener { _, _, _ ->
            observer?.let { observed?.lifecycle?.removeObserver(it) }
            val next = controller.currentBackStackEntry
            observed = next
            binding.currentChanged(next)
            observer = next?.let { entry -> LifecycleEventObserver { _, _ -> binding.entryStateChanged(entry) } }
            observer?.let { next?.lifecycle?.addObserver(it) }
        }
        controller.addOnDestinationChangedListener(listener)
        onDispose {
            controller.removeOnDestinationChangedListener(listener)
            observer?.let { observed?.lifecycle?.removeObserver(it) }
            binding.clearCurrent(observed)
        }
    }
    return binding
}

@Composable
internal fun MobilePersonRouteEntry(
    graph: MobileApplication,
    entry: NavBackStackEntry,
    binding: MobileCatalogRootBinding,
    navigator: MobileNavigator,
    onBack: () -> Unit,
    onModel: (MobilePersonViewModel) -> Unit = {},
) {
    val route = entry.toRoute<MobilePersonRoute>()
    val model = viewModel(viewModelStoreOwner = entry) { graph.person(route.id, createSavedStateHandle()) }
    DisposableEffect(entry, model, binding) {
        binding.attach(entry, model)
        onDispose { binding.detach(entry, model) }
    }
    SideEffect { onModel(model) }
    MobilePersonScreen(model, navigator, onBack)
}

@Composable
internal fun MobileSeasonRouteEntry(
    graph: MobileApplication,
    entry: NavBackStackEntry,
    binding: MobileCatalogRootBinding,
    navigator: MobileNavigator,
    onBack: () -> Unit,
    onModel: (MobileSeasonViewModel) -> Unit = {},
) {
    val route = entry.toRoute<MobileSeasonRoute>()
    val model = viewModel(viewModelStoreOwner = entry) { graph.season(route.seriesId, route.number, createSavedStateHandle()) }
    DisposableEffect(entry, model, binding) {
        binding.attach(entry, model)
        onDispose { binding.detach(entry, model) }
    }
    SideEffect { onModel(model) }
    MobileSeasonScreen(model, navigator, onBack)
}
