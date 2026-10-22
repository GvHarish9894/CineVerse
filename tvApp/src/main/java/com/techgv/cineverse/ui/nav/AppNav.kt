package com.techgv.cineverse.ui.nav

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.techgv.cineverse.ui.browse.BrowseScreen
import com.techgv.cineverse.ui.components.LocalToaster
import com.techgv.cineverse.ui.components.ToastHost
import com.techgv.cineverse.ui.components.Toaster
import com.techgv.cineverse.ui.search.SearchScreen
import com.techgv.cineverse.ui.settings.SettingsScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.techgv.cineverse.AppContainer
import com.techgv.cineverse.ui.components.ExitDialog
import com.techgv.cineverse.ui.detail.DetailScreen
import com.techgv.cineverse.ui.feedback.LocalFeedback
import com.techgv.cineverse.ui.feedback.navigationFeedback
import com.techgv.cineverse.ui.home.HomeScreen
import com.techgv.cineverse.ui.library.ImportLibraryHost
import com.techgv.cineverse.ui.library.LibraryActionsHost
import com.techgv.cineverse.ui.library.LibraryScreen
import com.techgv.cineverse.ui.login.LoginScreen
import com.techgv.cineverse.ui.person.PersonScreen
import com.techgv.cineverse.ui.player.PlayerScreen
import com.techgv.cineverse.ui.season.SeasonScreen
import com.techgv.cineverse.ui.spikes.spikeDestinations
import com.techgv.cineverse.ui.splash.SplashScreen
import com.techgv.cineverse.presentation.BrowseKind

/**
 * BACK steps 6–7 (§1.2): Home registers whether focus is at row 0 / col 0 and how to get there, from a
 * `DisposableEffect`, calling the returned unregister action in `onDispose`. Unregistered (or before
 * Home reports), the first BACK counts as "not at origin" and only the next one offers Exit.
 */
class HomeBackHandle {
    private class Registration(val atOrigin: () -> Boolean, val focusOrigin: () -> Unit)

    private var registration: Registration? = null
    private var originRequested = false

    fun atOrigin(): Boolean = registration?.atOrigin?.invoke() ?: originRequested

    fun focusOrigin() {
        originRequested = true
        registration?.focusOrigin?.invoke()
    }

    /** Returns the unregister action; it only clears this registration, never a newer one. */
    fun register(atOrigin: () -> Boolean, focusOrigin: () -> Unit): () -> Unit {
        val mine = Registration(atOrigin, focusOrigin)
        registration = mine
        originRequested = false
        return { if (registration === mine) registration = null }
    }
}

