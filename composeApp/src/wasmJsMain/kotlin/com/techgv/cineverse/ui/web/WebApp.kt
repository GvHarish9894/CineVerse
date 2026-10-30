@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui.web

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.search.RecentQueryStore
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.flow.Flow
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Video
import com.techgv.cineverse.presentation.LibraryUiState
import com.techgv.cineverse.presentation.LibraryWrite
import com.techgv.cineverse.presentation.LibraryWriteOutcome
import com.techgv.cineverse.data.library.LibraryWriteResult
import kotlinx.coroutines.flow.MutableSharedFlow

/** G4 extractors: identity and page come from the SAME immutable holder result; unknown origin is null. */
private val libraryResultIdentity: (LibraryUiState) -> WebLibraryResultIdentity? = { state ->
    if (!state.sourceIdentityKnown) null else state.sourceIdentity?.let(WebLibraryResultIdentity::Account) ?: WebLibraryResultIdentity.Guest
}
private val libraryResultPage: (LibraryUiState, MediaType) -> Int? = { state, media -> state.group(media).loadedPage }

/**
 * [settingsRepository] is the strict repository behind [browseSettings]; null only in hosts without one, where
 * the Settings route stays a placeholder. [historyBack] is the component Back action (browser history by default).
 */
@Composable
fun WebApp(runtime: WebRuntime, catalog: CatalogRepository, generation: GenerationCounter, browseSettings: Flow<Settings>, recent: RecentQueryStore,
           history: WebHistory = remember { BrowserHistory() }, settingsRepository: SettingsRepository? = null,
           approvalOpener: WebApprovalOpener = WebApprovalBrowserOpener, trailerHost: WebTrailerHost = WebTrailerDocumentHost,
           trailerDialogMaxWidth: Dp = 1000.dp, historyBack: () -> Unit = ::browserHistoryBack) {
    val navigation = remember(history) { WebNavigation(history) }
    DisposableEffect(navigation) { onDispose { navigation.close() } }
    val entry by navigation.entry.collectAsState()
    val session by runtime.session.collectAsState()
    val uiScope = rememberCoroutineScope()
    // App lifetime, above key(entry): route changes never recreate it; closed only when the app root leaves.
    val approval = remember(runtime, approvalOpener) { WebApprovalOwner(runtime, approvalOpener, uiScope) }
    DisposableEffect(approval) { onDispose { approval.close() } }
    // The only caller of settingsHolder.signOut() and the only collector of its signOutResults.
    val account = remember(runtime) { WebAccountOwner(runtime, uiScope) }
    DisposableEffect(account) { onDispose { account.close() } }
    val accountState by account.state.collectAsState()
    // Return hints are the runtime's soft session reads; forwarding them to the approval owner would busy it on every tab switch.
    val signInFocus = remember { FocusRequester() }
    val homeFocus = remember { FocusRequester() }
    val modalFocus = remember { WebModalFocus(homeFocus) }
    val approvalState by approval.state.collectAsState()
    // Identity follows the authoritative session (openRate requires holder identity == the rendered session).
    val libraryOutcomes = remember(runtime) { MutableSharedFlow<LibraryWriteOutcome>(extraBufferCapacity = 8) }
    val libraryActions = remember(runtime) { runtime.libraryActions(uiScope) { libraryOutcomes.tryEmit(it) } }
    // Remembered: Detail keys its owner on this function, so it must stay the same instance across recompositions.
    val libraryStateWithOrigin = remember(runtime) { runtime::libraryStateWithOrigin }
    LaunchedEffect(libraryActions) {
        runtime.session.collect { (it as? WebSessionState.Ready)?.let { ready -> libraryActions.onIdentity(ready.session) } }
    }
    val contextInvalidations = runtime.contextInvalidations
    var contextRevision by remember(runtime) { mutableLongStateOf(0L) }
    LaunchedEffect(runtime) { runtime.contextInvalidations.collect { contextRevision++ } }
    var trailer by remember { mutableStateOf<Triple<WebEntry, List<Video>, Video>?>(null) }
    LaunchedEffect(entry) { if (trailer?.first !== entry) trailer = null }
    val shownTrailer = trailer?.takeIf { it.first === entry }
    val trailerOpen = shownTrailer != null
    // The trailer is the web Player: the rate prompt and import offers wait (holders) while it is up.
    LaunchedEffect(libraryActions, account, trailerOpen) {
        libraryActions.setPlayerActive(trailerOpen)
        account.setPlayerActive(trailerOpen)
    }
    val ratePrompt by libraryActions.ratePrompt.collectAsState(null)
    val accountDialog = accountState.signOut != WebSignOutPhase.Closed || accountState.importPrompt != null || accountState.importNotice != null
    val storage by runtime.storageFailure.collectAsState()
    val settingsState by runtime.settingsHolder.state.collectAsState()
    val settings = settingsState?.settings
    val appearance = settings?.let {
        AppearanceSettings(it.motion, it.glow, it.haptics, com.techgv.cineverse.ui.haptics.HapticStrength.valueOf(it.strength.name))
    } ?: AppearanceSettings(reduceMotion = true, ambientGlow = false, haptics = false)
    val searchScope = rememberCoroutineScope()
    val searchOwner = if (settings != null && entry.route is WebRoute.Search) remember(entry) {
        WebSearchOwner(entry, catalog, generation, browseSettings, recent, searchScope, navigation::navigate, runtime::reportStorageFailure)
    } else null
    val searchEnabled = entry.route !is WebRoute.Search || searchOwner != null
    LaunchedEffect(searchOwner, entry.route) {
        val route = entry.route as? WebRoute.Search
        if (searchOwner != null && route != null) searchOwner.holder.setInput(route.query, route.searchFacet())
    }
    DisposableEffect(searchOwner) { onDispose { searchOwner?.dispose() } }
    var headerSubmission by remember { mutableStateOf<WebEntry?>(null) }
    LaunchedEffect(entry, searchOwner) {
        if (headerSubmission === entry && searchOwner != null) { headerSubmission = null; searchOwner.submit() }
        else if (headerSubmission !== entry) headerSubmission = null
    }
    val settingsFlow = remember(runtime) { runtime.settingsHolder.state.mapNotNull { it?.settings } }
    CineVerseTheme(ShellStyle.Web, appearance) {
        ProvideWindowClass(Modifier.fillMaxSize()) {
            WebShell {
                val toasts = LocalToasts.current
                LaunchedEffect(toasts) { libraryOutcomes.collect { outcome -> outcome.webToast()?.let(toasts::show) } }
                val searchFocus = remember { FocusRequester() }
                var searchFocused by remember { mutableStateOf(false) }
                var mainSearchFocused by remember(entry) { mutableStateOf(false) }
                val footerLinks = remember(navigation) {
                    fun link(label: String, route: WebRoute) = FooterLink(label) { navigation.navigate(route) }
                    FooterLinks(
                        browse = listOf(link("Popular movies", WebRoute.Browse("movie", "popular")), link("Upcoming", WebRoute.Browse("movie", "upcoming")),
                            link("TV airing today", WebRoute.Browse("tv", "airing_today")), link("Popular people", WebRoute.People)),
                        you = listOf(link("Watchlist", WebRoute.Library()), link("Ratings", WebRoute.Library("ratings")), link("Settings", WebRoute.Settings)),
                    )
                }
                // Page-level ambient glow (Settings › Ambient glow); the Home hero paints its own backdrop over it.
                WebAmbientGlow()
                CompositionLocalProvider(LocalFooterLinks provides footerLinks, LocalWebModalFocus provides modalFocus) {
                Column(Modifier.fillMaxSize().onPreviewKeyEvent {
                    if (searchEnabled && !searchFocused && !mainSearchFocused && it.type == KeyEventType.KeyDown && it.key == Key.Slash && !it.isCtrlPressed && !it.isMetaPressed) {
                        searchFocus.requestFocus(); true
                    } else false
                }) {
                    WebHeader(entry.route, session, searchFocus, navigation::navigate, { searchFocused = it },
                        searchOwner?.query, { searchOwner?.change(it) }, {
                            if (searchOwner != null) searchOwner.submit()
                            else if (entry.route !is WebRoute.Search) {
                                navigation.navigate(WebRoute.Search(it))
                                headerSubmission = navigation.entry.value
                            }
                        }, searchEnabled = searchEnabled, signIn = approval::show, signInFocus = signInFocus, homeFocus = homeFocus,
                        modalOpen = trailerOpen || accountDialog || ratePrompt != null || approvalState.phase != WebApprovalPhase.Closed)
                    WebStatus(session, storage.takeUnless { settings != null && entry.route.ownsStorageNotice(session, settingsRepository != null) }, runtime::revalidateSession, runtime::retryStorage)
                    if (settings == null) {
                        if (storage == null) WebLoadingSkeleton("Reading saved settings…")
                        else WebPage(Modifier.padding(vertical = 24.dp)) {
                            Text("Saved settings are unavailable. Retry storage to continue.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    } else key(entry) {
                        if (entry.route == WebRoute.Home) {
                            WebHome(entry, catalog, generation, settingsFlow, session, runtime, navigation::navigate,
                                playTrailer = { videos, video -> trailer = Triple(entry, videos, video) }, signIn = approval::show)
                        } else if (entry.route is WebRoute.Browse || entry.route == WebRoute.People) {
                            WebBrowse(entry, catalog, generation, browseSettings, runtime, navigation::navigate)
                        } else if (searchOwner != null) {
                            WebSearch(searchOwner, storage, {
                                runtime.retryStorage()
                                searchOwner.holder.retrySettings()
                                searchOwner.holder.retryRecentRead()
                            }, navigation::navigate) { mainSearchFocused = it }
                        } else when (val route = entry.route) {
                            is WebRoute.Detail -> WebDetail(entry, catalog, generation, browseSettings, contextInvalidations, runtime,
                                library = libraryActions, accountState = libraryStateWithOrigin, navigate = { navigation.navigate(it) }, back = historyBack,
                                playTrailer = { _, videos, video -> trailer = Triple(entry, videos, video) })
                            is WebRoute.Person -> WebPerson(entry, catalog, generation, browseSettings, runtime.storageFailure, contextRevision,
                                runtime::retryStorage, runtime::reportStorageFailure,
                                { ref -> navigation.navigate(WebRoute.Detail(ref.mediaType.path, ref.id)) }, historyBack)
                            is WebRoute.Season -> WebSeason(entry, catalog, browseSettings, runtime.storageFailure, generation, contextInvalidations,
                                runtime::retryStorage, runtime::reportStorageFailure, historyBack,
                                { id -> navigation.navigate(WebRoute.Detail("tv", id)) }, { id, number -> navigation.navigate(WebRoute.Season(id, number)) })
                            is WebRoute.Library -> WebLibrary(entry, runtime, libraryResultIdentity, libraryResultPage) { navigation.navigate(it) }
                            WebRoute.Settings -> if (settingsRepository == null) WebPlaceholder(route, navigation::navigate)
                            // WebSettingsOwner passes the authoritative runtime Ready.session (never settingsHolder/auth state).
                            else WebSettings(entry, settingsRepository, runtime, approval::show, onSignOut = account::startSignOut, onImport = account::startImport)
                            else -> WebPlaceholder(route, navigation::navigate)
                        }
                    }
                }
                // One root dialog at a time: trailer, then account (sign-out/import), then rate, then approval.
                if (shownTrailer != null) key(entry, shownTrailer.third) {
                    val restore = remember { modalFocus.restorer(WebModalKind.Trailer) }
                    WebTrailerDialog(shownTrailer.second, shownTrailer.third, autoplay = false, onClose = { trailer = null }, restoreFocus = homeFocus, host = trailerHost, maxWidth = trailerDialogMaxWidth, onRestoreFocus = restore)
                } else if (accountDialog) {
                    val restore = remember { modalFocus.restorer(WebModalKind.Account) }
                    WebSignOutDialog(account, homeFocus, restore)
                    WebImportDialog(account, homeFocus, restore) // Hides itself while the sign-out dialog is open.
                } else if (ratePrompt != null) {
                    val restore = remember { modalFocus.restorer(WebModalKind.Rate) }
                    WebRateDialog(libraryActions, homeFocus, restore)
                } else if (approvalState.phase != WebApprovalPhase.Closed) {
                    val restore = remember { modalFocus.restorer(WebModalKind.Approval) }
                    WebApprovalDialog(approval, signInFocus, restore)
                }
                }
            }
        }
    }
}

/** §7.12 honest copy: "Synced" only for a TMDB-confirmed write, otherwise this browser's guest library. Superseded: none. */
private fun LibraryWriteOutcome.webToast(): ToastMessage? = when (val done = result) {
    is LibraryWriteResult.Done -> ToastMessage(
        when (val change = write) {
            LibraryWrite.ToggleWatchlist -> if (done.state.watchlist) "Added “${title.title}” to Watchlist" else "Removed from Watchlist"
            LibraryWrite.ToggleFavourite -> if (done.state.favourite) "Added “${title.title}” to Favourites" else "Removed from Favourites"
            is LibraryWrite.Rate -> "Rated ${title.title} ${oneDecimal(change.value)} / 10"
            LibraryWrite.Unrate -> "Rating removed"
        },
        when {
            done.removedFromWatchlist -> "Synced · TMDB removed it from your watchlist"
            done.synced -> "Synced to your TMDB account"
            else -> "Saved in this browser · sign in to sync"
        },
        if (write is LibraryWrite.Rate) CvIcons.StarFilled else CvIcons.Check,
    )
    is LibraryWriteResult.Failed -> ToastMessage("Couldn't save · not retried", title.title, CvIcons.Close)
    LibraryWriteResult.Superseded -> null
}

/** Routes whose component renders its own runtime storage notice (Library only once the session is verified). */
private fun WebRoute.ownsStorageNotice(session: WebSessionState, hasSettings: Boolean): Boolean = when (this) {
    WebRoute.Home, is WebRoute.Browse, WebRoute.People, is WebRoute.Search, is WebRoute.Detail, is WebRoute.Person -> true
    WebRoute.Settings -> hasSettings
    is WebRoute.Library -> session is WebSessionState.Ready
    else -> false
}

@JsFun("() => history.back()")
private external fun browserHistoryBack()

@Composable
internal fun WebStatus(session: WebSessionState, storage: BrowserStorageFailure?, retrySession: () -> Unit, retryStorage: () -> Unit) {
    if (session == WebSessionState.Checking || session is WebSessionState.Failed || storage != null) WebPage(Modifier.padding(top = 8.dp)) {
        if (session == WebSessionState.Checking) Text("Checking session…", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = Tokens.Colors.TextSecondary)
        if (session is WebSessionState.Failed) ErrorState("Session verification failed. Account actions are unavailable.", retrySession, Modifier.padding(vertical = 8.dp))
        if (storage != null) ErrorState(storageMessage(storage), retryStorage, Modifier.padding(vertical = 8.dp))
    }
}

internal fun storageMessage(failure: BrowserStorageFailure): String = "Last reported storage error: " + when (failure) {
    BrowserStorageFailure.Unsupported -> "persistent storage unsupported. No temporary library is used."
    BrowserStorageFailure.Blocked -> "blocked access. Check browser storage access, then retry reads."
    BrowserStorageFailure.Quota -> "quota exhausted. Retry reads; writes are not replayed."
    BrowserStorageFailure.Corrupt -> "unreadable saved data. Nothing has been reset."
    BrowserStorageFailure.Closed -> "storage closed. Retry reads."
    BrowserStorageFailure.Aborted -> "operation interrupted. Writes are not replayed."
    BrowserStorageFailure.Unavailable -> "storage unavailable. Saved data has not been reset."
}

@Composable
internal fun WebStorageNotice(failure: BrowserStorageFailure, retry: () -> Unit, modifier: Modifier = Modifier) {
    ErrorState(storageMessage(failure), retry, modifier.padding(8.dp))
}

@Composable
internal fun WebHeader(route: WebRoute, session: WebSessionState, searchFocus: FocusRequester, navigate: (WebRoute, Boolean) -> Unit,
                       searchFocused: (Boolean) -> Unit = {}, searchQuery: String? = null, searchChange: (String) -> Unit = {}, searchSubmit: (String) -> Unit = {}, searchEnabled: Boolean = true,
                       signIn: () -> Unit = { navigate(WebRoute.Settings, false) }, signInFocus: FocusRequester? = null, homeFocus: FocusRequester? = null, modalOpen: Boolean = false) {
    val focus = LocalFocusManager.current
    var fieldFocused by remember { mutableStateOf(false) }
    var query by remember(route) { mutableStateOf((route as? WebRoute.Search)?.query.orEmpty()) }
    var edited by remember(route) { mutableStateOf(false) }
    val displayed = searchQuery ?: query
    val candidate = WebRoute.Search(displayed, (route as? WebRoute.Search)?.facet ?: "all")
    val invalid = !candidate.representable()
    LaunchedEffect(query, edited) {
        if (edited && route !is WebRoute.Search && WebRoute.Search(query).representable()) {
            delay(300)
            navigate(WebRoute.Search(query), false)
        }
    }
    val change: (String) -> Unit = {
        if (searchEnabled) {
            if (route is WebRoute.Search) searchChange(it) else { query = it; edited = true }
        }
    }
    val submit = { if (searchEnabled && !invalid) searchSubmit(displayed) }
    BoxWithConstraints(webHeaderFocusBridge(homeFocus, modalOpen, fieldFocused).fillMaxWidth().onPreviewKeyEvent { event ->
        if (fieldFocused && event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
            focus.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
            focusWebCanvasAfterInput()
            true
        } else false
    }.background(HeaderFill).drawBehind {
        drawLine(Color.White.copy(alpha = 0.07f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
    }) {
        val narrow = maxWidth <= 860.dp
        Column(Modifier.fillMaxWidth().webGutter()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (narrow) 8.dp else 6.dp)) {
                Row((homeFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .cvClickable({ navigate(WebRoute.Home, false) }, onClickLabel = "CineVerse home", focusShape = RoundedCornerShape(11.dp))
                    .then(if (narrow) Modifier.semantics { contentDescription = "CineVerse home" } else Modifier)
                    .padding(end = if (narrow) 0.dp else 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LogoMark(Modifier.dropShadow(RoundedCornerShape(11.dp), Shadow(24.dp, Tokens.Colors.Accent, (-8).dp, DpOffset(0.dp, 8.dp))))
                    if (!narrow) Text("CineVerse", style = displayStyle(22.sp))
                }
                if (!narrow) {
                    NavigationMenu("Movies", listOf("Popular" to "popular", "Now playing" to "now_playing", "Upcoming" to "upcoming", "Top rated" to "top_rated").map { it.first to WebRoute.Browse("movie", it.second) }, navigate)
                    NavigationMenu("TV Shows", listOf("Popular" to "popular", "Airing today" to "airing_today", "On TV" to "on_the_air", "Top rated" to "top_rated").map { it.first to WebRoute.Browse("tv", it.second) }, navigate)
                    NavButton("People") { navigate(WebRoute.People, false) }
                    NavButton("My Library") { navigate(WebRoute.Library(), false) }
                }
                val field = Modifier.focusRequester(searchFocus).testTag("header-search-input").onFocusChanged { fieldFocused = it.isFocused; searchFocused(it.isFocused) }
                // Wide: the field sits at the right edge, at most 340dp, shrinking before the nav does. Narrow: it takes the row.
                if (narrow) WebSearchField(displayed, change, submit, field.weight(1f), invalid = invalid, enabled = searchEnabled)
                else Box(Modifier.weight(1f).padding(start = 10.dp), contentAlignment = Alignment.CenterEnd) {
                    WebSearchField(displayed, change, submit, field.widthIn(max = 340.dp).fillMaxWidth(), invalid = invalid, enabled = searchEnabled)
                }
                Box(Modifier.padding(start = if (narrow) 0.dp else 10.dp)) {
                    when (session) {
                        is WebSessionState.Ready -> if (session.session == null) {
                            val approvalTrigger = rememberWebModalTrigger(WebModalKind.Approval)
                            PillButton("Sign in", { approvalTrigger.open(signIn) }, approvalTrigger.modifier.then(signInFocus?.let { Modifier.focusRequester(it) } ?: Modifier), small = true)
                        } else NavigationMenu(session.session!!.displayName.ifBlank { "Account" }, listOf("Watchlist" to WebRoute.Library(), "Favourites" to WebRoute.Library("favourites"), "Ratings" to WebRoute.Library("ratings"), "Settings" to WebRoute.Settings), navigate)
                        WebSessionState.Checking -> Text("Verifying", style = MaterialTheme.typography.bodySmall, color = Tokens.Colors.TextSecondary)
                        is WebSessionState.Failed -> Text("Unverified", style = MaterialTheme.typography.bodySmall, color = Tokens.Colors.TextSecondary)
                    }
                }
                if (narrow) NavigationMenu("Menu", listOf(
                    "Home" to WebRoute.Home, "Movies" to WebRoute.Browse("movie"), "TV Shows" to WebRoute.Browse("tv"),
                    "People" to WebRoute.People, "My Library" to WebRoute.Library(), "Settings" to WebRoute.Settings,
                ), navigate, iconOnly = true)
            }
            if (!searchEnabled) Text("Search is unavailable until saved settings are loaded. Your URL query and facet are unchanged.", Modifier.padding(bottom = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
            if (invalid) Text(SEARCH_URL_ERROR, Modifier.padding(bottom = 10.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** `.hdr`: rgba(9,8,18,.74) (Compose has no backdrop blur) over the page's ambient glow. */
private val HeaderFill = Color(9, 8, 18).copy(alpha = 0.74f)

/** Nav dropdown (`.dd` + `.menu`): a chevron link, or the phone hamburger ([iconOnly], named by [label]). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun NavigationMenu(label: String, links: List<Pair<String, WebRoute>>, navigate: (WebRoute, Boolean) -> Unit, iconOnly: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        if (iconOnly) RoundIconButton(CvIcons.Menu, label, { expanded = !expanded }, diameter = 42.dp, container = Color.White.copy(alpha = 0.08f))
        else NavButton(label, if (expanded) CvIcons.ChevronUp else CvIcons.ChevronDown) { expanded = !expanded }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(min = 230.dp).onPointerEvent(PointerEventType.Exit) { expanded = false },
            offset = DpOffset(0.dp, 8.dp), shape = RoundedCornerShape(16.dp), containerColor = MenuFill, border = BorderStroke(1.dp, Tokens.Colors.Stroke)) {
            links.forEach { (text, route) ->
                val source = remember { MutableInteractionSource() }
                DropdownMenuItem(text = { Text(text, style = bodyStyle(14.5.sp, FontWeight.Medium)) }, onClick = { expanded = false; navigate(route, false) },
                    interactionSource = source, modifier = Modifier.focusRing(source, RoundedCornerShape(10.dp)))
            }
        }
    }
}

/** `.menu`: rgba(22,19,40,.98). */
private val MenuFill = Color(22, 19, 40).copy(alpha = 0.98f)

/** `.navlink`: 40dp tall (48dp target), radius 10, 15sp semibold, white 8% on hover, optional trailing chevron. */
@Composable
private fun NavButton(label: String, trailing: ImageVector? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.heightIn(min = 48.dp).cvClickable(onClick, focusShape = shape, pressedScale = 1f, interactionSource = source), contentAlignment = Alignment.Center) {
        Row(Modifier.clip(shape).background(if (hovered) Color.White.copy(alpha = 0.08f) else Color.Transparent).heightIn(min = 40.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, maxLines = 1, style = bodyStyle(15.sp, FontWeight.SemiBold))
            if (trailing != null) Icon(trailing, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun WebPlaceholder(route: WebRoute, navigate: (WebRoute, Boolean) -> Unit) {
    Column(Modifier.fillMaxSize()) {
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
        WebPage(Modifier.padding(vertical = 44.dp)) {
            EmptyState(title, when (route) {
                WebRoute.NotFound -> "This URL is unknown or invalid. No account operation was performed."
                is WebRoute.Trailer -> "Open this trailer from its title page. No video is playing."
                else -> "This destination is not implemented in this preview. No account changes were made."
            }) { PillButton("Back to Home", { navigate(WebRoute.Home, false) }) }
        }
        Footer()
    }
}
