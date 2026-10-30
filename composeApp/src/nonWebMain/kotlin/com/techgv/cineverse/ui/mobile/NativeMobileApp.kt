package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import androidx.navigation.compose.*
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.techgv.cineverse.data.library.LibraryWriteResult
import com.techgv.cineverse.presentation.TrailerEvent
import com.techgv.cineverse.presentation.LibraryWrite
import com.techgv.cineverse.presentation.LibraryWriteOutcome
import com.techgv.cineverse.ui.adaptive.ProvideWindowClass
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.shell.MobileShell
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.theme.CineVerseTheme

/** Native composition root. The graph and its observers already exist before either platform displays UI. */
@Composable
fun NativeMobileApp(graph: MobileApplication) {
    setSingletonImageLoaderFactory { context ->
        // Deliberately independent from the authenticated TMDB API client.
        ImageLoader.Builder(context).components { add(KtorNetworkFetcherFactory()) }.build()
    }
    val settings by graph.settings.collectAsState()
    val failed by graph.settingsFailed.collectAsState()
    val appearance = settings?.mobileAppearance() ?: AppearanceSettings(haptics = false, ambientGlow = false)
    CineVerseTheme(ShellStyle.Mobile, appearance) {
        ProvideWindowClass {
            MobileShell {
                if (settings == null) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), contentAlignment = Alignment.Center) {
                        if (failed) ErrorState("Couldn't read saved preferences.", graph::retrySettings)
                        else Text("Loading saved preferences…")
                    }
                } else {
                    val model = viewModel { MobileHomeViewModel(graph, createSavedStateHandle()) }
                    MobileRoot(graph, model)
                }
            }
        }
    }
}

@Composable
internal fun MobileRoot(graph: MobileApplication, model: MobileHomeViewModel) {
    val home = model.home
    val toasts = LocalToasts.current
    var selected by rememberSaveable(stateSaver = Saver<MobileTab, Int>(
        save = { it.ordinal }, restore = { MobileTab.entries[it] },
    )) { mutableStateOf(MobileTab.Home) }
    var scrollHome by rememberSaveable { mutableIntStateOf(0) }
    var pendingBrowse by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTrend by rememberSaveable { mutableStateOf(com.techgv.cineverse.data.tmdb.TrendingWindow.Week.name) }
    var scrollSearch by rememberSaveable { mutableIntStateOf(0) }
    var scrollExplore by rememberSaveable { mutableIntStateOf(0) }
    val controllers = MobileTab.entries.map { tab -> key(tab) { rememberNavController() } }
    val controller = controllers[selected.ordinal]
    val entry by controller.currentBackStackEntryAsState()
    val atRoot = entry?.destination?.hasRoute<MobileTabRoot>() == true
    val detailBinding = rememberMobileDetailRootBinding(graph, controller)
    val catalogBinding = rememberMobileCatalogRootBinding(controller)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle, detailBinding, catalogBinding) {
        detailBinding.setResumed(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        catalogBinding.setResumed(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            detailBinding.setResumed(resumed)
            catalogBinding.setResumed(resumed)
            if (!resumed) home.setActive(false)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            detailBinding.setResumed(false)
            catalogBinding.setResumed(false)
            home.setActive(false)
        }
    }
    val active = resumed && selected == MobileTab.Home && atRoot
    DisposableEffect(active, home) {
        home.setActive(active)
        onDispose { home.setActive(false) }
    }
    fun select(tab: MobileTab) {
        detailBinding.leave()
        catalogBinding.leave()
        home.setActive(false)
        if (tab == selected) {
            if (!atRoot) controller.popBackStack<MobileTabRoot>(inclusive = false)
            else if (tab == MobileTab.Home) scrollHome++
            else if (tab == MobileTab.Explore) scrollExplore++
            else if (tab == MobileTab.Search) scrollSearch++
        }
        selected = tab
        // Re-select at the root does not change the active effect's key.
        if (resumed && tab == MobileTab.Home && controllers[tab.ordinal].currentDestination?.hasRoute<MobileTabRoot>() == true) home.setActive(true)
        detailBinding.currentChanged(controllers[tab.ordinal].currentBackStackEntry)
        catalogBinding.currentChanged(controllers[tab.ordinal].currentBackStackEntry)
    }
    fun back() {
        detailBinding.leave()
        catalogBinding.leave()
        home.setActive(false)
        controller.popBackStack()
    }
    val navigator = MobileNavigator { destination ->
        detailBinding.leave()
        catalogBinding.leave()
        home.setActive(false)
        if (destination is MobileDestination.Browse) {
            selected = MobileTab.Explore
            pendingBrowse = destination.row.name
            pendingTrend = destination.trend.name
        } else controller.open(destination)
    }
    LaunchedEffect(selected, entry, pendingBrowse, pendingTrend) {
        val row = pendingBrowse
        if (selected == MobileTab.Explore && entry != null && row != null) {
            pendingBrowse = null
            detailBinding.leave()
            catalogBinding.leave()
            controller.navigate(MobileBrowseRoute(row, pendingTrend))
        }
    }
    LaunchedEffect(active, controller) {
        if (active) home.events.collect { event ->
            when (event) {
                is TrailerEvent.Play -> navigator.open(MobileDestination.Trailer(event.videoKey, event.title, event.kicker))
                is TrailerEvent.Unavailable -> toasts.show(ToastMessage("No trailer available for ${event.title}"))
                TrailerEvent.Failed -> toasts.show(ToastMessage("Couldn't load trailer. Try again."))
            }
        }
    }
    LaunchedEffect(graph, resumed) {
        if (resumed) graph.outcomes.collect { outcome ->
            mobileLibraryOutcomeMessage(outcome)?.let { toasts.show(ToastMessage(it)) }
        }
    }
    val stateHolder = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize()) {
        stateHolder.SaveableStateProvider(selected.name) {
            NavHost(controller, startDestination = MobileTabRoot) {
                composable<MobileTabRoot> {
                    if (selected == MobileTab.Home) MobileHomeScreen(graph, model, scrollHome, navigator,
                        onSearch = { select(MobileTab.Search) }, onProfile = { select(MobileTab.Profile) })
                    else if (selected == MobileTab.Explore) {
                        val explore = viewModel { MobileExploreViewModel(graph, createSavedStateHandle()) }
                        MobileExploreScreen(graph, explore, scrollExplore, navigator, onInvalidBack = { select(MobileTab.Home) })
                    } else if (selected == MobileTab.Search) {
                        val search = viewModel { MobileSearchViewModel(graph, createSavedStateHandle()) }
                        MobileSearchScreen(graph, search, scrollSearch, navigator, onInvalidBack = { select(MobileTab.Home) })
                    } else MobilePlaceholder(selected.name, "Internal placeholder · This tab is not implemented in M1.")
                }
                composable<MobileDetailRoute> { route ->
                    MobileDetailRouteEntry(graph, route, detailBinding, navigator, ::back)
                }
                composable<MobilePersonRoute> { route ->
                    MobilePersonRouteEntry(graph, route, catalogBinding, navigator, ::back)
                }
                composable<MobileSeasonRoute> { route ->
                    MobileSeasonRouteEntry(graph, route, catalogBinding, navigator, ::back)
                }
                composable<MobileTrailerRoute> { route ->
                    val trailer = route.toRoute<MobileTrailerRoute>()
                    MobilePlaceholder(trailer.title, "Internal placeholder · ${trailer.kicker}. Trailer playback is not implemented.", ::back)
                }
                composable<MobileBrowseRoute> { route ->
                    val selection = route.toRoute<MobileBrowseRoute>().selection()
                    val explore = viewModel { MobileExploreViewModel(graph, createSavedStateHandle(), selection) }
                    MobileExploreScreen(graph, explore, 0, navigator,
                        onInvalidBack = ::back, onBack = ::back)
                }
            }
        }
        FloatingTabBar(CineVerseTabs, selected.ordinal, { select(MobileTab.entries[it]) })
        MobileRootBackHandler(atRoot && selected != MobileTab.Home) { select(MobileTab.Home) }
        MobileRootRateHost(graph, detailBinding)
    }
}

