package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.haptics.HapticKind
import com.techgv.cineverse.ui.haptics.LocalHaptics
import com.techgv.cineverse.ui.theme.displayStyle
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileHomeScreen(
    graph: MobileApplication,
    home: HomeStateHolder,
    scrollToTop: Int,
    navigator: MobileNavigator,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
) {
    val state by home.uiState.collectAsState()
    val settingsFailed by graph.settingsFailed.collectAsState()
    val identityFailed by graph.identityFailed.collectAsState()
    val storageFailed by graph.storageFailed.collectAsState()
    val scroll = rememberLazyListState()
    var handledScroll by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(scrollToTop) {
        if (scrollToTop != handledScroll) {
            scroll.scrollToItem(0)
            handledScroll = scrollToTop
        }
    }
    val titles = (state.rows[HomeRow.Trending] as? SectionState.Content)?.value?.items
        .orEmpty().filterIsInstance<Hero.OfTitle>().map { it.title }.take(5)
    val palette = mobilePalette(state.hero?.key ?: "home")
    Box(Modifier.fillMaxSize()) {
        AmbientGlow(palette)
        PullToRefreshBox(
            isRefreshing = state.rows.values.all { it == SectionState.Loading },
            onRefresh = { HomeRow.entries.forEach(home::retry) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.testTag("mobile-home"),
                state = scroll,
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item("hero") {
                    if (titles.isNotEmpty()) HomeHero(graph, home, state, titles, navigator)
                    else Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 72.dp, start = 20.dp, end = 20.dp)) {
                        SectionTitle("Discover something great")
                        Text("Movies, TV and the people who make them.")
                    }
                }
                if (settingsFailed) item("settings-error") {
                    ErrorState("Saved preferences unavailable; keeping the last loaded values.", graph::retrySettings, Modifier.padding(horizontal = 20.dp))
                }
                if (identityFailed) item("identity-error") {
                    ErrorState("Account state unavailable. Watchlist is disabled.", graph::retryIdentity, Modifier.padding(horizontal = 20.dp))
                }
                if (storageFailed) item("storage-error") {
                    ErrorState("Library storage unavailable. Retry reads (no writes replayed).", graph::retryLibraryStorage, Modifier.padding(horizontal = 20.dp))
                }
                HomeRow.entries.forEach { row ->
                    item(row.key) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (row == HomeRow.Trending) {
                                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Chip("Today", state.trend == TrendingWindow.Day, { home.selectTrend(TrendingWindow.Day) })
                                    Chip("This week", state.trend == TrendingWindow.Week, { home.selectTrend(TrendingWindow.Week) })
                                }
                            }
                            HomeSection(row, state.rows.getValue(row), graph.catalog.imageBaseUrl(), navigator,
                                onRetry = { home.retry(row) }, onMore = { home.loadMore(row) })
                        }
                    }
                    if (row == HomeRow.PopularMovies) item("popular-ten") {
                        // shortcut: ranks are positions in the loaded popular list, replace when a dedicated curated source exists.
                        val popular = (state.rows[row] as? SectionState.Content)?.value?.items.orEmpty().filterIsInstance<Hero.OfTitle>().take(10)
                        if (popular.isNotEmpty()) ScrollRow("10 Popular Movies") {
                            items(popular, key = { it.key }) { hero ->
                                Top10Card(popular.indexOf(hero) + 1, hero.title.title, mobilePalette(hero.key),
                                    { navigator.open(MobileDestination.Detail(hero.title.ref)) },
                                    imageUrl = imageUrl(graph.catalog.imageBaseUrl(), "w342", hero.title.posterPath))
                            }
                        }
                    }
                }
                item("attribution") { Footer() }
            }
        }
        GlassTopBar(
            solid = rememberTopBarSolid(scroll, 420.dp),
            navigation = { LogoMark(size = 30.dp); Text("CineVerse", style = displayStyle(20.sp)) },
            actions = {
                RoundIconButton(CvIcons.Search, "Search", onSearch)
                RoundIconButton(CvIcons.User, "Profile", onProfile)
            },
        )
    }
}

