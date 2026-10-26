package com.techgv.cineverse.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.library.LibraryStateSnapshot
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.formatDate
import com.techgv.cineverse.data.tmdb.formatRuntime
import com.techgv.cineverse.data.tmdb.yearOf
import com.techgv.cineverse.di.WebRuntime
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Scrims.webDetailHeroScrim
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.bodyStyle
import com.techgv.cineverse.ui.theme.displayStyle
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Explicit component seam; the root supplies raw (not defaulted/stateIn) settings, its runtime, the optional actions
 * holder (identity forwarded by the root), a stable [accountState] producer of origin-carrying snapshots (repository
 * stateWithOrigin), real navigation/back, and a trailer controller accepting the Title, the full validated video
 * list and the clicked video. The root must supply reliable contextInvalidations for actual generation/entry/
 * content-context events (not a fake continuous GenerationCounter flow). Clicks independently recheck current
 * authority; account state is shown only for a snapshot whose source identity equals the rendered session.
 * No dialog/player/account orchestration is owned here.
 */
@Composable
fun WebDetail(
    entry: WebEntry,
    catalog: CatalogRepository,
    generation: GenerationCounter,
    settings: Flow<Settings>,
    contextInvalidations: Flow<Unit>,
    runtime: WebRuntime,
    library: LibraryActionsStateHolder?,
    accountState: (MediaRef) -> Flow<LibraryStateSnapshot>,
    navigate: (WebRoute) -> Unit,
    back: () -> Unit,
    playTrailer: (Title, List<Video>, Video) -> Unit,
) {
    val valid = remember(entry) {
        try { validateWebDetailEntry(entry); true } catch (_: IllegalArgumentException) { false }
    }
    if (!valid) {
        Column(Modifier.padding(24.dp)) {
            Text("Invalid title route or saved detail state. Saved state was not changed.")
            PillButton("Back", back)
        }
        return
    }
    val scope = rememberCoroutineScope()
    val owner = remember(entry, catalog, generation, settings, runtime, library, accountState) {
        WebDetailOwner(entry, catalog, generation, settings, runtime.session, runtime.storageFailure,
            runtime::retryStorage, runtime::reportStorageFailure, library, accountState, scope)
    }
    DisposableEffect(owner) { onDispose { owner.close() } }
    LaunchedEffect(owner, contextInvalidations) { contextInvalidations.collect { owner.contextChanged() } }
    val state by owner.state.collectAsState()
    val account by owner.account.collectAsState()
    val session by runtime.session.collectAsState()
    val storage by runtime.storageFailure.collectAsState()
    WebDetailContent(entry, state, account.webDetailAccount(session as? WebSessionState.Ready), session, library != null, storage,
        owner::retryReads, owner::retryDetail, owner::act,
        { route -> owner.browse { navigate(route) } }, { owner.browse(back) },
        { title, videos, video -> owner.browse { playTrailer(title, videos, video) } })
}