const val SIGN_IN_EXPIRED_MESSAGE = "Sign-in expired"

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val contentFocus = remember { FocusRequester() }
    val homeBack = remember { HomeBackHandle() }
    var railFocused by remember { mutableStateOf(false) }
    /** Id of the back-stack entry the Exit dialog was opened on; null when closed. */
    var exitDialogEntry by rememberSaveable { mutableStateOf<String?>(null) }
    var loginMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val activity = LocalActivity.current
    val leaveLogin: () -> Unit = {
        loginMessage = null
        if (nav.previousBackStackEntry != null) nav.popBackStack() else nav.reset(Route.Home)
    }

    // §3.3: AuthRepository already signed out locally (credentials cleared, account generation bumped).
    LaunchedEffect(container) {
        container.authRepository.sessionExpired.collect {
            loginMessage = SIGN_IN_EXPIRED_MESSAGE
            nav.reset(Route.Login)
        }
    }

    val session by container.authRepository.session.collectAsStateWithLifecycle(null)
    val accountName = session?.displayName
    val toaster = remember { Toaster() }
    val railScreen = destination?.isRailScreen() == true
    val visibleEntries by nav.visibleEntries.collectAsState()
    // Includes a Player NavHost still shows while it transitions out: no modal, toast or clock over the video.
    val onPlayer = destination?.hasRoute<Route.Player>() == true ||
        visibleEntries.any { it.destination.hasRoute<Route.Player>() }
    // Another destination became current (e.g. a delayed trailer opened the Player): the Exit dialog closes.
    val entryId = entry?.id
    LaunchedEffect(entryId) { if (entryId != null && entryId != exitDialogEntry) exitDialogEntry = null }

    CompositionLocalProvider(LocalToaster provides toaster) {
        // §9 D-pad hook, once, on the root holding both rail and content (dialogs use FeedbackRoot).
        Box(Modifier.fillMaxSize().navigationFeedback(LocalFeedback.current, LocalFocusManager.current)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = if (railScreen) RAIL_COLLAPSED_WIDTH else 0.dp)
                    .focusRequester(contentFocus)
                    // The restorer owns one group (the screen): without it its onEnter acts on every top-level
                    // group inside and redirects programmatic requestFocus calls (e.g. the Season episode panel).
                    .focusRestorer()
                    .focusGroup(),
            ) {
                // §7.12: the Rate dialog waits while the Player is up.
                LibraryActionsHost(container.libraryRepository, container.appScope, playerActive = onPlayer) {
                    NavHost(nav, startDestination = Route.Splash) {
                        composable<Route.Splash> {
                            SplashScreen(container) { signedIn -> nav.reset(if (signedIn) Route.Home else Route.Login) }
                        }
                        composable<Route.Home> { HomeScreen(container, onOpen = { nav.navigate(it) }, backHandle = homeBack) }
                        composable<Route.Browse> { BrowseScreen(container, it.toRoute<Route.Browse>().kind, onOpen = { route -> nav.navigate(route) }) }
                        composable<Route.Search> { SearchScreen(container, onOpen = { route -> nav.navigate(route) }) }
                        composable<Route.Library> {
                            LibraryScreen(
                                container,
                                onOpenTitle = { nav.navigate(Route.Detail(it.mediaType, it.id)) },
                                onExplore = { nav.reset(Route.Browse(BrowseKind.Movies)) },
                                onSignIn = { nav.navigate(Route.Login) },
                            )
                        }
                        composable<Route.Settings> { SettingsScreen(onSignIn = { nav.navigate(Route.Login) }) }
                        composable<Route.Login> { LoginScreen(onContinueAsGuest = leaveLogin, onSignedIn = leaveLogin, message = loginMessage) }
                        composable<Route.Detail> { DetailScreen(it.toRoute(), container, onNavigate = { route -> nav.navigate(route) }) }
                        composable<Route.Person> { PersonScreen(it.toRoute(), container, onNavigate = { route -> nav.navigate(route) }) }
                        composable<Route.Season> { SeasonScreen(it.toRoute(), container, onNavigate = { route -> nav.navigate(route) }) }
                        composable<Route.Player> { PlayerScreen(it.toRoute(), onBack = { nav.popBackStack() }) }
                        spikeDestinations(nav, container)
                    }
                }
            }
            // §5 clock: every screen but Splash and Player (nothing drawn over the player).
            if (destination != null && !destination.hasRoute<Route.Splash>() && !onPlayer) {
                Clock(Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 48.dp))
            }
            if (railScreen) {
                val current = entry?.railItem()
                // The expanded rail overlays the content behind a scrim (prototype `.nav-scrim`).
                if (railFocused) {
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xE607060F), Color(0x4007060F)))))
                }
                NavRail(
                    current = current,
                    // Rail press on the current root: no navigation, focus goes into the content.
                    onSelect = { item -> if (item == current) contentFocus.requestFocus() else nav.reset(item.route) },
                    accountName = accountName,
                    modifier = Modifier.onFocusChanged { railFocused = it.hasFocus },
                )
            }
            // §7.12 bottom area, never over the player: on Player a toast waits in the queue until it closes.
            if (!onPlayer) ToastHost(toaster, Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp))
            ImportLibraryHost(container.libraryRepository, container.libraryImporter, playerActive = onPlayer)
        }
    }

    // Declared after NavHost so it takes precedence over NavHost's own pop handler.
    BackHandler(enabled = destination != null && !destination.hasRoute<Route.Splash>()) {
        val context = BackContext(
            onLogin = destination?.hasRoute<Route.Login>() == true,
            hasPushedRoute = nav.previousBackStackEntry != null,
            focusInRail = railFocused,
            onHome = destination?.hasRoute<Route.Home>() == true,
            homeAtOrigin = homeBack.atOrigin(),
        )
        when (backAction(context)) {
            BackAction.ShowExitDialog -> exitDialogEntry = entryId
            BackAction.Pop -> nav.popBackStack()
            BackAction.FocusContent -> contentFocus.requestFocus()
            BackAction.ResetToHome -> nav.reset(Route.Home)
            BackAction.FocusHomeOrigin -> homeBack.focusOrigin()
        }
    }

    if (exitDialogEntry != null && exitDialogEntry == entryId && !onPlayer) {
        ExitDialog(onStay = { exitDialogEntry = null }, onExit = { activity?.finish() })
    }
}

private fun NavBackStackEntry.railItem(): RailItem? = when {
    destination.hasRoute<Route.Search>() -> RailItem.Search
    destination.hasRoute<Route.Home>() -> RailItem.Home
    destination.hasRoute<Route.Browse>() -> when (toRoute<Route.Browse>().kind) {
        BrowseKind.Movies -> RailItem.Movies
        BrowseKind.Tv -> RailItem.TvShows
        BrowseKind.People -> RailItem.People
    }
    destination.hasRoute<Route.Library>() -> RailItem.Library
    destination.hasRoute<Route.Settings>() -> RailItem.Settings
    else -> null
}
