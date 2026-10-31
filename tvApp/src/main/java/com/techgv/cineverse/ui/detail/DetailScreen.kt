package com.techgv.cineverse.ui.detail

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.flowOf
import com.techgv.cineverse.AppContainer
import com.techgv.cineverse.data.tmdb.formatRuntime
import com.techgv.cineverse.domain.Detail
import com.techgv.cineverse.domain.LibraryActions
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.LocalLibraryActions
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Review
import com.techgv.cineverse.domain.Video
import com.techgv.cineverse.ui.components.ActionButton
import com.techgv.cineverse.ui.components.ActionButtonStyle
import com.techgv.cineverse.ui.components.FactCard
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.FocusRestorer
import com.techgv.cineverse.ui.components.FocusRow
import com.techgv.cineverse.ui.components.LandscapeCard
import com.techgv.cineverse.ui.components.PersonCard
import com.techgv.cineverse.ui.components.PosterCard
import com.techgv.cineverse.ui.components.ReviewCard
import com.techgv.cineverse.ui.components.ScoreRing
import com.techgv.cineverse.ui.components.SavedFocus
import com.techgv.cineverse.ui.components.SeasonCard
import com.techgv.cineverse.ui.components.Section
import com.techgv.cineverse.presentation.SectionState
import com.techgv.cineverse.ui.components.DetailCardArt
import com.techgv.cineverse.ui.components.rememberSavedLazyListState
import com.techgv.cineverse.ui.components.rememberSharedMedia
import com.techgv.cineverse.ui.components.sharedBackdropKey
import com.techgv.cineverse.ui.components.sharedPosterKey
import com.techgv.cineverse.ui.components.tmdbImageUrl
import com.techgv.cineverse.ui.feedback.LocalFeedback
import com.techgv.cineverse.ui.nav.Route
import com.techgv.cineverse.presentation.DetailUiState
import com.techgv.cineverse.presentation.trailerOf
import com.techgv.cineverse.presentation.canToggle
import com.techgv.cineverse.presentation.episodesSeason

private const val PANEL = "main"