internal fun mobileLibraryOutcomeMessage(outcome: LibraryWriteOutcome): String? = when (val result = outcome.result) {
    LibraryWriteResult.Superseded -> null
    is LibraryWriteResult.Failed -> when (outcome.write) {
        LibraryWrite.ToggleWatchlist -> "Couldn't update watchlist. Not retried."
        LibraryWrite.ToggleFavourite -> "Couldn't update favourites. Not retried."
        is LibraryWrite.Rate -> "Couldn't save rating. Not retried."
        LibraryWrite.Unrate -> "Couldn't remove rating. Not retried."
    }
    is LibraryWriteResult.Done -> if (!result.state.known) null else {
        val operation = when (outcome.write) {
            LibraryWrite.ToggleWatchlist -> if (result.state.watchlist) "Added to watchlist" else "Removed from watchlist"
            LibraryWrite.ToggleFavourite -> if (result.state.favourite) "Added to favourites" else "Removed from favourites"
            is LibraryWrite.Rate -> if (result.state.rating != null) "Rating saved" else null
            LibraryWrite.Unrate -> if (result.state.rating == null) "Rating removed" else null
        }
        operation?.let {
            "$it · ${if (result.synced) "Synced" else "Saved locally"}" +
                if (result.removedFromWatchlist) " · Removed from watchlist" else ""
        }
    }
}

@Composable
internal fun MobilePlaceholder(title: String, message: String, onBack: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (onBack != null) PillButton("Back", onBack, variant = ButtonVariant.Ghost)
        SectionTitle(title)
        Text(message)
    }
}

@Composable
internal expect fun MobileRootBackHandler(enabled: Boolean, onBack: () -> Unit)
