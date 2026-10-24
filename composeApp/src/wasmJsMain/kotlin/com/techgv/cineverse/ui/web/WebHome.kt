package com.techgv.cineverse.ui.web

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.di.WebRuntime
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.adaptive.*
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Scrims.webHeroScrim
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.*
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun WebHome(entry: WebEntry, catalog: CatalogRepository, generation: GenerationCounter, settings: Flow<Settings>, session: WebSessionState, runtime: WebRuntime, navigate: (WebRoute, Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val holder = remember(entry) { HomeStateHolder(catalog, generation, settings, entry, scope, runtime::reportStorageFailure) }
    val storage by runtime.storageFailure.collectAsState()
    val retryStorage = {
        runtime.retryStorage()
        // Same-valued recovered preferences need not emit; retry this live owner's failed reads explicitly.
        holder.retryStorage()
    }
    val visible = rememberPageVisible()
    DisposableEffect(holder, visible) { holder.setActive(visible); onDispose { holder.setActive(false) } }
    val toasts = LocalToasts.current
    LaunchedEffect(holder, visible) {
        if (!visible) return@LaunchedEffect
        holder.events.collect { event ->
            when (event) {
                is TrailerEvent.Play -> navigate(WebRoute.Trailer(event.videoKey, event.title), false)
                is TrailerEvent.Unavailable -> toasts.show(ToastMessage("No trailer available for ${event.title}"))
                TrailerEvent.Failed -> toasts.show(ToastMessage("Could not find a trailer. Try again."))
            }
        }
    }
    val state by holder.uiState.collectAsState()
    LaunchedEffect(state.rows[HomeRow.Trending]) {
        val saved = entry.getString("home.hero")
        val titles = (state.rows[HomeRow.Trending] as? SectionState.Content)?.value?.items.orEmpty()
        (titles.firstOrNull { it.key == saved } ?: titles.firstOrNull())?.let { holder.selectHero(it) }
    }
    WebHomeContent(state, entry, session, { entry.putString("home.hero", null); holder.selectTrend(it) }, { holder.selectHero(it) }, holder::retry, holder::playTrailer, { navigate(it, false) }, retryStorage, storage)
}

@Composable
internal fun WebHomeContent(state: HomeUiState, saver: StateSaver, session: WebSessionState,
                            selectTrend: (TrendingWindow) -> Unit, selectHero: (Hero) -> Unit,
                            retry: (HomeRow) -> Unit, playTrailer: (Title) -> Unit, navigate: (WebRoute) -> Unit,
                            retryStorage: () -> Unit, storage: BrowserStorageFailure?) {
    val scroll = savedScroll(saver, "page")
    var popularTv by remember { mutableStateOf(saver.getBoolean("home.popularTv") ?: false) }
    LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("web-home"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (storage != null) item("storage-error") { WebStorageNotice(storage, retryStorage, Modifier.testTag("home-storage-recovery")) }
        if (state.settingsError != null) item("settings-error") {
            ErrorState("Saved preferences could not be observed. Last loaded values are retained.", retryStorage, Modifier.padding(16.dp).testTag("home-settings-recovery"))
        }
        if (state.genresError != null) item("genres-error") {
            ErrorState("Genre names could not be read. Loaded content is retained.", retryStorage, Modifier.padding(16.dp).testTag("home-genres-recovery"))
        }
        item("hero") { HomeHero(state, { saver.putString("home.hero", it.key); selectHero(it) }, playTrailer, navigate) }
        item("trending") {
            Column {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Today", state.trend == TrendingWindow.Day, { selectTrend(TrendingWindow.Day) })
                    Chip("This week", state.trend == TrendingWindow.Week, { selectTrend(TrendingWindow.Week) })
                }
                HomeSection(HomeRow.Trending, state.rows[HomeRow.Trending] ?: SectionState.Loading, saver, retry, navigate)
            }
        }
        item("popular") {
            Column {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Movies", !popularTv, { popularTv = false; saver.putBoolean("home.popularTv", false) })
                    Chip("On TV", popularTv, { popularTv = true; saver.putBoolean("home.popularTv", true) })
                }
                val row = if (popularTv) HomeRow.PopularTv else HomeRow.PopularMovies
                HomeSection(row, state.rows[row] ?: SectionState.Loading, saver, retry, navigate)
            }
        }
        item("trailers") {
            val upcoming = state.rows[HomeRow.Upcoming]
            val titles = (upcoming as? SectionState.Content)?.value?.items.orEmpty().filterIsInstance<Hero.OfTitle>().take(8)
            if (titles.isNotEmpty()) ScrollRow("Find a trailer · Upcoming movies", state = savedScroll(saver, "trailers")) {
                items(titles, key = { it.key }) { hero ->
                    VideoCard(hero.title.title, "Find a trailer", webPalette(hero.key), { playTrailer(hero.title) }, imageUrl = webImage(hero.title.backdropPath, "w780"))
                }
            }
        }
        if (session is WebSessionState.Ready && session.session == null) item("guest") {
            Panel(Modifier.padding(horizontal = 16.dp), "Make it yours") {
                Text("Sign in to manage your TMDB library. Account screens are coming in a later preview.")
                PillButton("Account preview", { navigate(WebRoute.Settings) }, Modifier.padding(top = 12.dp), variant = ButtonVariant.Ghost)
            }
        }
        listOf(HomeRow.NowPlaying, HomeRow.Upcoming, HomeRow.PopularPeople, HomeRow.TopRatedMovies).forEach { row ->
            item(row.key) { HomeSection(row, state.rows[row] ?: SectionState.Loading, saver, retry, navigate) }
        }
        item("footer") { Footer(browse = listOf(FooterLink("Movies") { navigate(WebRoute.Browse("movie")) }, FooterLink("TV Shows") { navigate(WebRoute.Browse("tv")) }, FooterLink("People") { navigate(WebRoute.People) }), you = listOf(FooterLink("My Library") { navigate(WebRoute.Library()) }, FooterLink("Settings") { navigate(WebRoute.Settings) })) }
    }
}

