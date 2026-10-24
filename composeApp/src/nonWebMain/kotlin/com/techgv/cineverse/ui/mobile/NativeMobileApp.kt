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
import com.techgv.cineverse.presentation.HomeStateHolder
import com.techgv.cineverse.presentation.TrailerEvent
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
                    MobileRoot(graph, model.home)
                }
            }
        }
    }
}

@Composable
internal fun MobileRoot(graph: MobileApplication, home: HomeStateHolder) {
    val toasts = LocalToasts.current
    var selected by rememberSaveable(stateSaver = Saver<MobileTab, Int>(
        save = { it.ordinal }, restore = { MobileTab.entries[it] },
    )) { mutableStateOf(MobileTab.Home) }
    var scrollHome by rememberSaveable { mutableIntStateOf(0) }
    var pendingBrowse by rememberSaveable { mutableStateOf<String?>(null) }
    val controllers = MobileTab.entries.map { tab -> key(tab) { rememberNavController() } }
    val controller = controllers[selected.ordinal]
    val entry by controller.currentBackStackEntryAsState()
    val atRoot = entry?.destination?.hasRoute<MobileTabRoot>() == true
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) home.setActive(false)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); home.setActive(false) }
    }
    val active = resumed && selected == MobileTab.Home && atRoot
    DisposableEffect(active, home) {
        home.setActive(active)
        onDispose { home.setActive(false) }
    }
    fun select(tab: MobileTab) {
        home.setActive(false)
        if (tab == selected) {
            if (!atRoot) controller.popBackStack<MobileTabRoot>(inclusive = false)
            else if (tab == MobileTab.Home) scrollHome++
        }
        selected = tab
        // Re-select at the root does not change the active effect's key.
        if (resumed && tab == MobileTab.Home && controllers[tab.ordinal].currentDestination?.hasRoute<MobileTabRoot>() == true) home.setActive(true)
    }
    val navigator = MobileNavigator { destination ->
        home.setActive(false)
        if (destination is MobileDestination.Browse) {
            selected = MobileTab.Explore
            pendingBrowse = destination.row.name
        } else controller.open(destination)
    }
    LaunchedEffect(selected, entry, pendingBrowse) {
        val row = pendingBrowse
        if (selected == MobileTab.Explore && entry != null && row != null) {
            pendingBrowse = null
            controller.navigate(MobileBrowseRoute(row))
        }
    }
    MobileRootBackHandler(atRoot && selected != MobileTab.Home) { select(MobileTab.Home) }
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
            when (val result = outcome.result) {
                is LibraryWriteResult.Done -> toasts.show(ToastMessage(if (result.state.watchlist) "Added to watchlist" else "Removed from watchlist"))
                is LibraryWriteResult.Failed -> toasts.show(ToastMessage("Couldn't update watchlist. Not retried."))
                LibraryWriteResult.Superseded -> Unit
            }
        }
    }
    val stateHolder = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize()) {
        stateHolder.SaveableStateProvider(selected.name) {
            NavHost(controller, startDestination = MobileTabRoot) {
                composable<MobileTabRoot> {
                    if (selected == MobileTab.Home) MobileHomeScreen(graph, home, scrollHome, navigator,
                        onSearch = { select(MobileTab.Search) }, onProfile = { select(MobileTab.Profile) })
                    else MobilePlaceholder(selected.name, "Internal placeholder · This tab is not implemented in M1.")
                }
                composable<MobileDetailRoute> { route ->
                    val detail = route.toRoute<MobileDetailRoute>()
                    MobilePlaceholder("Title detail", "Internal placeholder · ${detail.media} ${detail.id}", { controller.popBackStack() })
                }
                composable<MobilePersonRoute> { route ->
                    MobilePlaceholder("Person", "Internal placeholder · Person ${route.toRoute<MobilePersonRoute>().id}", { controller.popBackStack() })
                }
                composable<MobileSeasonRoute> { route ->
                    val season = route.toRoute<MobileSeasonRoute>()
                    MobilePlaceholder("Season ${season.number}", "Internal placeholder · Series ${season.seriesId}", { controller.popBackStack() })
                }
                composable<MobileTrailerRoute> { route ->
                    val trailer = route.toRoute<MobileTrailerRoute>()
                    MobilePlaceholder(trailer.title, "Internal placeholder · ${trailer.kicker}. Trailer playback is not implemented.", { controller.popBackStack() })
                }
                composable<MobileBrowseRoute> { route ->
                    MobilePlaceholder("Explore", "Internal placeholder · ${route.toRoute<MobileBrowseRoute>().row}", { controller.popBackStack() })
                }
            }
        }
        FloatingTabBar(CineVerseTabs, selected.ordinal, { select(MobileTab.entries[it]) })
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