@Composable
private fun HomeHero(graph: MobileApplication, home: HomeStateHolder, state: HomeUiState, titles: List<Title>, navigator: MobileNavigator) {
    val pager = rememberPagerState(pageCount = { titles.size })
    val haptics = LocalHaptics.current
    LaunchedEffect(pager, titles) {
        var first = true
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            titles.getOrNull(page)?.let { home.selectHero(Hero.OfTitle(it)) }
            if (!first) haptics.perform(HapticKind.Tick)
            first = false
        }
    }
    Column {
        HorizontalPager(pager, modifier = Modifier.testTag("home-hero"), key = { titles[it].ref.key }) { page ->
            val title = titles[page]
            val palette = mobilePalette(title.ref.key)
            val identity by graph.identity.collectAsState()
            val library by remember(title.ref) { graph.actions.state(title.ref) }.collectAsState(LibraryState.Unknown)
            Box(Modifier.fillMaxWidth().heightIn(min = 610.dp)) {
                PaletteArt(imageUrl(graph.catalog.imageBaseUrl(), "w1280", title.backdropPath), palette, ArtStyle.Backdrop,
                    Modifier.matchParentSize(), bottomScrim = true)
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 76.dp, bottom = 18.dp, start = 20.dp, end = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PaletteArt(imageUrl(graph.catalog.imageBaseUrl(), "w342", title.posterPath), palette, ArtStyle.Poster,
                        Modifier.size(156.dp, 234.dp).clip(RoundedCornerShape(18.dp))) {
                        Text(title.title, Modifier.align(Alignment.BottomCenter).padding(12.dp), style = displayStyle(20.sp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        title.genreIds.mapNotNull(state.genres::get).take(2).forEach { MetaChip(it) }
                    }
                    Text(title.title, style = displayStyle(32.sp), textAlign = TextAlign.Center)
                    Text(titleMeta(title), textAlign = TextAlign.Center)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PillButton(if (state.trailerLoading == title.ref) "Loading…" else "Trailer",
                            { home.playTrailer(title) }, icon = CvIcons.Play, enabled = state.trailerLoading == null)
                        PillButton(if (!library.known || identity == null) "Watchlist unknown" else if (library.watchlist) "Saved" else "Watchlist",
                            { if (graph.identity.value != null) graph.actions.toggleWatchlist(title) },
                            variant = ButtonVariant.Glass, icon = if (library.watchlist) CvIcons.Check else CvIcons.Plus,
                            enabled = identity != null && library.known && LibraryKind.WATCHLIST !in library.pending)
                        RoundIconButton(CvIcons.Info, "Details for ${title.title}", { navigator.open(MobileDestination.Detail(title.ref)) })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentDescription = "Featured title ${pager.currentPage + 1} of ${titles.size}" },
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            repeat(titles.size) { index ->
                Box(Modifier.size(if (pager.currentPage == index) 22.dp else 6.dp, 6.dp).clip(CircleShape)
                    .background(if (pager.currentPage == index) Tokens.Colors.Accent else Color.White.copy(alpha = 0.3f)))
            }
        }
    }
}

@Composable
internal fun HomeSection(row: HomeRow, state: SectionState<PagedItems>, baseUrl: String, navigator: MobileNavigator,
                         onRetry: () -> Unit, onMore: () -> Unit) {
    val list = rememberLazyListState()
    when (state) {
        SectionState.Loading -> ScrollRow(row.title, state = list) { items(4) { PosterCardSkeleton() } }
        SectionState.Empty -> Column(Modifier.padding(horizontal = 20.dp)) { SectionTitle(row.title); Text("Nothing here yet.") }
        is SectionState.Error -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(row.title)
            SectionFailure(state.kind, onRetry)
        }
        is SectionState.Content -> ScrollRow(row.title, state = list,
            headerTrailing = { PillButton("See all", { navigator.open(MobileDestination.Browse(row)) }, variant = ButtonVariant.Ghost, small = true) }) {
            items(state.value.items, key = { it.key }) { hero ->
                val palette = mobilePalette(hero.key)
                when (hero) {
                    is Hero.OfPerson -> PersonCircle(hero.person.name, hero.person.knownForDepartment, palette,
                        { navigator.open(MobileDestination.Person(hero.person.id)) }, imageUrl = imageUrl(baseUrl, "w185", hero.person.profilePath))
                    is Hero.OfTitle -> {
                        val title = hero.title
                        val click = { navigator.open(MobileDestination.Detail(title.ref)) }
                        if (row.card == HomeCard.Landscape) LandscapeCard(title.title, titleMeta(title), palette, click,
                            imageUrl = imageUrl(baseUrl, "w780", title.backdropPath))
                        else PosterCard(title.title, titleMeta(title), palette, click,
                            imageUrl = imageUrl(baseUrl, "w342", title.posterPath), voteAverage = title.voteAverage, voteCount = title.voteCount,
                            tag = if (title.mediaType == MediaType.Tv) "TV" else null,
                            badge = title.releaseDate.takeIf { row == HomeRow.Upcoming })
                    }
                }
            }
            if (state.value.hasMore) item("paging") {
                Column(Modifier.width(180.dp).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val moreError = state.value.moreError
                    if (moreError != null) SectionFailure(moreError, onMore)
                    else PillButton(if (state.value.loadingMore) "Loading…" else "Load more", onMore, enabled = !state.value.loadingMore)
                }
            }
        }
    }
}

@Composable
private fun SectionFailure(kind: SectionErrorKind, retry: () -> Unit) {
    val message = when (kind) {
        SectionErrorKind.Offline -> "You're offline. Check your connection."
        SectionErrorKind.NotAvailable -> "Not available."
        SectionErrorKind.AppCredential -> "Catalog configuration unavailable."
        SectionErrorKind.SessionExpired -> "Sign-in expired."
        SectionErrorKind.Generic -> "Couldn't load this section."
    }
    if (kind.retryable) ErrorState(message, retry) else Text(message)
}

internal fun imageUrl(base: String, size: String, path: String?): String? =
    path?.takeIf { it.startsWith('/') && !it.startsWith("//") }?.let { "${base.trimEnd('/')}/$size$it" }

internal fun mobilePalette(key: String): ArtPalette {
    val hue = (key.hashCode().toLong() and 0x7fffffff).rem(360).toFloat()
    return ArtPalette(Color.hsv(hue, 0.65f, 0.65f), Color.hsv((hue + 45) % 360, 0.7f, 0.3f))
}

internal fun titleMeta(title: Title) = listOfNotNull(
    title.releaseDate?.take(4),
    if (title.mediaType == MediaType.Tv) "TV" else "Movie",
    if (title.voteCount > 0) "★ ${oneDecimal(title.voteAverage)}" else null,
).joinToString(" · ")
