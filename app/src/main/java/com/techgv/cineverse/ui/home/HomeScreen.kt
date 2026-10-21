package com.techgv.cineverse.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.flowOf
import com.techgv.cineverse.AppContainer
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.LocalLibraryActions
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.domain.key
import com.techgv.cineverse.ui.components.ActionButton
import com.techgv.cineverse.ui.components.ActionButtonStyle
import com.techgv.cineverse.ui.components.ActionIcon
import com.techgv.cineverse.ui.components.AmbientBackdrop
import com.techgv.cineverse.ui.components.Chip
import com.techgv.cineverse.ui.components.FocusRestorer
import com.techgv.cineverse.ui.components.FocusRow
import com.techgv.cineverse.ui.components.HeroBackdrop
import com.techgv.cineverse.ui.components.HeroInfo
import com.techgv.cineverse.ui.components.ImageSize
import com.techgv.cineverse.ui.components.LandscapeCard
import com.techgv.cineverse.ui.components.LocalToaster
import com.techgv.cineverse.ui.components.PagedItems
import com.techgv.cineverse.ui.components.PersonCard
import com.techgv.cineverse.ui.components.PinToStart
import com.techgv.cineverse.ui.components.PosterCard
import com.techgv.cineverse.ui.components.ScoreRing
import com.techgv.cineverse.ui.components.Section
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.ui.components.UiColors
import com.techgv.cineverse.ui.components.bleedStart
import com.techgv.cineverse.ui.components.parseTmdbDate
import com.techgv.cineverse.ui.components.rememberSavedLazyListState
import com.techgv.cineverse.ui.components.tmdbImageUrl
import com.techgv.cineverse.ui.nav.HomeBackHandle
import com.techgv.cineverse.ui.browse.route
import com.techgv.cineverse.ui.spikes.spikesMenuRoute
import kotlinx.coroutines.launch

private const val PANEL = "home"
private const val BUTTONS = "buttons"
private const val TREND_CHIPS = "trendChips"
private const val DEBUG = "debug"

/** Horizontal inset of Home content from the collapsed rail (prototype x 120 = rail 88 + 32). */
private val CONTENT_INSET = 32.dp

