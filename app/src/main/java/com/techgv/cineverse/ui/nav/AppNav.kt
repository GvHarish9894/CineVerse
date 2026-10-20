package com.techgv.cineverse.ui.nav

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.techgv.cineverse.AppContainer
import com.techgv.cineverse.ui.components.ExitDialog
import com.techgv.cineverse.ui.components.PlaceholderScreen
import com.techgv.cineverse.ui.home.HomeScreen
import com.techgv.cineverse.ui.feedback.LocalFeedback
import com.techgv.cineverse.ui.feedback.navigationFeedback
import com.techgv.cineverse.ui.login.AccountToastHost
import com.techgv.cineverse.ui.login.AccountToasts
import com.techgv.cineverse.ui.login.LoginScreen
import com.techgv.cineverse.ui.player.PlayerScreen
import com.techgv.cineverse.ui.settings.SettingsScreen
import com.techgv.cineverse.ui.spikes.spikeDestinations
import com.techgv.cineverse.ui.splash.SplashScreen

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
    var exitDialog by rememberSaveable { mutableStateOf(false) }
    var loginMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val activity = LocalActivity.current
    val toasts = remember { AccountToasts() }
    val credentials by container.authRepository.credentials.collectAsStateWithLifecycle(initialValue = null)
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

    Row(Modifier.fillMaxSize().navigationFeedback(LocalFeedback.current, LocalFocusManager.current)) {
        if (destination?.isRailScreen() == true) {
            val current = entry?.railItem()
            NavRail(
                current = current,
                // Rail press on the current root: no navigation, focus goes into the content.
                onSelect = { item -> if (item == current) contentFocus.requestFocus() else nav.reset(item.route) },
                modifier = Modifier.onFocusChanged { railFocused = it.hasFocus },
                accountName = credentials?.displayName,
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight().focusRequester(contentFocus).focusRestorer()) {
            NavHost(nav, startDestination = Route.Splash) {
                composable<Route.Splash> {
                    SplashScreen(container) { signedIn -> nav.reset(if (signedIn) Route.Home else Route.Login) }
                }
                composable<Route.Home> { HomeScreen(onOpen = { nav.navigate(it) }, backHandle = homeBack) }
                composable<Route.Browse> { PlaceholderScreen("Browse", it.toRoute<Route.Browse>().kind.name) }
                composable<Route.Search> { PlaceholderScreen("Search") }
                composable<Route.Library> { PlaceholderScreen("My Library") }
                composable<Route.Settings> {
                    SettingsScreen(
                        onSignIn = { nav.navigate(Route.Login) },
                        onSignedOut = { toasts.show("Signed out", "Your library stays on this TV") },
                    )
                }
                composable<Route.Login> {
                    LoginScreen(
                        message = loginMessage,
                        onContinueAsGuest = {
                            toasts.show("Browsing as a guest", "Sign in from Settings whenever you like")
                            leaveLogin()
                        },
                        onSignedIn = { name ->
                            toasts.show("Signed in as $name", "Watchlist, favourites and ratings will sync")
                            leaveLogin()
                        },
                    )
                }
                composable<Route.Detail> {
                    val route = it.toRoute<Route.Detail>()
                    PlaceholderScreen("Detail", "${route.mediaType.path} ${route.id}")
                }
                composable<Route.Person> { PlaceholderScreen("Person", "${it.toRoute<Route.Person>().id}") }
                composable<Route.Season> {
                    val route = it.toRoute<Route.Season>()
                    PlaceholderScreen("Season", "series ${route.seriesId} · season ${route.seasonNumber}")
                }
                composable<Route.Player> { PlayerScreen(it.toRoute(), onBack = { nav.popBackStack() }) }
                spikeDestinations(nav, container)
            }
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
            BackAction.ShowExitDialog -> exitDialog = true
            BackAction.Pop -> nav.popBackStack()
            BackAction.FocusContent -> contentFocus.requestFocus()
            BackAction.ResetToHome -> nav.reset(Route.Home)
            BackAction.FocusHomeOrigin -> homeBack.focusOrigin()
        }
    }

    AccountToastHost(toasts, Modifier.fillMaxSize().wrapContentSize(Alignment.BottomCenter))

    if (exitDialog) ExitDialog(onStay = { exitDialog = false }, onExit = { activity?.finish() })
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