@Composable
fun DetailScreen(route: Route.Detail, container: AppContainer, onNavigate: (Route) -> Unit) {
    val vm: DetailViewModel = viewModel {
        DetailViewModel(
            route, createSavedStateHandle(), container.tmdbRepository, container.settingsRepository.settings,
            container.appGeneration,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    // Library lane's seam (§6 pins): null → library buttons disabled; its flow never gates the catalog render.
    val library = LocalLibraryActions.current
    val libraryState by remember(library, route) {
        library?.state(MediaRef(route.mediaType, route.id)) ?: flowOf(LibraryState.Unknown)
    }.collectAsStateWithLifecycle(LibraryState.Unknown)
    DetailContent(
        state = state,
        libraryState = libraryState,
        focusMemory = vm.focusMemory,
        imageBase = vm.imageBase,
        actions = DetailActions(onRetry = vm::retry, library = library, onNavigate = onNavigate),
        seed = route,
    )
}

class DetailActions(
    val onRetry: () -> Unit,
    val library: LibraryActions?,
    val onNavigate: (Route) -> Unit,
)

/** Player route for [video] of [detail] (§7.10): film title, video name as kicker; BACK pops back here and focus is restored. */
fun playerRoute(detail: Detail, video: Video) = trailerOf(detail.title, video.key, video.name).let { Route.Player(it.videoKey, title = it.title, kicker = it.kicker) }

@Composable
fun DetailContent(
    state: DetailUiState,
    libraryState: LibraryState,
    focusMemory: FocusMemory,
    imageBase: String,
    actions: DetailActions,
    seed: Route.Detail? = null,
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val detail = (state.detail as? SectionState.Content)?.value
        // One call site for seed and loaded detail: the hero artwork (shared-element target) is not remounted.
        when (val art = heroArt(detail, seed)) {
            null -> Section(state.detail, onRetry = actions.onRetry, modifier = Modifier.padding(SCREEN_INSET)) {}
            else -> DetailBody(detail, art, state.detail, libraryState, focusMemory, imageBase, actions)
        }
    }
}

/** The hero's artwork and name: the loaded [Detail]'s, else the route's display seed. */
internal class HeroArt(val ref: MediaRef, val title: String, val posterPath: String?, val backdropPath: String?)

internal fun heroArt(detail: Detail?, seed: Route.Detail?): HeroArt? = when {
    detail != null -> HeroArt(detail.ref, detail.title, detail.posterPath, detail.backdropPath)
    seed?.title != null -> HeroArt(MediaRef(seed.mediaType, seed.id), seed.title, seed.posterPath, seed.backdropPath)
    else -> null
}

internal class ButtonSpec(
    val key: String,
    val glyph: String,
    val label: String,
    val primary: Boolean = false,
    val on: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

internal fun detailButtons(detail: Detail, library: LibraryState, actions: DetailActions): List<ButtonSpec> {
    val title = detail.toTitle()
    val lib = actions.library?.takeIf { library.known }
    fun enabled(kind: LibraryKind) = library.canToggle(kind, hasLibrary = actions.library != null)
    return buildList {
        detail.videos.firstOrNull()?.let { trailer ->
            add(ButtonSpec("trailer", "▶", "Play trailer", primary = true) { actions.onNavigate(playerRoute(detail, trailer)) })
        }
        add(
            ButtonSpec(
                "watchlist", if (library.watchlist) "✓" else "+", if (library.watchlist) "On watchlist" else "Watchlist",
                on = library.watchlist, enabled = enabled(LibraryKind.WATCHLIST),
            ) { lib?.toggleWatchlist(title) },
        )
        add(
            ButtonSpec("favourite", "♥", "Favourite", on = library.favourite, enabled = enabled(LibraryKind.FAVOURITE)) {
                lib?.toggleFavourite(title)
            },
        )
        val rating = library.rating
        add(
            ButtonSpec(
                "rate", "★", if (rating != null) "Rated ${oneDecimal(rating)}" else "Rate",
                on = rating != null, enabled = enabled(LibraryKind.RATING),
            ) { lib?.openRate(title) },
        )
        detail.episodesSeason()?.let { first ->
            add(ButtonSpec("episodes", "☰", "Episodes") { actions.onNavigate(Route.Season(detail.ref.id, first)) })
        }
    }
}

@Composable
private fun DetailBody(
    detail: Detail?,
    art: HeroArt,
    pending: SectionState<Detail>,
    library: LibraryState,
    memory: FocusMemory,
    imageBase: String,
    actions: DetailActions,
) {
    val restorer = remember(memory) { FocusRestorer(memory) }
    val outer = rememberSavedLazyListState(memory, "outer")
    // Seeded hero only: a list of one item would clamp the saved scroll position, so it gets a throwaway state.
    val seedOuter = remember { LazyListState() }
    val buttons = if (detail != null) detailButtons(detail, library, actions) else emptyList()
    val crew = listOfNotNull(detail?.leadCrew)

    // Outer list item i is focus row i (restore scrolls the outer list to it).
    val rows = if (detail == null) emptyList() else listOfNotNull(
        RowSpec("buttons", buttons.map { it.key }),
        RowSpec("seasons", detail.seasons.map { "season:${it.seasonNumber}" }),
        RowSpec("cast", detail.cast.map { "cast:${it.personId}" } + crew.map { "crew:${it.personId}" }),
        RowSpec("videos", detail.videos.map { it.key }),
        RowSpec("recommendations", detail.recommendations.map { it.ref.key }),
        RowSpec("reviews", detail.reviews.map { it.id }),
        RowSpec("facts", detail.facts.map { it.label }),
    ).filter { it.itemKeys.isNotEmpty() }
    // The buttons are a plain Row: a LazyListState there would never be laid out and restore would hang on it.
    val rowStates = rows.drop(1).associate { row -> row.key to key(row.key) { rememberSavedLazyListState(memory, row.key) } }
    val focusRows = rows.map { FocusRow(PANEL, it.key, it.itemKeys, rowStates[it.key]) }
    fun focusRow(key: String) = focusRows.first { it.rowKey == key }
    fun avatarUrl(review: Review) = review.avatarUrl ?: tmdbImageUrl(imageBase, "w185", review.avatarPath)
    // Review reading panel (saved like the Season episode panel); closing it returns focus to its card.
    var openReview by rememberSaveable { mutableStateOf<String?>(null) }
    var refocusReview by remember { mutableStateOf<String?>(null) }
    fun closeReview() {
        refocusReview = openReview
        openReview = null
    }

    Backdrop(tmdbImageUrl(imageBase, "w1280", art.backdropPath), sharedBackdropKey(art.ref))
    RevealVertically(ROW_TOP_INSET) {
        LazyColumn(state = if (detail != null) outer else seedOuter, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            item(key = "buttons") {
                Hero(art, imageBase) {
                    if (detail != null) HeroInfo(detail, buttons, restorer, focusRow("buttons"), outer)
                    else HeroPending(art, pending, actions.onRetry)
                }
            }
            if (detail != null) for (row in rows.drop(1)) {
                item(key = row.key) {
                    when (row.key) {
                        "seasons" -> DetailRow("Seasons", restorer, focusRow(row.key), rowStates.getValue(row.key)) {
                            itemsIndexed(detail.seasons, key = { _, s -> s.seasonNumber }) { index, season ->
                                SeasonCard(
                                    season, tmdbImageUrl(imageBase, "w342", season.posterPath),
                                    onClick = { actions.onNavigate(Route.Season(detail.ref.id, season.seasonNumber)) },
                                    modifier = with(restorer) { Modifier.rememberFocus(PANEL, row.key, row.itemKeys[index], index) },
                                )
                            }
                        }
                        "cast" -> DetailRow("Top billed cast", restorer, focusRow(row.key), rowStates.getValue(row.key)) {
                            val people = detail.cast + crew
                            itemsIndexed(people, key = { index, _ -> row.itemKeys[index] }) { index, person ->
                                PersonCard(
                                    person.name, person.role, person.profilePath, seed = row.itemKeys[index],
                                    onClick = { actions.onNavigate(Route.Person(person.personId)) },
                                    modifier = with(restorer) { Modifier.rememberFocus(PANEL, row.key, row.itemKeys[index], index) },
                                )
                            }
                        }
                        "videos" -> DetailRow("Videos", restorer, focusRow(row.key), rowStates.getValue(row.key)) {
                            itemsIndexed(detail.videos, key = { _, v -> v.key }) { index, video ->
                                LandscapeCard(
                                    video.name, video.type, imagePath = null, seed = video.key,
                                    onClick = { actions.onNavigate(playerRoute(detail, video)) },
                                    modifier = with(restorer) { Modifier.rememberFocus(PANEL, row.key, video.key, index) },
                                    imageUrl = "https://i.ytimg.com/vi/${video.key}/hqdefault.jpg",
                                )
                            }
                        }
                        "recommendations" -> DetailRow("Recommendations", restorer, focusRow(row.key), rowStates.getValue(row.key)) {
                            itemsIndexed(detail.recommendations, key = { index, _ -> row.itemKeys[index] }) { index, title ->
                                PosterCard(
                                    title,
                                    onClick = { actions.onNavigate(Route.Detail(title.ref.mediaType, title.ref.id)) },
                                    modifier = with(restorer) { Modifier.rememberFocus(PANEL, row.key, row.itemKeys[index], index) },
                                )
                            }
                        }
                        "reviews" -> DetailRow("Reviews", restorer, focusRow(row.key), rowStates.getValue(row.key)) {
                            itemsIndexed(detail.reviews, key = { _, r -> r.id }) { index, review ->
                                ReviewCard(
                                    review, avatarUrl(review),
                                    onClick = { openReview = review.id },
                                    modifier = with(restorer) { Modifier.rememberFocus(PANEL, row.key, review.id, index) },
                                )
                            }
                        }
                        "facts" -> DetailRow("Details", restorer, focusRow(row.key), rowStates.getValue(row.key)) {
                            itemsIndexed(detail.facts, key = { _, f -> f.label }) { index, fact ->
                                FactCard(fact, modifier = with(restorer) { Modifier.rememberFocus(PANEL, row.key, fact.label, index) })
                            }
                        }
                    }
                }
            }
        }
    }
    // The saved id can outlive the review list (reload / process death): only an existing review opens the panel.
    val activeReview = detail?.reviews?.firstOrNull { it.id == openReview }
    activeReview?.let { review -> ReviewPanel(review, avatarUrl(review), ::closeReview) }
    BackHandler(enabled = activeReview != null, onBack = ::closeReview)
    LaunchedEffect(refocusReview) {
        val id = refocusReview ?: return@LaunchedEffect
        // Focus leaving the removed panel may land elsewhere first: pin the invoking card, then restore it.
        memory.save(SavedFocus(PANEL, "reviews", id, detail?.reviews.orEmpty().indexOfFirst { it.id == id }.coerceAtLeast(0)))
        restorer.restore(focusRows, outer)
        refocusReview = null
    }

    // §1.3: data of the current generation is composed → restore the saved focus (else the first button);
    // entered with the review panel open (process death), the panel takes focus instead. A seed is not data.
    LaunchedEffect(restorer, detail != null) { if (detail != null && activeReview == null) restorer.restore(focusRows, outer) }
}

private class RowSpec(val key: String, val itemKeys: List<String>)

@Composable
private fun Backdrop(url: String?, sharedKey: String) {
    if (url == null) return
    val background = MaterialTheme.colorScheme.background
    // Drawn in place during the transition: in the overlay it would cover the hero text until it lands.
    Box(rememberSharedMedia(sharedKey, renderInOverlay = false).fillMaxWidth().height(540.dp)) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.45f))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to background, 0.75f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to background)))
    }
}