@Composable
private fun savedScroll(saver: StateSaver, key: String): LazyListState {
    val state = remember(saver, key) { LazyListState(saver.getInt("$key.index") ?: 0, saver.getInt("$key.offset") ?: 0) }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { (index, offset) ->
            saver.putInt("$key.index", index); saver.putInt("$key.offset", offset)
        }
    }
    return state
}

@Composable
private fun HomeSection(row: HomeRow, section: SectionState<PagedItems>, saver: StateSaver, retry: (HomeRow) -> Unit, navigate: (WebRoute) -> Unit) {
    when (section) {
        SectionState.Loading -> ScrollRow(row.title) { items(5) { PosterCardSkeleton() } }
        SectionState.Empty -> Column(Modifier.padding(16.dp)) { SectionTitle(row.title); EmptyState("No titles yet", "There are no results for this section.") { PillButton("Refresh ${row.title}", { retry(row) }) } }
        is SectionState.Error -> Column(Modifier.padding(16.dp)) {
            SectionTitle(row.title)
            val message = homeSectionError(section.kind)
            if (section.kind.retryable) ErrorState(message, { retry(row) }, Modifier.testTag("home-${row.key}-recovery")) else Text(message)
        }
        is SectionState.Content -> Column {
            ScrollRow(row.title, state = savedScroll(saver, row.key)) {
                items(section.value.items, key = { it.key }) { hero ->
                    when (hero) {
                        is Hero.OfPerson -> PersonCard(hero.person.name, hero.person.knownForDepartment, webPalette(hero.key), { navigate(hero.route()) }, imageUrl = webImage(hero.person.profilePath, "w185"))
                        is Hero.OfTitle -> PosterCard(hero.title.title, hero.title.releaseDate, webPalette(hero.key), { navigate(hero.route()) }, modifier = Modifier.testTag("home-${row.key}-${hero.key}"), imageUrl = webImage(hero.title.posterPath, "w342"), voteAverage = hero.title.voteAverage, voteCount = hero.title.voteCount, badge = if (row == HomeRow.Upcoming) hero.title.releaseDate else null)
                    }
                }
            }
            section.value.moreError?.let { error ->
                val message = "${homeSectionError(error)} Loaded titles are retained."
                if (error.retryable) ErrorState(message, { retry(row) }, Modifier.padding(16.dp).testTag("home-${row.key}-recovery")) else Text(message, Modifier.padding(16.dp))
            }
        }
    }
}

private fun homeSectionError(kind: SectionErrorKind): String = when (kind) {
    SectionErrorKind.Offline -> "Offline. Check your connection."
    SectionErrorKind.NotAvailable -> "This section is not available."
    SectionErrorKind.AppCredential -> "Catalog service configuration is unavailable."
    SectionErrorKind.SessionExpired -> "Session expired. Verification is required."
    SectionErrorKind.Generic -> "Could not load this section."
}

