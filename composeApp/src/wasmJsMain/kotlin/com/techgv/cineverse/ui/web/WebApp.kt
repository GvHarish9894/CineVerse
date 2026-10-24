package com.techgv.cineverse.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.di.WebRuntime
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.ui.adaptive.*
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.shell.WebShell
import com.techgv.cineverse.ui.theme.*
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.mapNotNull

@Composable
fun WebApp(runtime: WebRuntime, catalog: CatalogRepository, generation: GenerationCounter, history: WebHistory = remember { BrowserHistory() }) {
    val navigation = remember(history) { WebNavigation(history) }
    DisposableEffect(navigation) { onDispose { navigation.close() } }
    val entry by navigation.entry.collectAsState()
    val session by runtime.session.collectAsState()
    val storage by runtime.storageFailure.collectAsState()
    val settingsState by runtime.settingsHolder.state.collectAsState()
    val settings = settingsState?.settings
    val appearance = settings?.let {
        AppearanceSettings(it.motion, it.glow, it.haptics, com.techgv.cineverse.ui.haptics.HapticStrength.valueOf(it.strength.name))
    } ?: AppearanceSettings(reduceMotion = true, ambientGlow = false, haptics = false)
    val settingsFlow = remember(runtime) { runtime.settingsHolder.state.mapNotNull { it?.settings } }
    CineVerseTheme(ShellStyle.Web, appearance) {
        ProvideWindowClass(Modifier.fillMaxSize()) {
            WebShell {
                val searchFocus = remember { FocusRequester() }
                var searchFocused by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxSize().onPreviewKeyEvent {
                    if (!searchFocused && it.type == KeyEventType.KeyDown && it.key == Key.Slash && !it.isCtrlPressed && !it.isMetaPressed) {
                        searchFocus.requestFocus(); true
                    } else false
                }) {
                    WebHeader(entry.route, session, searchFocus, navigation::navigate) { searchFocused = it }
                    WebStatus(session, storage, runtime::revalidateSession, runtime::retryStorage)
                    if (settings == null) {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(if (storage == null) "Reading saved settings…" else "Saved settings are unavailable. Retry storage to continue.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    } else key(entry) {
                        if (entry.route == WebRoute.Home) {
                            WebHome(entry, catalog, generation, settingsFlow, session, navigation::navigate)
                        } else WebPlaceholder(entry.route, navigation::navigate)
                    }
                }
            }
        }
    }
}