/** Poster + [info] column; the poster is the same node for the seed and the loaded detail. */
@Composable
private fun Hero(art: HeroArt, imageBase: String, info: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = SCREEN_INSET, end = SCREEN_INSET, top = 40.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        DetailCardArt(
            tmdbImageUrl(imageBase, "w780", art.posterPath), art.title,
            rememberSharedMedia(sharedPosterKey(art.ref)).size(240.dp, 360.dp).clip(RoundedCornerShape(16.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { info() }
    }
}

@Composable
private fun HeroHeading(ref: MediaRef, title: String, network: String?) {
    Text(
        listOfNotNull(if (ref.mediaType == MediaType.Movie) "Movie" else "TV series", network).joinToString(" · "),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        title,
        fontSize = if (title.length > 17) 46.sp else 60.sp,
        lineHeight = if (title.length > 17) 50.sp else 64.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Seeded hero while the detail loads (or failed): the seed's name over the section's loading / error state. */
@Composable
private fun HeroPending(art: HeroArt, pending: SectionState<Detail>, onRetry: () -> Unit) {
    HeroHeading(art.ref, art.title, network = null)
    Section(pending, onRetry) {}
}

@Composable
private fun HeroInfo(
    detail: Detail,
    buttons: List<ButtonSpec>,
    restorer: FocusRestorer,
    buttonRow: FocusRow,
    outer: LazyListState,
) {
    val scope = rememberCoroutineScope()
    HeroHeading(detail.ref, detail.title, detail.network)
    if (detail.tagline.isNotBlank()) {
        Text(
            detail.tagline + if (detail.taglineInEnglish) "  (English)" else "",
            style = MaterialTheme.typography.titleMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val chips = listOfNotNull(detail.year, detail.runtimeMinutes?.let(::formatRuntime), detail.certification) + detail.genres
    if (chips.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips.forEach { DetailChip(it) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        ScoreRing(detail.voteAverage, detail.voteCount)
        Column {
            if (detail.overviewInEnglish) {
                Text("(English)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                detail.overview,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = with(restorer) { Modifier.restoreRowFocus(buttonRow, scope) }
            .revealHeroOnFocus(outer, scope, LocalFeedback.current.reduceMotion),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        buttons.forEachIndexed { index, b ->
            ActionButton(
                b.label, b.glyph, b.onClick,
                modifier = with(restorer) { Modifier.rememberFocus(PANEL, buttonRow.rowKey, b.key, index) },
                style = when {
                    b.primary -> ActionButtonStyle.Solid
                    b.on -> ActionButtonStyle.On
                    else -> ActionButtonStyle.Default
                },
                enabled = b.enabled,
            )
        }
    }
}