/**
 * §7.2 Home: hero stage following focus + rows. BACK steps 6–7 via [backHandle]: origin = the first hero
 * button. The hero's Watchlist toggle reads [LocalLibraryActions]; disabled until its state is known.
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpen: (Any) -> Unit,
    backHandle: HomeBackHandle,
) {
    val vm: HomeViewModel = viewModel {
        HomeViewModel(container.tmdbRepository, container.appGeneration, container.settingsRepository.settings, createSavedStateHandle())
    }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // RESUMED, not STARTED: during a NavHost exit transition Home stays STARTED under the new screen.
    LifecycleResumeEffect(vm) {
        vm.setActive(true)
        onPauseOrDispose { vm.setActive(false) }
    }
    LaunchedEffect(vm, lifecycle) {
        vm.events.flowWithLifecycle(lifecycle).collect { event ->
            when (event) {
                is HomeEvent.Open -> onOpen(event.route)
                is HomeEvent.Toast -> toaster.show(event.title, event.subtitle)
            }
        }
    }

    val restorer = remember(vm) { FocusRestorer(vm.focusMemory) }
    val outer = rememberSavedLazyListState(vm.focusMemory, PANEL)
    val rowStates = HomeRow.entries.associateWith { rememberSavedLazyListState(vm.focusMemory, it.key) }
    val scope = rememberCoroutineScope()
    val origin = remember { FocusRequester() }
    var originFocused by remember { mutableStateOf(false) }
    var buttonsFocused by remember { mutableStateOf(false) }
    var listFocused by remember { mutableStateOf(false) }
    val hero = state.hero
    val currentHero by rememberUpdatedState(hero)

    DisposableEffect(backHandle) {
        val unregister = backHandle.register(
            atOrigin = { originFocused || currentHero == null },
            focusOrigin = {
                scope.launch {
                    outer.scrollToItem(0)
                    withFrameNanos { }
                    runCatching { origin.requestFocus() }
                }
            },
        )
        onDispose { unregister() }
    }

    val buttonKeys = when (hero) {
        is Hero.OfTitle -> listOf("play", "watchlist", "info")
        is Hero.OfPerson -> listOf("profile")
        null -> emptyList()
    }
    val focusRows = buildList {
        add(FocusRow(PANEL, BUTTONS, buttonKeys))
        add(FocusRow(PANEL, TREND_CHIPS, TrendingWindow.entries.map { it.name }))
        HomeRow.entries.forEach { row ->
            val keys = (state.rows[row] as? SectionState.Content)?.value?.items?.map { it.key }.orEmpty()
            add(FocusRow(PANEL, row.key, keys, rowStates.getValue(row)))
        }
        if (spikesMenuRoute != null) add(FocusRow(PANEL, DEBUG, listOf(DEBUG)))
    }
    var restored by remember { mutableStateOf(false) }
    val settled = state.rows.values.none { it is SectionState.Loading }
    LaunchedEffect(settled) {
        if (settled && !restored) {
            restored = true
            restorer.restore(focusRows, outer)
        }
    }

    val artUrl = heroArtUrl(hero)
    val seed = hero?.key ?: "home"
    Box(Modifier.fillMaxSize().background(UiColors.Background)) {
        HeroBackdrop(artUrl, seed)
        // Ambient light falls over the artwork and its scrims alike (prototype `.amb-wrap` above `.bd`).
        AmbientBackdrop(artUrl, seed)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(70.dp))
            if (hero != null) {
                HeroText(hero, state, compact = listFocused && !buttonsFocused, Modifier.padding(start = CONTENT_INSET))
            }
            Spacer(Modifier.height(8.dp))
            // Vertical pin: the focused card's row title (42 dp) stays visible above it.
            PinToStart(leadingInset = 42.dp) {
                LazyColumn(
                    state = outer,
                    modifier = Modifier.weight(1f).fillMaxWidth().onFocusChanged { listFocused = it.hasFocus },
                    contentPadding = PaddingValues(bottom = 48.dp),
                ) {
                    item(BUTTONS) {
                        HeroButtons(
                            hero = hero,
                            trailerLoading = state.trailerLoading,
                            restorer = restorer,
                            keys = buttonKeys,
                            origin = origin,
                            onOriginFocus = { originFocused = it },
                            onPlay = vm::playTrailer,
                            onOpen = onOpen,
                            modifier = Modifier.onFocusChanged { buttonsFocused = it.hasFocus },
                        )
                    }
                    item(TREND_CHIPS) { TrendChips(state.trend, restorer, vm::selectTrend) }
                    HomeRow.entries.forEach { row ->
                        item(row.key) {
                            HomeRowSection(
                                row = row,
                                title = if (row == HomeRow.Trending) trendingTitle(state.trend) else row.title,
                                state = state.rows.getValue(row),
                                genres = state.genres,
                                restorer = restorer,
                                listState = rowStates.getValue(row),
                                focusRow = focusRows.first { it.rowKey == row.key },
                                onRetry = { vm.retry(row) },
                                onFocused = vm::onItemFocused,
                                onOpen = { onOpen(it.route()) },
                            )
                        }
                    }
                    val spikes = spikesMenuRoute
                    if (spikes != null) {
                        item(DEBUG) {
                            Row(Modifier.padding(start = CONTENT_INSET, top = 24.dp)) {
                                Chip("Spikes (debug)", false, { onOpen(spikes) }, with(restorer) { Modifier.rememberFocus(PANEL, DEBUG, DEBUG, 0) })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun trendingTitle(trend: TrendingWindow) = if (trend == TrendingWindow.Day) "Trending today" else "Trending this week"

/** Title: backdrop (poster when it has none); person: profile (§1.5 hero sizes). */
@Composable
private fun heroArtUrl(hero: Hero?): String? = when (hero) {
    is Hero.OfTitle -> tmdbImageUrl(hero.title.backdropPath, ImageSize.BACKDROP) ?: tmdbImageUrl(hero.title.posterPath, ImageSize.POSTER_HERO)
    is Hero.OfPerson -> tmdbImageUrl(hero.person.profilePath, ImageSize.PROFILE_HERO)
    null -> null
}