@Composable
internal fun WebDetailContent(
    entry: WebEntry,
    state: WebDetailReadState,
    account: LibraryState,
    session: WebSessionState,
    hasLibrary: Boolean,
    storage: BrowserStorageFailure?,
    retryReads: () -> Unit,
    retryDetail: () -> Unit,
    act: (LibraryKind, DetailReadContext?, WebSessionState.Ready?) -> Unit,
    navigate: (WebRoute) -> Unit,
    back: () -> Unit,
    playTrailer: (Title, List<Video>, Video) -> Unit,
) {
    val validated = remember(entry) { validateWebDetailEntry(entry) }
    val scroll = remember(entry) { LazyListState() }
    var restored by remember(entry) { mutableStateOf(false) }
    val detail = ((state.section as? SectionState.Content)?.value ?: state.retained)?.takeIf { it.ref == validated.ref }
    LaunchedEffect(scroll, detail != null) {
        if (detail == null || restored) return@LaunchedEffect
        // scrollToItem awaits layout and remeasures real sections, never the temporary Loading/Error list.
        scroll.scrollToItem(validated.index, validated.offset)
        restored = true
    }
    LaunchedEffect(scroll, restored) {
        if (restored) snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { (index, offset) ->
            entry.putInt("detail.index", index); entry.putInt("detail.offset", offset)
        }
    }
    val renderedContext = state.producer.request
    val renderedSession = session as? WebSessionState.Ready
    val actionable = state.section is SectionState.Content && !state.freshnessUnverified && storage == null &&
        renderedSession != null && hasLibrary && renderedContext != null &&
        state.producer.completedWebDetail(validated.ref, renderedContext.appGeneration) != null
    LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("web-detail"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // History back: the origin page is not known here, so the link never claims one.
        item("back") { WebPage { WebBackLink("Back", back, Modifier.padding(top = 12.dp)) } }
        if (storage != null) item("storage") { WebPage { WebStorageNotice(storage, retryReads, Modifier.testTag("detail-storage-recovery")) } }
        if (state.freshnessUnverified) item("preferences") {
            WebPage {
                ErrorState(if (state.settingsFailed) "Saved preferences could not be read. Retained detail is stale and unverified."
                    else "Detail replacement is not verified. Retained detail is stale; completion may come from cache.",
                    retryReads, Modifier.testTag("detail-settings-recovery"))
            }
        }
        when (val section = state.section) {
            SectionState.Loading -> if (!state.freshnessUnverified) item("loading") { WebLoadingSkeleton("Loading title…") }
            SectionState.Empty -> item("empty") { WebPage { Text("Title not found.") } }
            is SectionState.Error -> item("error") { WebPage { WebDetailError(section.kind, detail != null, retryDetail) } }
            is SectionState.Content -> Unit
        }
        if (detail != null) {
            item("hero") {
                WebDetailHero(detail, account, actionable, { kind -> act(kind, renderedContext, renderedSession) }, playTrailer,
                    { id -> navigate(WebRoute.Person(id)) })
            }
            if (detail.hasWebDetailBody()) item("body") { WebDetailBody(detail, navigate, playTrailer) }
            val recommendations = detail.recommendations.filter { it.ref.id > 0 && it.mediaType == it.ref.mediaType }.distinctBy { it.ref }
            if (recommendations.isNotEmpty()) item("recommendations") {
                ScrollRow("Recommendations") {
                    items(recommendations, key = { "${it.mediaType.path}:${it.ref.id}" }) { title ->
                        PosterCard(title.title, formatDate(title.releaseDate), detailPalette, { navigate(WebRoute.Detail(title.mediaType.path, title.ref.id)) },
                            Modifier.testTag("detail-recommendation-${title.mediaType.path}-${title.ref.id}"),
                            imageUrl = webImage(title.posterPath, "w342"), voteAverage = title.voteAverage, voteCount = title.voteCount)
                    }
                }
            }
        }
        item("footer") {
            Footer(browse = listOf(FooterLink("Movies") { navigate(WebRoute.Browse("movie")) }, FooterLink("TV Shows") { navigate(WebRoute.Browse("tv")) }, FooterLink("People") { navigate(WebRoute.People) }),
                you = listOf(FooterLink("My Library") { navigate(WebRoute.Library()) }, FooterLink("Settings") { navigate(WebRoute.Settings) }))
        }
    }
}

private val detailPalette = ArtPalette(Tokens.Colors.Accent, Tokens.Colors.Secondary)

private fun Detail.webDetailCast() = cast.filter { it.personId > 0 }.distinctBy { it.personId to it.role }

/** TV "Current season": the latest numbered season, else Specials when that is all there is. */
private fun Detail.webDetailCurrentSeason(): SeasonSummary? = if (ref.mediaType != MediaType.Tv) null else
    seasons.filter { it.seasonNumber >= 0 }.let { all -> all.filter { it.seasonNumber > 0 }.maxByOrNull { it.seasonNumber } ?: all.firstOrNull() }

