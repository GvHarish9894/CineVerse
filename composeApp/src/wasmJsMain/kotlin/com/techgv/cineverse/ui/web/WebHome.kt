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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.techgv.cineverse.data.storage.StorageException
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
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

/**
 * [playTrailer] opens the root trailer dialog with the validated YouTube list of the title whose Play-trailer event
 * arrived; [signIn] is the root's sign-in (approval) path.
 */
@Composable
internal fun WebHome(entry: WebEntry, catalog: CatalogRepository, generation: GenerationCounter, settings: Flow<Settings>, session: WebSessionState, runtime: WebRuntime,
                     navigate: (WebRoute, Boolean) -> Unit, playTrailer: (List<Video>, Video) -> Unit, signIn: () -> Unit) {
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
    // The title whose Play trailer was pressed (the holder's event carries only its name and video key).
    var requested by remember(holder) { mutableStateOf<Title?>(null) }
    var noTrailer by remember(holder) { mutableStateOf(emptySet<MediaRef>()) }
    LaunchedEffect(holder, visible) {
        if (!visible) return@LaunchedEffect
        holder.events.collect { event ->
            val title = requested
            when (event) {
                is TrailerEvent.Play -> {
                    val match = title?.takeIf { it.title == event.title }
                    val videos = match?.let { homeTrailerVideos(catalog, it, event.videoKey, runtime::reportStorageFailure) }
                    if (match == null || videos == null) toasts.show(ToastMessage("Could not find a trailer. Try again."))
                    else if (videos.isEmpty()) { noTrailer += match.ref; toasts.show(ToastMessage("No trailer available for ${event.title}")) }
                    else playTrailer(videos, videos.first { it.key == event.videoKey })
                }
                is TrailerEvent.Unavailable -> {
                    if (title?.title == event.title) noTrailer += title.ref
                    toasts.show(ToastMessage("No trailer available for ${event.title}"))
                }
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
    WebHomeContent(state, entry, session, { entry.putString("home.hero", null); holder.selectTrend(it) }, { holder.selectHero(it) }, holder::retry,
        { if (holder.canStartTrailer) { requested = it; holder.playTrailer(it) } }, { navigate(it, false) }, retryStorage, storage, signIn, noTrailer,
        canPlayTrailer = { holder.canStartTrailer })
}

/**
 * The full YouTube list (valid 11-character keys, trailer order) that contains [key], read through the catalog's
 * cached `/videos` requests the holder just made (settings language, then its `en-US` fallback). Empty when [key] is
 * not a playable entry; null when the read failed.
 */
private suspend fun homeTrailerVideos(catalog: CatalogRepository, title: Title, key: String, onStorageFailure: (StorageException) -> Unit): List<Video>? = try {
    listOf(null, ENGLISH).firstNotNullOfOrNull { language ->
        orderedYouTubeVideos(catalog.videos(title.mediaType, title.ref.id, language).results)
            .filter { it.key.matches(YOUTUBE_KEY) }
            .takeIf { videos -> videos.any { it.key == key } }
    }.orEmpty()
} catch (_: TmdbException) {
    null
} catch (failure: StorageException) {
    onStorageFailure(failure)
    null
}

private val YOUTUBE_KEY = Regex("[A-Za-z0-9_-]{11}")

@Composable
internal fun WebHomeContent(state: HomeUiState, saver: StateSaver, session: WebSessionState,
                            selectTrend: (TrendingWindow) -> Unit, selectHero: (Hero) -> Unit,
                            retry: (HomeRow) -> Unit, playTrailer: (Title) -> Unit, navigate: (WebRoute) -> Unit,
                            retryStorage: () -> Unit, storage: BrowserStorageFailure?, signIn: () -> Unit,
                            noTrailer: Set<MediaRef> = emptySet(), canPlayTrailer: () -> Boolean = { state.trailerLoading == null }) {
    val scroll = savedScroll(saver, "page")
    var popularTv by remember { mutableStateOf(saver.getBoolean("home.popularTv") ?: false) }
    LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("web-home").semantics {
        contentDescription = "Catalog status: " + HomeRow.entries.joinToString("; ") { row ->
            "${row.key}=" + when (state.rows[row]) {
                SectionState.Empty -> "empty"
                SectionState.Loading, null -> "loading"
                is SectionState.Content -> "content"
                is SectionState.Error -> "error"
            }
        }
    }, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (storage != null) item("storage-error") { WebPage(Modifier.padding(top = 16.dp)) { WebStorageNotice(storage, retryStorage, Modifier.testTag("home-storage-recovery")) } }
        if (state.settingsError != null) item("settings-error") {
            WebPage { ErrorState("Saved preferences could not be observed. Last loaded values are retained.", retryStorage, Modifier.testTag("home-settings-recovery")) }
        }
        if (state.genresError != null) item("genres-error") {
            WebPage { ErrorState("Genre names could not be read. Loaded content is retained.", retryStorage, Modifier.testTag("home-genres-recovery")) }
        }
        item("hero") { HomeHero(state, { saver.putString("home.hero", it.key); selectHero(it) }, playTrailer, navigate, noTrailer, canPlayTrailer) }
        item("trending") {
            HomeSection(HomeRow.Trending, state.rows[HomeRow.Trending] ?: SectionState.Loading, saver, retry, navigate) {
                SegmentedControl(listOf("Today", "This week"), if (state.trend == TrendingWindow.Day) 0 else 1,
                    { selectTrend(if (it == 0) TrendingWindow.Day else TrendingWindow.Week) }, Modifier.width(196.dp))
            }
        }
        item("popular") {
            val row = if (popularTv) HomeRow.PopularTv else HomeRow.PopularMovies
            HomeSection(row, state.rows[row] ?: SectionState.Loading, saver, retry, navigate, title = "What’s popular") {
                SegmentedControl(listOf("Movies", "On TV"), if (popularTv) 1 else 0,
                    { popularTv = it == 1; saver.putBoolean("home.popularTv", popularTv) }, Modifier.width(176.dp))
            }
        }
        item("trailers") {
            val upcoming = state.rows[HomeRow.Upcoming]
            val titles = (upcoming as? SectionState.Content)?.value?.items.orEmpty().filterIsInstance<Hero.OfTitle>().filter { it.title.ref !in noTrailer }.take(8)
            if (titles.isNotEmpty()) ScrollRow("Find a trailer · Upcoming movies", state = savedScroll(saver, "trailers")) {
                items(titles, key = { it.key }) { hero ->
                    val trigger = rememberWebModalTrigger(WebModalKind.Trailer)
                    VideoCard(hero.title.title, "Find a trailer", webPalette(hero.key), { if (canPlayTrailer()) trigger.open { playTrailer(hero.title) } }, trigger.modifier.testTag("home-trailer-${hero.title.ref.key}"), imageUrl = webImage(hero.title.backdropPath, "w780"))
                }
            }
        }
        if (session is WebSessionState.Ready && session.session == null) item("guest") { GuestCallToAction(signIn) }
        listOf(HomeRow.NowPlaying, HomeRow.Upcoming, HomeRow.PopularPeople, HomeRow.TopRatedMovies).forEach { row ->
            item(row.key) { HomeSection(row, state.rows[row] ?: SectionState.Loading, saver, retry, navigate) }
        }
        item("footer") { Footer(Modifier.padding(top = 56.dp)) }
    }
}

/** `.cta` "Build your watchlist": the guest's way into the root sign-in; the guest library itself stays local. */
@Composable
private fun GuestCallToAction(signIn: () -> Unit) {
    WebPage(Modifier.padding(top = 32.dp)) {
        val shape = RoundedCornerShape(Tokens.Radius.Dialog)
        FlowRow(
            Modifier.fillMaxWidth().clip(shape)
                .background(Brush.linearGradient(listOf(Tokens.Colors.Secondary.copy(alpha = 0.55f), Tokens.Colors.Accent.copy(alpha = 0.30f))))
                .border(1.dp, Tokens.Colors.Stroke, shape).padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).widthIn(min = 260.dp)) {
                Text("Build your watchlist", style = displayStyle(30.sp), modifier = Modifier.semantics { heading() })
                Text("Sign in with your TMDB account to sync your watchlist, favourites and ratings. Until then they are saved in this browser.",
                    Modifier.padding(top = 8.dp).widthIn(max = 560.dp), style = bodyStyle(16.sp).copy(lineHeight = 24.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.85f))
            }
            val trigger = rememberWebModalTrigger(WebModalKind.Approval)
            PillButton("Sign in with TMDB", { trigger.open(signIn) }, trigger.modifier, variant = ButtonVariant.White, small = true)
        }
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
private fun HomeSection(row: HomeRow, section: SectionState<PagedItems>, saver: StateSaver, retry: (HomeRow) -> Unit, navigate: (WebRoute) -> Unit,
                        title: String = row.title, trailing: @Composable RowScope.() -> Unit = {}) {
    when (section) {
        SectionState.Loading -> ScrollRow(title, headerTrailing = trailing) { items(5) { PosterCardSkeleton() } }
        SectionState.Empty -> WebPage {
            WebSectionHeader(title, Modifier.padding(bottom = 14.dp), trailing = trailing)
            EmptyState("No titles yet", "There are no results for this section.") { PillButton("Refresh ${row.title}", { retry(row) }, variant = ButtonVariant.Ghost) }
        }
        is SectionState.Error -> WebPage {
            WebSectionHeader(title, Modifier.padding(bottom = 14.dp), trailing = trailing)
            val message = homeSectionError(section.kind)
            if (section.kind.retryable) ErrorState(message, { retry(row) }, Modifier.testTag("home-${row.key}-recovery")) else Text(message, color = Tokens.Colors.TextSecondary)
        }
        is SectionState.Content -> Column {
            ScrollRow(title, state = savedScroll(saver, row.key), headerTrailing = trailing) {
                items(section.value.items, key = { it.key }) { hero ->
                    when (hero) {
                        is Hero.OfPerson -> PersonCard(hero.person.name, hero.person.knownForDepartment, webPalette(hero.key), { navigate(hero.route()) }, imageUrl = webImage(hero.person.profilePath, "w185"))
                        is Hero.OfTitle -> PosterCard(hero.title.title, formatDate(hero.title.releaseDate)?.let { if (row == HomeRow.Upcoming) "Coming $it" else it },
                            webPalette(hero.key), { navigate(hero.route()) }, modifier = Modifier.testTag("home-${row.key}-${hero.key}"), imageUrl = webImage(hero.title.posterPath, "w342"),
                            voteAverage = hero.title.voteAverage, voteCount = hero.title.voteCount)
                    }
                }
            }
            section.value.moreError?.let { error ->
                val message = "${homeSectionError(error)} Loaded titles are retained."
                WebPage {
                    if (error.retryable) ErrorState(message, { retry(row) }, Modifier.testTag("home-${row.key}-recovery")) else Text(message, color = Tokens.Colors.TextSecondary)
                }
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
private fun HomeHero(state: HomeUiState, select: (Hero) -> Unit, play: (Title) -> Unit, navigate: (WebRoute) -> Unit, noTrailer: Set<MediaRef>, canPlayTrailer: () -> Boolean) {
    val hero = state.hero
    val trending = (state.rows[HomeRow.Trending] as? SectionState.Content)?.value?.items.orEmpty()
    val picks = trending.take(5)
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
        Box(Modifier.fillMaxWidth().height(if (phone) 560.dp else 660.dp)) {
            if (hero != null) {
                val path = when (hero) { is Hero.OfTitle -> hero.title.backdropPath; is Hero.OfPerson -> hero.person.profilePath }
                PaletteArt(webImage(path, "w1280"), webPalette(hero.key), ArtStyle.Backdrop, Modifier.fillMaxSize())
                Box(Modifier.matchParentSize().webHeroScrim())
            }
            WebPage(Modifier.align(Alignment.CenterStart).padding(bottom = 40.dp)) {
                Column(Modifier.widthIn(max = 760.dp)) {
                    if (hero != null) {
                        HeroKicker(hero, trending.indexOfFirst { it.key == hero.key }, state.trend)
                        Text(hero.name(), Modifier.padding(top = 14.dp).semantics { heading() },
                            style = displayStyle(if (phone) 42.sp else if (width <= 1000.dp) 56.sp else Tokens.Typography.ScaleWeb.Hero, trackingEm = -0.035f).copy(lineHeight = 0.98.em),
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val meta = when (hero) {
                            // List items carry year and genre ids only: certification and runtime are omitted, never guessed.
                            is Hero.OfTitle -> listOfNotNull(yearOf(hero.title.releaseDate),
                                hero.title.genreIds.mapNotNull(state.genres::get).take(3).joinToString(" · ").ifEmpty { null }).joinToString(" • ")
                            is Hero.OfPerson -> hero.person.knownForDepartment.orEmpty()
                        }
                        FlowRow(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            if (hero is Hero.OfTitle) ScoreRing(hero.title.voteAverage, hero.title.voteCount, size = ScoreRingSize.Web)
                            if (meta.isNotEmpty()) Text(meta, style = bodyStyle(15.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.86f))
                        }
                        if (hero is Hero.OfTitle && hero.title.overview.isNotBlank()) {
                            Text(hero.title.overview, Modifier.padding(top = 16.dp).widthIn(max = 580.dp), maxLines = if (phone) 4 else 3, overflow = TextOverflow.Ellipsis,
                                style = bodyStyle(if (phone) 15.5.sp else 17.sp).copy(lineHeight = if (phone) 24.sp else 26.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.86f))
                        }
                        FlowRow(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (hero is Hero.OfTitle && hero.title.ref !in noTrailer) {
                                val finding = state.trailerLoading == hero.title.ref
                                val trigger = rememberWebModalTrigger(WebModalKind.Trailer)
                                PillButton(if (finding) "Finding trailer…" else "Play trailer", { if (canPlayTrailer()) trigger.open { play(hero.title) } }, trigger.modifier, icon = CvIcons.Play, enabled = state.trailerLoading == null)
                            }
                            PillButton("More info", { navigate(hero.route()) }, variant = ButtonVariant.Ghost, icon = CvIcons.Info)
                        }
                    } else {
                        Text("Discover your next favourite", style = displayStyle(if (phone) 42.sp else 56.sp), modifier = Modifier.semantics { heading() })
                        when (state.rows[HomeRow.Trending]) {
                            SectionState.Loading, null -> SkeletonLines(3, Modifier.padding(top = 20.dp).widthIn(max = 580.dp))
                            SectionState.Empty -> Text("No trending titles available.", Modifier.padding(top = 16.dp), color = Tokens.Colors.TextSecondary)
                            else -> Text("Trending titles are unavailable. Retry the Trending section below.", Modifier.padding(top = 16.dp), color = Tokens.Colors.TextSecondary)
                        }
                    }
                }
            }
            if (width > 1000.dp && picks.isNotEmpty()) Row(Modifier.align(Alignment.BottomEnd).padding(end = contentGutter(ShellStyle.Web, width), bottom = 34.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                picks.forEach { pick -> HeroPick(pick, pick.key == hero?.key) { select(pick) } }
            }
        }
    }
}

/** `.eyebrow`: accent dot + "MOVIE · #1 TRENDING THIS WEEK" (rank only when the hero is in the loaded trending row). */
@Composable
private fun HeroKicker(hero: Hero, index: Int, window: TrendingWindow) {
    val kind = when (hero) { is Hero.OfTitle -> if (hero.title.mediaType == MediaType.Movie) "Movie" else "TV show"; is Hero.OfPerson -> "Person" }
    val trend = (if (index >= 0) "#${index + 1} trending " else "Trending ") + if (window == TrendingWindow.Day) "today" else "this week"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Tokens.Colors.Accent))
        Text("$kind · $trend".uppercase(), style = bodyStyle(13.sp, FontWeight.Bold, trackingEm = 0.1f), color = Tokens.Colors.TextPrimary.copy(alpha = 0.82f))
    }
}

/** `.pick`: 156×88 thumbnail (white ring when shown) and title; auto-advance runs without a visible bar. */
@Composable
private fun HeroPick(pick: Hero, shown: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.width(156.dp).graphicsLayer { alpha = if (shown || hovered) 1f else 0.7f }
        .cvClickable(onClick, focusShape = shape, interactionSource = source).semantics { selected = shown }) {
        PaletteArt(webImage(when (pick) { is Hero.OfTitle -> pick.title.backdropPath; is Hero.OfPerson -> pick.person.profilePath }, "w300"), webPalette(pick.key), ArtStyle.Backdrop,
            Modifier.fillMaxWidth().height(88.dp).clip(shape).border(if (shown) 2.dp else 1.dp, if (shown) Color.White else Color.White.copy(alpha = 0.12f), shape))
        Text(pick.name(), Modifier.padding(top = 8.dp), style = bodyStyle(12.5.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun Hero.route(): WebRoute = when (this) { is Hero.OfTitle -> WebRoute.Detail(title.mediaType.path, title.ref.id); is Hero.OfPerson -> WebRoute.Person(person.id) }
private fun Hero.name() = when (this) { is Hero.OfTitle -> title.title; is Hero.OfPerson -> person.name }
private fun webPalette(key: String) = if (key.hashCode() % 2 == 0) ArtPalette(Tokens.Colors.Accent, Tokens.Colors.Secondary) else ArtPalette(Tokens.Colors.Secondary, Tokens.Colors.Accent)

/** Only public TMDB artwork reaches Coil's credential-free image client, never the /api transport. */
internal fun webImage(path: String?, size: String): String? = path?.takeIf { it.matches(Regex("/[A-Za-z0-9_\\-]+\\.(jpg|png|webp)")) }?.let { "$DEFAULT_IMAGE_BASE_URL$size$it" }