@Composable
internal fun WebStatus(session: WebSessionState, storage: BrowserStorageFailure?, retrySession: () -> Unit, retryStorage: () -> Unit) {
    if (session == WebSessionState.Checking) Text("Checking session…", Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
    if (session is WebSessionState.Failed) ErrorState("Session verification failed. Account actions are unavailable.", retrySession, Modifier.padding(8.dp))
    if (storage != null) ErrorState(storageMessage(storage), retryStorage, Modifier.padding(8.dp))
}

internal fun storageMessage(failure: BrowserStorageFailure): String = when (failure) {
    BrowserStorageFailure.Unsupported -> "Persistent browser storage is unsupported. No temporary library will be used."
    BrowserStorageFailure.Blocked -> "Browser storage is blocked. Allow storage, then retry the read."
    BrowserStorageFailure.Quota -> "Browser storage is full. Saved data is retained; writes are not replayed."
    BrowserStorageFailure.Corrupt -> "Saved browser data could not be read. Nothing has been reset."
    BrowserStorageFailure.Closed -> "Browser storage closed. Retry the read."
    BrowserStorageFailure.Aborted -> "The storage operation was interrupted. Writes are not replayed."
    BrowserStorageFailure.Unavailable -> "Browser storage is unavailable. Saved data has not been reset."
}

@Composable
internal fun WebHeader(route: WebRoute, session: WebSessionState, searchFocus: FocusRequester, navigate: (WebRoute, Boolean) -> Unit, searchFocused: (Boolean) -> Unit = {}) {
    var query by remember(route) { mutableStateOf((route as? WebRoute.Search)?.query.orEmpty()) }
    var edited by remember(route) { mutableStateOf(false) }
    LaunchedEffect(query, edited) {
        if (edited) { delay(300); navigate(WebRoute.Search(query), route is WebRoute.Search) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().background(Tokens.Colors.SurfaceGlass)) {
        val narrow = maxWidth <= 860.dp
        Column(Modifier.fillMaxWidth().padding(horizontal = if (maxWidth <= 640.dp) 16.dp else 32.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.cvClickable({ navigate(WebRoute.Home, false) }, onClickLabel = "CineVerse home"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LogoMark()
                    if (!narrow) Text("CineVerse", style = displayStyle(22.sp))
                }
                if (narrow) NavigationMenu("Menu", listOf(
                    "Home" to WebRoute.Home, "Movies" to WebRoute.Browse("movie"), "TV Shows" to WebRoute.Browse("tv"),
                    "People" to WebRoute.People, "My Library" to WebRoute.Library(), "Settings" to WebRoute.Settings,
                ), navigate)
                else {
                    NavigationMenu("Movies", listOf("Popular" to "popular", "Now playing" to "now_playing", "Upcoming" to "upcoming", "Top rated" to "top_rated").map { it.first to WebRoute.Browse("movie", it.second) }, navigate)
                    NavigationMenu("TV Shows", listOf("Popular" to "popular", "Airing today" to "airing_today", "On TV" to "on_the_air", "Top rated" to "top_rated").map { it.first to WebRoute.Browse("tv", it.second) }, navigate)
                    NavButton("People") { navigate(WebRoute.People, false) }
                    NavButton("My Library") { navigate(WebRoute.Library(), false) }
                }
                if (!narrow) Spacer(Modifier.weight(1f))
                if (!narrow) SearchField(query, { query = it; edited = true }, searchFocus, Modifier.widthIn(max = 340.dp).weight(1f).onFocusChanged { searchFocused(it.isFocused) })
                when (session) {
                    is WebSessionState.Ready -> if (session.session == null) {
                        PillButton("Sign in", { navigate(WebRoute.Settings, false) }, small = true, variant = ButtonVariant.Ghost)
                    } else NavigationMenu(session.session!!.displayName.ifBlank { "Account" }, listOf("Watchlist" to WebRoute.Library(), "Favourites" to WebRoute.Library("favourites"), "Ratings" to WebRoute.Library("ratings"), "Settings" to WebRoute.Settings), navigate)
                    WebSessionState.Checking -> Text("Verifying", style = MaterialTheme.typography.bodySmall)
                    is WebSessionState.Failed -> Text("Unverified", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (narrow) SearchField(query, { query = it; edited = true }, searchFocus, Modifier.fillMaxWidth().padding(top = 8.dp).onFocusChanged { searchFocused(it.isFocused) })
        }
    }
}

@Composable
private fun SearchField(query: String, change: (String) -> Unit, focus: FocusRequester, modifier: Modifier) {
    OutlinedTextField(query, change, modifier.focusRequester(focus), singleLine = true, label = { Text("Search movies, TV and people") })
}

@Composable
private fun NavigationMenu(label: String, links: List<Pair<String, WebRoute>>, navigate: (WebRoute, Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        NavButton("$label ▾") { expanded = !expanded }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.onPointerEvent(PointerEventType.Exit) { expanded = false }) {
            links.forEach { (text, route) ->
                val source = remember { MutableInteractionSource() }
                DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; navigate(route, false) },
                    interactionSource = source, modifier = Modifier.focusRing(source, RoundedCornerShape(6.dp)))
            }
        }
    }
}

@Composable
private fun NavButton(label: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).cvClickable(onClick, focusShape = RoundedCornerShape(8.dp)).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun WebPlaceholder(route: WebRoute, navigate: (WebRoute, Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        val title = when (route) {
            is WebRoute.Browse -> "${if (route.media == "movie") "Movies" else "TV Shows"} · ${route.list}"
            WebRoute.People -> "People"
            is WebRoute.Detail -> "${route.media} · ${route.id}"
            is WebRoute.Person -> "Person · ${route.id}"
            is WebRoute.Season -> "TV ${route.id} · Season ${route.number}"
            is WebRoute.Search -> "Search · ${route.query}"
            is WebRoute.Library -> "My Library · ${route.tab}"
            WebRoute.Settings -> "Settings and account"
            is WebRoute.Trailer -> "Trailer · ${route.title}"
            else -> "Page not found"
        }
        EmptyState(title, when (route) {
            WebRoute.NotFound -> "This URL is unknown or invalid. No account operation was performed."
            is WebRoute.Trailer -> "Playback is not implemented in this preview. No video is playing."
            else -> "This destination is not implemented in this preview. No account changes were made."
        }) { PillButton("Back to Home", { navigate(WebRoute.Home, false) }) }
        Footer()
    }
}