/** Aside rows: the mapped facts minus genres (already on the facts line), in the kit's order. */
private val asideOrder = listOf("Status", "Original language", "Runtime", "Certification", "Release date", "First air date", "Network", "Type", "Episodes")
private fun Detail.webDetailAside() = facts.filter { it.value.isNotBlank() && it.label != "Genres" }
    .sortedBy { asideOrder.indexOf(it.label).let { i -> if (i < 0) asideOrder.size else i } }

private fun Detail.hasWebDetailBody() = webDetailCast().isNotEmpty() || webDetailCurrentSeason() != null || reviews.isNotEmpty() ||
    webDetailVideos().isNotEmpty() || webDetailAside().isNotEmpty()

@Composable
private fun WebDetailHero(detail: Detail, account: LibraryState, actionable: Boolean, act: (LibraryKind) -> Unit,
                          playTrailer: (Title, List<Video>, Video) -> Unit, openPerson: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        if (detail.backdropPath != null) PaletteArt(webImage(detail.backdropPath, "w1280"), detailPalette, ArtStyle.Backdrop, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().webDetailHeroScrim())
        WebPage(Modifier.padding(top = 16.dp, bottom = 40.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val phone = maxWidth <= 640.dp
                val side = maxWidth >= 300.dp + 44.dp + 440.dp
                val posterShape = RoundedCornerShape(22.dp)
                val poster = @Composable {
                    if (detail.posterPath != null) PaletteArt(webImage(detail.posterPath, "w342"), detailPalette, ArtStyle.Poster,
                        Modifier.width(if (phone) 200.dp else 300.dp).aspectRatio(2f / 3f)
                            .dropShadow(posterShape, Shadow(60.dp, detailPalette.c1.copy(alpha = 0.55f), (-24).dp, DpOffset(0.dp, 40.dp)))
                            .clip(posterShape))
                }
                val info = @Composable { modifier: Modifier -> WebDetailInfo(detail, account, actionable, act, playTrailer, openPerson, phone, modifier) }
                if (side) Row(horizontalArrangement = Arrangement.spacedBy(44.dp)) { poster(); info(Modifier.weight(1f)) }
                else Column(verticalArrangement = Arrangement.spacedBy(28.dp)) { poster(); info(Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun WebDetailInfo(detail: Detail, account: LibraryState, actionable: Boolean, act: (LibraryKind) -> Unit,
                          playTrailer: (Title, List<Video>, Video) -> Unit, openPerson: (Int) -> Unit, phone: Boolean, modifier: Modifier) {
    Column(modifier) {
        val title = buildAnnotatedString {
            append(detail.title)
            detail.year?.let { withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = Tokens.Colors.TextPrimary.copy(alpha = 0.6f))) { append(" ($it)") } }
        }
        Text(title, style = displayStyle(if (phone) 36.sp else 52.sp, trackingEm = -0.03f).copy(lineHeight = 1.05.em), modifier = Modifier.semantics { heading() })
        WebDetailFactsLine(detail, Modifier.padding(top = 12.dp))
        FlowRow(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            if (detail.voteCount > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScoreRing(detail.voteAverage, detail.voteCount, size = ScoreRingSize.Large)
                Text("User score", Modifier.width(56.dp).padding(end = 8.dp), style = bodyStyle(15.sp, FontWeight.Bold).copy(lineHeight = 17.sp))
            }
            val known = actionable && account.known
            DetailActionButton(if (known && account.watchlist) CvIcons.BookmarkFilled else CvIcons.Bookmark,
                if (!known) "Watchlist · unknown" else if (account.watchlist) "In watchlist" else "Add to watchlist",
                active = known && account.watchlist, Tokens.Colors.Accent, Tokens.Colors.OnAccent,
                enabled = actionable && account.canToggle(LibraryKind.WATCHLIST)) { act(LibraryKind.WATCHLIST) }
            DetailActionButton(if (known && account.favourite) CvIcons.HeartFilled else CvIcons.Heart,
                if (!known) "Favourite · unknown" else if (account.favourite) "Favourited" else "Favourite",
                active = known && account.favourite, favouriteColor, Color.White,
                enabled = actionable && account.canToggle(LibraryKind.FAVOURITE)) { act(LibraryKind.FAVOURITE) }
            DetailActionButton(if (known && account.rating != null) CvIcons.StarFilled else CvIcons.Star,
                if (!known) "Rating · unknown" else account.rating?.let { "Rated $it" } ?: "Rate",
                active = known && account.rating != null, Tokens.Colors.Gold, Tokens.Colors.OnGold,
                enabled = actionable && account.canToggle(LibraryKind.RATING)) { act(LibraryKind.RATING) }
            val videos = detail.webDetailVideos()
            videos.firstOrNull()?.let { video -> PillButton("Play trailer", { playTrailer(detail.toTitle(), videos, video) }, icon = CvIcons.Play) }
        }
        detail.webDetailTagline()?.let {
            Text(it, Modifier.padding(top = 24.dp), style = bodyStyle(17.sp).copy(fontStyle = FontStyle.Italic), color = Tokens.Colors.TextPrimary.copy(alpha = 0.72f))
        }
        detail.webDetailOverview()?.let {
            Text("Overview", Modifier.padding(top = 16.dp).semantics { heading() }, style = displayStyle(20.sp, FontWeight.Bold))
            Text(it, Modifier.padding(top = 8.dp).widthIn(max = 760.dp), style = bodyStyle(16.5.sp).copy(lineHeight = 26.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.9f))
        }
        // The model carries one lead crew credit (Director / Creator); no Screenplay/Producer rows are invented.
        detail.leadCrew?.takeIf { it.personId > 0 }?.let { crew ->
            Column(Modifier.padding(top = 22.dp).widthIn(min = 180.dp).cvClickable({ openPerson(crew.personId) }, onClickLabel = "Open ${crew.name}")
                .testTag("detail-person-${crew.personId}")) {
                Text(crew.name, style = bodyStyle(15.sp, FontWeight.Bold))
                if (crew.role.isNotBlank()) Text(crew.role, Modifier.padding(top = 2.dp), style = bodyStyle(13.5.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.7f))
            }
        }
    }
}

private val favouriteColor = Color(0xFFFF5C8A)

/** Kit `.circ`: 50dp round icon button, filled with the state colour when active; the label is its tooltip. */
@Composable
private fun DetailActionButton(icon: ImageVector, label: String, active: Boolean, activeContainer: Color, activeContent: Color,
                               enabled: Boolean, onClick: () -> Unit) {
    RoundIconButton(icon, label, onClick, diameter = 50.dp, container = if (active) activeContainer else Color(0xFF1C1933),
        content = if (active) activeContent else Color.White, enabled = enabled)
}

/** Kit `.facts-line`: outlined certification badge, then date • genres • runtime; unknown parts are omitted. */
@Composable
private fun WebDetailFactsLine(detail: Detail, modifier: Modifier) {
    val cert = detail.certification?.takeIf { it.isNotBlank() }
    val facts = listOfNotNull(
        formatDate(detail.releaseDate),
        detail.genres.filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() },
        detail.runtimeMinutes?.takeIf { it > 0 }?.let(::formatRuntime),
    ).joinToString(" • ")
    if (cert == null && facts.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        cert?.let {
            Text(it, Modifier.border(1.5.dp, Tokens.Colors.TextPrimary.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                style = bodyStyle(13.sp, FontWeight.SemiBold))
        }
        if (facts.isNotEmpty()) Text(facts, style = bodyStyle(15.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.85f))
    }
}

/** Kit `.split`: main column (cast, current season, reviews, videos) and the facts aside; the aside stacks below at ≤ 860. */
@Composable
private fun WebDetailBody(detail: Detail, navigate: (WebRoute) -> Unit, playTrailer: (Title, List<Video>, Video) -> Unit) {
    WebPage {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val aside = detail.webDetailAside()
            if (maxWidth > 860.dp) Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                WebDetailMain(detail, navigate, playTrailer, Modifier.weight(1f))
                if (aside.isNotEmpty()) WebDetailAside(aside, Modifier.width(300.dp))
            } else Column(verticalArrangement = Arrangement.spacedBy(30.dp)) {
                WebDetailMain(detail, navigate, playTrailer, Modifier.fillMaxWidth())
                if (aside.isNotEmpty()) WebDetailAside(aside, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun WebDetailMain(detail: Detail, navigate: (WebRoute) -> Unit, playTrailer: (Title, List<Video>, Video) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(30.dp)) {
        val cast = detail.webDetailCast()
        if (cast.isNotEmpty()) Column {
            WebSectionHeader("Top billed cast")
            LazyRow(contentPadding = PaddingValues(top = 14.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(cast, key = { "${it.personId}:${it.role}" }) { person ->
                    PersonCard(person.name, person.role.takeIf { it.isNotBlank() }, detailPalette,
                        { navigate(WebRoute.Person(person.personId)) }, Modifier.testTag("detail-person-${person.personId}"),
                        webImage(person.profilePath, "w185"), width = 150.dp)
                }
            }
        }
        detail.webDetailCurrentSeason()?.let { season ->
            Column {
                WebSectionHeader("Current season")
                WebDetailSeasonCard(season, { navigate(WebRoute.Season(detail.ref.id, season.seasonNumber)) }, Modifier.padding(top = 14.dp))
            }
        }
        if (detail.reviews.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            WebSectionHeader("Reviews")
            // The mapped review carries no date: the date line stays empty rather than invented.
            detail.reviews.forEach { review -> ReviewCard(review.author, "", review.rating, review.text, maxLines = Int.MAX_VALUE) }
        }
        val videos = detail.webDetailVideos()
        if (videos.isNotEmpty()) Column {
            WebSectionHeader("Videos")
            LazyRow(contentPadding = PaddingValues(top = 14.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(videos, key = { it.key }) { video ->
                    VideoCard(video.name.ifBlank { video.type }, video.type.takeIf { it.isNotBlank() }, detailPalette,
                        { playTrailer(detail.toTitle(), videos, video) }, Modifier.testTag("detail-video-${video.key}"),
                        webImage(detail.backdropPath, "w780"), width = 300.dp)
                }
            }
        }
    }
}

/** Kit `.season-card`: 100dp poster, season name, year · episodes (only when known). */
@Composable
private fun WebDetailSeasonCard(season: SeasonSummary, open: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Row(modifier.fillMaxWidth().cvClickable(open, onClickLabel = "Open ${season.label}", focusShape = shape)
        .testTag("detail-season-${season.seasonNumber}")
        .clip(shape).background(Color.White.copy(alpha = 0.05f)).border(1.dp, Color.White.copy(alpha = 0.06f), shape).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        PaletteArt(webImage(season.posterPath, "w185"), detailPalette, ArtStyle.Poster,
            Modifier.width(100.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f)) {
            Text(season.name.ifBlank { season.label }, style = displayStyle(22.sp))
            listOfNotNull(yearOf(season.airDate), season.episodeCount.takeIf { it > 0 }?.let { if (it == 1) "1 episode" else "$it episodes" })
                .joinToString(" · ").takeIf { it.isNotEmpty() }
                ?.let { Text(it, Modifier.padding(top = 4.dp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.7f)) }
        }
    }
}

@Composable
private fun WebDetailAside(facts: List<Fact>, modifier: Modifier) {
    Panel(modifier.testTag("detail-aside")) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) { facts.forEach { FactTile(it.label, it.value) } }
    }
}

@Composable
private fun WebDetailError(kind: SectionErrorKind, retained: Boolean, retry: () -> Unit) {
    val message = when (kind) {
        SectionErrorKind.Offline -> "Offline. Could not load this title."
        SectionErrorKind.NotAvailable -> "Title not found or unavailable."
        SectionErrorKind.AppCredential -> "Catalog service configuration is unavailable."
        SectionErrorKind.SessionExpired -> "Session verification is required."
        SectionErrorKind.Generic -> "Could not load this title."
    } + if (retained) " Last loaded detail is retained, not a fresh result." else ""
    if (kind.retryable) ErrorState(message, retry, Modifier.testTag("detail-catalog-recovery"))
    else Text(message)
}