@Composable
private fun HeroText(hero: Hero, state: HomeUiState, compact: Boolean, modifier: Modifier) {
    when (hero) {
        is Hero.OfTitle -> {
            val title = hero.title
            val trending = (state.rows[HomeRow.Trending] as? SectionState.Content)?.value?.items.orEmpty()
            HeroInfo(
                eyebrow = titleEyebrow(title, trending.indexOfFirst { it.key == hero.key }, state.trend),
                title = title.title,
                meta = titleMeta(title, state.genres),
                overview = title.overview,
                compact = compact,
                modifier = modifier,
                ring = { ScoreRing(title.voteAverage, title.voteCount) },
            )
        }
        is Hero.OfPerson -> HeroInfo(
            eyebrow = hero.person.knownForDepartment ?: "Popular person",
            title = hero.person.name,
            meta = "",
            overview = personOverview(hero.person.knownFor.map { it.title }),
            compact = compact,
            modifier = modifier,
        )
    }
}

/** "Movie · #3 trending this week"; the rank only for items of the trending row (§7.2). */
fun titleEyebrow(title: Title, trendingIndex: Int, trend: TrendingWindow): String {
    val type = if (title.mediaType == MediaType.Movie) "Movie" else "TV series"
    if (trendingIndex < 0) return type
    val window = if (trend == TrendingWindow.Day) "today" else "this week"
    return "$type · #${trendingIndex + 1} trending $window"
}

/** "2024   •   Action · Drama" — year and up to three genre names known from the genre lists. */
fun titleMeta(title: Title, genres: Map<Int, String>): String = listOfNotNull(
    parseTmdbDate(title.releaseDate)?.year?.toString(),
    title.genreIds.mapNotNull(genres::get).take(3).joinToString(" · ").ifEmpty { null },
).joinToString("   •   ")

/** D5 person hero overview: "Known for A, B, C" from `known_for[]` (no extra fetch). */
fun personOverview(knownFor: List<String>): String =
    knownFor.filter { it.isNotBlank() }.take(3).takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Known for ").orEmpty()

