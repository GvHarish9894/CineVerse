package com.techgv.cineverse.ui.detail

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.techgv.cineverse.domain.Video
import com.techgv.cineverse.ui.components.FactCard
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.FocusRestorer
import com.techgv.cineverse.ui.components.FocusRow
import com.techgv.cineverse.ui.components.ReviewCard
import com.techgv.cineverse.ui.components.SeasonCard
import com.techgv.cineverse.ui.components.Section
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.ui.components.DetailCardArt
import com.techgv.cineverse.ui.components.rememberSavedLazyListState
import com.techgv.cineverse.ui.components.tmdbImageUrl
import com.techgv.cineverse.ui.feedback.LocalFeedback
import com.techgv.cineverse.ui.nav.Route

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
    )
}

class DetailActions(
    val onRetry: () -> Unit,
    val library: LibraryActions?,
    val onNavigate: (Route) -> Unit,
)

/** Player route for [video] of [detail] (§7.10): BACK pops back here and focus is restored. */
fun playerRoute(detail: Detail, video: Video) = Route.Player(video.key, video.name, "${detail.title} · ${video.type}")

@Composable
fun DetailContent(
    state: DetailUiState,
    libraryState: LibraryState,
    focusMemory: FocusMemory,
    imageBase: String,
    actions: DetailActions,
) {
    Box(Modifier.screenFocusRoot().background(MaterialTheme.colorScheme.background)) {
        when (val section = state.detail) {
            is SectionState.Content -> DetailBody(section.value, libraryState, focusMemory, imageBase, actions)
            else -> Section(section, onRetry = actions.onRetry, modifier = Modifier.padding(SCREEN_INSET)) {}
        }
    }
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
    fun enabled(kind: LibraryKind) = lib != null && kind !in library.pending
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
        detail.seasons.firstOrNull()?.let { first ->
            add(ButtonSpec("episodes", "☰", "Episodes") { actions.onNavigate(Route.Season(detail.ref.id, first.seasonNumber)) })
        }
    }
}

@Composable
private fun DetailBody(detail: Detail, library: LibraryState, memory: FocusMemory, imageBase: String, actions: DetailActions) {
    val restorer = remember(memory) { FocusRestorer(memory) }
    val outer = rememberSavedLazyListState(memory, "outer")
    val buttons = detailButtons(detail, library, actions)
    val crew = listOfNotNull(detail.leadCrew)

    // Outer list item i is focus row i (restore scrolls the outer list to it).
    val rows = listOfNotNull(
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

    Backdrop(tmdbImageUrl(imageBase, "w1280", detail.backdropPath))
    RevealVertically(ROW_TOP_INSET) {
        LazyColumn(state = outer, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            item(key = "buttons") { Hero(detail, buttons, restorer, focusRow("buttons"), outer, imageBase) }
            for (row in rows.drop(1)) {
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
                                Lane2PersonCard(
                                    person.name, person.role, tmdbImageUrl(imageBase, "w185", person.profilePath),
                                    onClick = { actions.onNavigate(Route.Person(person.personId)) },
                                    modifier = with(restorer) { Modifier.rememberFocus(PANEL, row.key, row.itemKeys[index], index) },
                                )
                            }
                        }
                        "videos" -> DetailRow("Videos", restorer, focusRow(row.key), rowStates.getValue(row.key)) {
                            itemsIndexed(detail.videos, key = { _, v -> v.key }) { index, video ->
                                Lane2LandscapeCard(
                                    video.name, video.type, "https://i.ytimg.com/vi/${video.key}/hqdefault.jpg",
                                    onClick = { actions.onNavigate(playerRoute(detail, video)) },
                                    modifier = with(restorer) { Modifier.rememberFocus(PANEL, row.key, video.key, index) },
                                )
                            }
                        }
                        "recommendations" -> DetailRow("Recommendations", restorer, focusRow(row.key), rowStates.getValue(row.key)) {
                            itemsIndexed(detail.recommendations, key = { index, _ -> row.itemKeys[index] }) { index, title ->
                                Lane2PosterCard(
                                    title, tmdbImageUrl(imageBase, "w342", title.posterPath),
                                    onClick = { actions.onNavigate(Route.Detail(title.ref.mediaType, title.ref.id)) },
                                    modifier = with(restorer) { Modifier.rememberFocus(PANEL, row.key, row.itemKeys[index], index) },
                                )
                            }
                        }
                        "reviews" -> DetailRow("Reviews", restorer, focusRow(row.key), rowStates.getValue(row.key)) {
                            itemsIndexed(detail.reviews, key = { _, r -> r.id }) { index, review ->
                                ReviewCard(
                                    review, review.avatarUrl ?: tmdbImageUrl(imageBase, "w185", review.avatarPath),
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
    // §1.3: data of the current generation is composed → restore the saved focus (else the first button).
    LaunchedEffect(restorer) { restorer.restore(focusRows, outer) }
}

private class RowSpec(val key: String, val itemKeys: List<String>)

@Composable
private fun Backdrop(url: String?) {
    if (url == null) return
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().height(540.dp)) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.45f))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to background, 0.75f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to background)))
    }
}

@Composable
private fun Hero(
    detail: Detail,
    buttons: List<ButtonSpec>,
    restorer: FocusRestorer,
    buttonRow: FocusRow,
    outer: LazyListState,
    imageBase: String,
) {
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(start = SCREEN_INSET, end = SCREEN_INSET, top = 40.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        DetailCardArt(
            tmdbImageUrl(imageBase, "w780", detail.posterPath), detail.title,
            Modifier.size(240.dp, 360.dp).clip(RoundedCornerShape(16.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                listOfNotNull(if (detail.ref.mediaType == MediaType.Movie) "Movie" else "TV series", detail.network).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                detail.title,
                fontSize = if (detail.title.length > 17) 46.sp else 60.sp,
                lineHeight = if (detail.title.length > 17) 50.sp else 64.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
                if (detail.voteCount > 0) DetailScoreRing(detail.voteAverage)
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
                    DetailActionButton(
                        b.glyph, b.label, b.onClick,
                        modifier = with(restorer) { Modifier.rememberFocus(PANEL, buttonRow.rowKey, b.key, index) },
                        primary = b.primary, on = b.on, enabled = b.enabled,
                    )
                }
            }
        }
    }
}