@Composable
private fun HomeHero(state: HomeUiState, select: (Hero) -> Unit, play: (Title) -> Unit, navigate: (WebRoute) -> Unit) {
    val hero = state.hero
    val picks = (state.rows[HomeRow.Trending] as? SectionState.Content)?.value?.items.orEmpty().take(5)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val visible = rememberPageVisible()
    val reduceMotion = LocalReduceMotion.current
    val progress = remember(hero?.key) { Animatable(0f) }
    LaunchedEffect(hero?.key, hovered, focused, visible, reduceMotion, picks) {
        if (!hovered && !focused && visible && !reduceMotion && picks.size > 1) {
            progress.animateTo(1f, tween(((1f - progress.value) * 8000).toInt()))
            progress.snapTo(0f)
            select(picks[(picks.indexOfFirst { it.key == hero?.key } + 1).mod(picks.size)])
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().hoverable(source).onFocusChanged { focused = it.hasFocus }.focusGroup()) {
        val width = maxWidth
        val phone = width <= 640.dp
        val gutter = contentGutter(ShellStyle.Web, maxWidth)
        Box(Modifier.fillMaxWidth().height(if (phone) 560.dp else 660.dp)) {
            if (hero != null) {
                val path = when (hero) { is Hero.OfTitle -> hero.title.backdropPath; is Hero.OfPerson -> hero.person.profilePath }
                PaletteArt(webImage(path, "w1280"), webPalette(hero.key), ArtStyle.Backdrop, Modifier.fillMaxSize())
            }
            Box(Modifier.matchParentSize().webHeroScrim())
            Column(Modifier.align(Alignment.CenterStart).padding(horizontal = gutter).widthIn(max = 660.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (hero != null) {
                    Text(if (hero is Hero.OfTitle) "${if (hero.title.mediaType == MediaType.Movie) "Movie" else "TV Show"} · Trending" else "Person · Trending", style = MaterialTheme.typography.labelLarge)
                    Text(hero.name(), style = displayStyle(if (phone) 42.sp else if (width <= 1000.dp) 56.sp else 76.sp), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    if (hero is Hero.OfTitle) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ScoreRing(hero.title.voteAverage, hero.title.voteCount)
                            Text(hero.title.releaseDate.orEmpty())
                        }
                        Text(hero.title.overview, maxLines = if (phone) 4 else 3, overflow = TextOverflow.Ellipsis)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (hero is Hero.OfTitle) PillButton(if (state.trailerLoading == hero.title.ref) "Finding trailer…" else "Find a trailer", { play(hero.title) }, enabled = state.trailerLoading == null)
                        PillButton("Open library", { navigate(WebRoute.Library()) }, variant = ButtonVariant.Ghost)
                        PillButton("More info", { navigate(hero.route()) }, variant = ButtonVariant.Ghost)
                    }
                } else {
                    Text("Discover your next favourite", style = displayStyle(if (phone) 42.sp else 56.sp), modifier = Modifier.semantics { heading() })
                    Text(when (state.rows[HomeRow.Trending]) { SectionState.Loading -> "Loading trending titles…"; SectionState.Empty -> "No trending titles available."; else -> "Trending titles are unavailable. Retry the Trending section below." })
                }
            }
            if (width > 1000.dp && picks.isNotEmpty()) Row(Modifier.align(Alignment.BottomEnd).padding(end = gutter, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                picks.forEach { pick -> Column(Modifier.width(156.dp)) {
                    LandscapeCard(pick.name(), null, webPalette(pick.key), { select(pick) }, modifier = if (pick.key == hero?.key) Modifier.border(2.dp, Color.White, RoundedCornerShape(16.dp)) else Modifier, imageUrl = webImage(when (pick) { is Hero.OfTitle -> pick.title.backdropPath; is Hero.OfPerson -> pick.person.profilePath }, "w300"), width = 156.dp)
                    if (pick.key == hero?.key) LinearProgressIndicator(progress = { if (reduceMotion) 1f else progress.value }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                } }
            }
        }
    }
}

internal fun Hero.route(): WebRoute = when (this) { is Hero.OfTitle -> WebRoute.Detail(title.mediaType.path, title.ref.id); is Hero.OfPerson -> WebRoute.Person(person.id) }
private fun Hero.name() = when (this) { is Hero.OfTitle -> title.title; is Hero.OfPerson -> person.name }
private fun webPalette(key: String) = if (key.hashCode() % 2 == 0) ArtPalette(Tokens.Colors.Accent, Tokens.Colors.Secondary) else ArtPalette(Tokens.Colors.Secondary, Tokens.Colors.Accent)

/** Only public TMDB artwork reaches Coil's credential-free image client, never the /api transport. */
internal fun webImage(path: String?, size: String): String? = path?.takeIf { it.matches(Regex("/[A-Za-z0-9_\\-]+\\.(jpg|png|webp)")) }?.let { "$DEFAULT_IMAGE_BASE_URL$size$it" }