@Composable
private fun HeroButtons(
    hero: Hero?,
    trailerLoading: MediaRef?,
    restorer: FocusRestorer,
    keys: List<String>,
    origin: FocusRequester,
    onOriginFocus: (Boolean) -> Unit,
    onPlay: (Title) -> Unit,
    onOpen: (Any) -> Unit,
    modifier: Modifier,
) {
    fun Modifier.button(key: String): Modifier {
        val base = with(restorer) { rememberFocus(PANEL, BUTTONS, key, keys.indexOf(key)) }
        return if (keys.firstOrNull() == key) base.focusRequester(origin).onFocusChanged { onOriginFocus(it.isFocused) } else base
    }
    Row(
        modifier.padding(start = CONTENT_INSET, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (hero) {
            is Hero.OfTitle -> {
                val title = hero.title
                ActionButton(
                    label = if (trailerLoading == title.ref) "Loading trailer…" else "Play trailer",
                    icon = ActionIcon.PLAY,
                    onClick = { onPlay(title) },
                    style = ActionButtonStyle.Solid,
                    enabled = trailerLoading == null,
                    modifier = Modifier.button("play"),
                )
                HeroWatchlistButton(title, Modifier.button("watchlist"))
                ActionButton("More info", ActionIcon.INFO, { onOpen(hero.route()) }, Modifier.button("info"))
            }
            is Hero.OfPerson -> ActionButton(
                "View profile", ActionIcon.USER, { onOpen(hero.route()) }, Modifier.button("profile"), style = ActionButtonStyle.Primary,
            )
            null -> Unit
        }
    }
}

/** §6: enabled only with a provider, a known state and no Watchlist write in flight. */
fun heroWatchlistEnabled(hasActions: Boolean, state: LibraryState): Boolean =
    hasActions && state.known && LibraryKind.WATCHLIST !in state.pending

/** Hero Watchlist toggle (§7.2) driven by the Library lane's [LocalLibraryActions]; see [heroWatchlistEnabled]. */
@Composable
private fun HeroWatchlistButton(title: Title, modifier: Modifier) {
    val actions = LocalLibraryActions.current
    val stateFlow = remember(actions, title.ref) { actions?.state(title.ref) ?: flowOf(LibraryState.Unknown) }
    val libraryState by stateFlow.collectAsStateWithLifecycle(LibraryState.Unknown)
    val onList = libraryState.watchlist
    ActionButton(
        label = if (onList) "On watchlist" else "Watchlist",
        icon = if (onList) ActionIcon.CHECK else ActionIcon.PLUS,
        onClick = { actions?.toggleWatchlist(title) },
        style = if (onList) ActionButtonStyle.On else ActionButtonStyle.Default,
        enabled = heroWatchlistEnabled(actions != null, libraryState),
        modifier = modifier,
    )
}

@Composable
private fun TrendChips(trend: TrendingWindow, restorer: FocusRestorer, onSelect: (TrendingWindow) -> Unit) {
    Column(Modifier.padding(start = CONTENT_INSET, top = 8.dp)) {
        RowTitle("Trending")
        Row(
            with(restorer) { Modifier.restoreRowFocus(PANEL, TREND_CHIPS) }.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TrendingWindow.entries.forEachIndexed { index, window ->
                Chip(
                    label = if (window == TrendingWindow.Day) "Today" else "This week",
                    selected = window == trend,
                    onClick = { onSelect(window) },
                    modifier = with(restorer) { Modifier.rememberFocus(PANEL, TREND_CHIPS, window.name, index) },
                )
            }
        }
    }
}

@Composable
private fun RowTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.height(28.dp), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun HomeRowSection(
    row: HomeRow,
    title: String,
    state: SectionState<PagedItems>,
    genres: Map<Int, String>,
    restorer: FocusRestorer,
    listState: LazyListState,
    focusRow: FocusRow,
    onRetry: () -> Unit,
    onFocused: (Hero) -> Unit,
    onOpen: (Hero) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(top = 8.dp)) {
        RowTitle(title, Modifier.padding(start = CONTENT_INSET))
        Section(state, onRetry, Modifier.padding(start = CONTENT_INSET)) { paged ->
            PinToStart(leadingInset = CONTENT_INSET) {
                LazyRow(
                    state = listState,
                    // Section pads its messages; the row bleeds back to the content edge so a scaled card is not clipped.
                    modifier = with(restorer) { Modifier.restoreRowFocus(focusRow, scope) }.bleedStart(CONTENT_INSET),
                    contentPadding = PaddingValues(start = CONTENT_INSET, end = CONTENT_INSET, top = 14.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    itemsIndexed(paged.items, key = { _, item -> item.key }) { index, item ->
                        val modifier = with(restorer) { Modifier.rememberFocus(PANEL, row.key, item.key, index) }
                            .onFocusChanged { if (it.isFocused) onFocused(item) }
                        HomeCard(item, row.card, genres, { onOpen(item) }, modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeCard(item: Hero, card: HomeCard, genres: Map<Int, String>, onClick: () -> Unit, modifier: Modifier) {
    when (item) {
        is Hero.OfPerson -> PersonCard(
            name = item.person.name,
            role = item.person.knownForDepartment.orEmpty(),
            profilePath = item.person.profilePath,
            seed = item.key,
            onClick = onClick,
            modifier = modifier,
        )
        is Hero.OfTitle -> if (card == HomeCard.Landscape) {
            // §7.2: network unknown from list data → first genre only.
            LandscapeCard(
                title = item.title.title,
                subtitle = item.title.genreIds.firstNotNullOfOrNull(genres::get).orEmpty(),
                imagePath = item.title.backdropPath,
                seed = item.key,
                onClick = onClick,
                modifier = modifier,
            )
        } else {
            PosterCard(item.title, onClick, modifier)
        }
    }
}
