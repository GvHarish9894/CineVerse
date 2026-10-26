package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.di.WebRuntime
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Scrims.webHeroScrim
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.displayStyle
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Explicit component seam; the root supplies raw (not defaulted/stateIn) settings, its runtime, optional
 * identity-bound LibraryActions, real navigation/back, and a trailer controller accepting the real Title/Video.
 * The root must supply reliable contextInvalidations for actual generation/entry/content-context events (not
 * a fake continuous GenerationCounter flow). Clicks independently recheck current authority. LibraryActions
 * still require separately certified private account-source binding; catalog metadata cannot certify it.
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
    library: LibraryActions?,
    navigate: (WebRoute) -> Unit,
    back: () -> Unit,
    playTrailer: (Title, Video) -> Unit,
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
    val owner = remember(entry, catalog, generation, settings, runtime, library) {
        WebDetailOwner(entry, catalog, generation, settings, runtime.session, runtime.storageFailure,
            runtime::retryStorage, runtime::reportStorageFailure, library, scope)
    }
    DisposableEffect(owner) { onDispose { owner.close() } }
    LaunchedEffect(owner, contextInvalidations) { contextInvalidations.collect { owner.contextChanged() } }
    val state by owner.state.collectAsState()
    val account by owner.holder.accountState.collectAsState()
    val session by runtime.session.collectAsState()
    val storage by runtime.storageFailure.collectAsState()
    WebDetailContent(entry, state, account, session, library != null, storage, owner::retryReads,
        owner::retryDetail, owner::act,
        { route -> owner.browse { navigate(route) } }, { owner.browse(back) },
        { title, video -> owner.browse { playTrailer(title, video) } })
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
    playTrailer: (Title, Video) -> Unit,
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
        item("back") { PillButton("← Back", back, Modifier.padding(horizontal = 16.dp), variant = ButtonVariant.Ghost) }
        if (storage != null) item("storage") { WebStorageNotice(storage, retryReads, Modifier.testTag("detail-storage-recovery")) }
        if (state.freshnessUnverified) item("preferences") {
            ErrorState(if (state.settingsFailed) "Saved preferences could not be read. Retained detail is stale and unverified."
                else "Detail replacement is not verified. Retained detail is stale; completion may come from cache.",
                retryReads, Modifier.padding(16.dp).testTag("detail-settings-recovery"))
        }
        when (val section = state.section) {
            SectionState.Loading -> if (!state.freshnessUnverified) item("loading") { Text("Loading title…", Modifier.padding(16.dp)) }
            SectionState.Empty -> item("empty") { Text("Title not found.", Modifier.padding(16.dp)) }
            is SectionState.Error -> item("error") { WebDetailError(section.kind, detail != null, retryDetail) }
            is SectionState.Content -> Unit
        }
        if (detail != null) {
            item("hero") { WebDetailHero(detail, account, actionable, { kind -> act(kind, renderedContext, renderedSession) }, playTrailer) }
            val people = (detail.cast + listOfNotNull(detail.leadCrew)).filter { it.personId > 0 }.distinctBy { it.personId to it.role }
            if (people.isNotEmpty()) item("cast") {
                ScrollRow("Cast and lead crew") {
                    items(people, key = { "${it.personId}:${it.role}" }) { person ->
                        PersonCard(person.name, person.role.takeIf { it.isNotBlank() }, detailPalette,
                            { navigate(WebRoute.Person(person.personId)) }, Modifier.testTag("detail-person-${person.personId}"),
                            webImage(person.profilePath, "w185"))
                    }
                }
            }
            val seasons = detail.seasons.filter { it.seasonNumber >= 0 }.distinctBy { it.seasonNumber }
            if (detail.ref.mediaType == MediaType.Tv && seasons.isNotEmpty()) item("seasons") {
                ScrollRow("Seasons") {
                    items(seasons, key = { it.seasonNumber }) { season ->
                        PosterCard(season.name.ifBlank { if (season.seasonNumber == 0) "Specials" else "Season ${season.seasonNumber}" },
                            season.airDate, detailPalette, { navigate(WebRoute.Season(detail.ref.id, season.seasonNumber)) },
                            Modifier.testTag("detail-season-${season.seasonNumber}"), imageUrl = webImage(season.posterPath, "w342"))
                    }
                }
            }
            if (detail.reviews.isNotEmpty() || detail.facts.isNotEmpty()) item("reviews-facts") { WebDetailReviewsAndFacts(detail) }
            val videos = detail.webDetailVideos()
            if (videos.isNotEmpty()) item("videos") {
                ScrollRow("Videos") {
                    items(videos, key = { it.key }) { video ->
                        VideoCard(video.name.ifBlank { video.type }, video.type.takeIf { it.isNotBlank() }, detailPalette,
                            { playTrailer(detail.toTitle(), video) }, Modifier.testTag("detail-video-${video.key}"),
                            webImage(detail.backdropPath, "w780"))
                    }
                }
            }
            val recommendations = detail.recommendations.filter { it.ref.id > 0 && it.mediaType == it.ref.mediaType }.distinctBy { it.ref }
            if (recommendations.isNotEmpty()) item("recommendations") {
                ScrollRow("Recommendations") {
                    items(recommendations, key = { "${it.mediaType.path}:${it.ref.id}" }) { title ->
                        PosterCard(title.title, title.releaseDate, detailPalette, { navigate(WebRoute.Detail(title.mediaType.path, title.ref.id)) },
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

@Composable
private fun WebDetailHero(detail: Detail, account: LibraryState, actionable: Boolean, act: (LibraryKind) -> Unit, playTrailer: (Title, Video) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val phone = maxWidth <= 640.dp
        Box(Modifier.fillMaxWidth()) {
            if (detail.backdropPath != null) PaletteArt(webImage(detail.backdropPath, "w1280"), detailPalette, ArtStyle.Backdrop, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().webHeroScrim())
            FlowRow(Modifier.widthIn(max = 1320.dp).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (detail.posterPath != null) PaletteArt(webImage(detail.posterPath, "w342"), detailPalette, ArtStyle.Poster,
                    Modifier.width(if (phone) 200.dp else 260.dp).aspectRatio(2f / 3f))
                Column(Modifier.widthIn(max = if (phone) 600.dp else 780.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(if (detail.ref.mediaType == MediaType.Movie) "Movie" else "TV Show", style = MaterialTheme.typography.labelLarge)
                    Text(detail.title, style = displayStyle(if (phone) 42.sp else 52.sp), modifier = Modifier.semantics { heading() })
                    detail.webDetailMetadata().takeIf { it.isNotBlank() }?.let { Text(it) }
                    if (detail.voteCount > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ScoreRing(detail.voteAverage, detail.voteCount)
                        Text("User score")
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(if (!actionable || !account.known) "Watchlist · unknown" else if (account.watchlist) "In watchlist" else "Add to watchlist",
                            { act(LibraryKind.WATCHLIST) }, enabled = actionable && account.canToggle(LibraryKind.WATCHLIST))
                        PillButton(if (!actionable || !account.known) "Favourite · unknown" else if (account.favourite) "Favourited" else "Favourite",
                            { act(LibraryKind.FAVOURITE) }, enabled = actionable && account.canToggle(LibraryKind.FAVOURITE), variant = ButtonVariant.Ghost)
                        PillButton(if (!actionable || !account.known) "Rating · unknown" else account.rating?.let { "Rated $it" } ?: "Rate",
                            { act(LibraryKind.RATING) }, enabled = actionable && account.canToggle(LibraryKind.RATING), variant = ButtonVariant.Ghost)
                        detail.webDetailVideos().firstOrNull()?.let { video -> PillButton("Play trailer", { playTrailer(detail.toTitle(), video) }) }
                    }
                    detail.webDetailTagline()?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    detail.webDetailOverview()?.let {
                        Text("Overview", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text(it)
                    }
                }
            }
        }
    }
}

@Composable
private fun WebDetailReviewsAndFacts(detail: Detail) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
        val reviewsWidth = if (maxWidth > 860.dp) maxWidth - 328.dp else maxWidth
        FlowRow(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (detail.reviews.isNotEmpty()) Column(Modifier.width(reviewsWidth), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionTitle("Reviews")
                detail.reviews.forEach { review ->
                    Panel(title = review.author) {
                        review.rating?.let { Text("Rating: $it") }
                        if (review.text.isNotBlank()) Text(review.text)
                    }
                }
            }
            if (detail.facts.isNotEmpty()) Column(Modifier.widthIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Details")
                detail.facts.filter { it.value.isNotBlank() }.forEach { fact ->
                    Text(fact.label, style = MaterialTheme.typography.labelLarge)
                    Text(fact.value)
                }
            }
        }
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
    if (kind.retryable) ErrorState(message, retry, Modifier.padding(16.dp).testTag("detail-catalog-recovery"))
    else Text(message, Modifier.padding(16.dp))
}
