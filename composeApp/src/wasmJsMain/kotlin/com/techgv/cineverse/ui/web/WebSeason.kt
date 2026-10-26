package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.displayStyle
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Clock

/** Root must forward actual invalidations and use the strict repository flow, not a defaulted UI projection. */
@Composable
internal fun WebSeason(
    entry: WebEntry,
    catalog: CatalogRepository,
    settings: Flow<Settings>,
    storageFailure: StateFlow<BrowserStorageFailure?>,
    generation: GenerationCounter,
    contextInvalidations: Flow<Unit>,
    retryUpstream: () -> Unit,
    reportStorageFailure: (StorageException) -> Unit,
    onBack: () -> Unit,
    onShow: (Int) -> Unit,
    onSeason: (Int, Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentRetry by rememberUpdatedState(retryUpstream)
    val currentReport by rememberUpdatedState(reportStorageFailure)
    val currentBack by rememberUpdatedState(onBack)
    val currentShow by rememberUpdatedState(onShow)
    val currentSeason by rememberUpdatedState(onSeason)
    val owner = remember(entry, catalog, settings, storageFailure, generation, contextInvalidations) {
        WebSeasonOwner(entry, catalog, settings, storageFailure, generation, contextInvalidations, scope,
            { currentRetry() }, { currentReport(it) }, { currentBack() }, { currentShow(it) }, { id, number -> currentSeason(id, number) })
    }
    DisposableEffect(owner) { onDispose { owner.close() } }
    val state by owner.state.collectAsState()
    WebSeasonContent(owner, state, Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
}

@Composable
internal fun WebSeasonContent(owner: WebSeasonOwner, state: WebSeasonState, today: LocalDate) {
    val scroll = remember(owner) { LazyListState() }
    var restored by remember(owner) { mutableStateOf(false) }
    val token = state.context
    val currentShow = (state.current?.show as? SectionState.Content)?.value
    val currentSeason = (state.current?.episodes as? SectionState.Content)?.value
    val unavailable = state.current?.episodes == SectionState.Empty
    val shownSeason = currentSeason ?: state.retainedSeason?.value?.takeUnless { unavailable }
    val staleEpisodes = currentSeason == null && shownSeason != null
    val expectedItems = (shownSeason?.episodes?.size?.takeIf { it > 0 } ?: 1) + 2
    LaunchedEffect(owner, token, state.bodyReady, restored) {
        if (!restored && token != null && state.bodyReady) {
            // Loading/show-only and retained old bodies must not clamp the original entry target.
            withFrameNanos { }
            snapshotFlow { scroll.layoutInfo.totalItemsCount }.first { it == expectedItems }
            scroll.scrollToItem(owner.scrollTarget.first.coerceAtMost(expectedItems - 1), owner.scrollTarget.second)
            restored = true
        }
    }
    LaunchedEffect(owner, token, restored, state.bodyReady) {
        if (restored && token != null && state.bodyReady) {
            snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }
                .distinctUntilChanged().collect { (index, offset) -> owner.saveScroll(token, index, offset) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val narrow = maxWidth <= 640.dp
        LazyColumn(state = scroll, modifier = Modifier.widthIn(max = 1320.dp).fillMaxSize().testTag("web-season"),
            contentPadding = PaddingValues(horizontal = if (narrow) 16.dp else 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item("header") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton("← Back", owner::back, variant = ButtonVariant.Ghost)
                    if (state.invalidSavedState) Text("This season route or saved state is invalid. Saved values have not been changed.")
                    else {
                        when (state.reader) {
                            WebSeasonReader.Unknown -> Text("Waiting for verified preferences…")
                            is WebSeasonReader.Failed -> ErrorState("Saved preferences are unavailable (${state.reader.failure}).", owner::retryStorage)
                            is WebSeasonReader.Ready -> Unit
                        }
                        val show = currentShow ?: state.retainedShow?.value
                        if (show != null) {
                            if (currentShow == null) StaleSeasonLabel("Show", state.retainedShow!!.context)
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                PaletteArt(webImage(show.posterPath, "w185"), ArtPalette(Tokens.Colors.Accent, Tokens.Colors.Secondary),
                                    ArtStyle.Poster, Modifier.width(if (narrow) 72.dp else 100.dp).aspectRatio(2f / 3f))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (currentShow != null && token != null) PillButton(show.title, { owner.show(token) }, variant = ButtonVariant.Ghost)
                                    else Text(show.title)
                                    show.network?.let { Text(it) }
                                    Text(if (owner.route?.number == 0) "Specials" else "Season ${owner.route?.number}",
                                        style = displayStyle(if (narrow) 32.sp else 48.sp), modifier = Modifier.semantics { heading() })
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                show.seasons.forEach { season ->
                                    if (currentShow != null && token != null) Chip(season.label, state.current?.selected == season.seasonNumber,
                                        { owner.selectSeason(token, season.seasonNumber) }, selectedColor = Tokens.Colors.Accent)
                                    else Text(season.label, modifier = Modifier.padding(8.dp))
                                }
                            }
                        }
                        when (val showState = state.current?.show) {
                            SectionState.Loading -> Text("Loading show…")
                            is SectionState.Error -> SeasonError("Show", showState.kind) { token?.let(owner::retry) }
                            SectionState.Empty -> Text("Show is unavailable.")
                            else -> Unit
                        }
                        when (val episodes = state.current?.episodes) {
                            is SectionState.Error -> SeasonError("Episodes", episodes.kind) { token?.let(owner::retry) }
                            SectionState.Loading -> if (currentShow != null) Text("Loading episodes…")
                            else -> Unit
                        }
                        if (staleEpisodes) StaleSeasonLabel("Episodes", state.retainedSeason!!.context)
                    }
                }
            }
            if (shownSeason != null && shownSeason.episodes.isNotEmpty()) {
                items(shownSeason.episodes, key = { "episode-${it.episodeNumber}" }) { episode ->
                    SeasonEpisodeCard(episode, today, narrow,
                        expanded = !staleEpisodes && state.current?.openEpisode == episode.episodeNumber,
                        enabled = !staleEpisodes && token != null,
                        onOpen = { token?.let { owner.openEpisode(it, episode.episodeNumber) } },
                        onClose = { token?.let(owner::closeEpisode) })
                }
            } else item("body-status") {
                when {
                    unavailable -> Text("Requested season is unavailable.")
                    currentSeason != null -> Text("No episodes were returned for this season.")
                    state.invalidSavedState -> Unit
                    state.current?.episodes is SectionState.Error -> Text("Episode details are unavailable.")
                    else -> Text("Episode details will appear after this season loads.")
                }
            }
            item("footer") { Footer() }
        }
    }
}

@Composable
private fun StaleSeasonLabel(section: String, context: WebSeasonContext) {
    Text("$section retained · ${context.settings.language} / ${context.settings.region} · stale, not verified for the current context")
}

@Composable
private fun SeasonError(section: String, kind: SectionErrorKind, retry: () -> Unit) {
    val message = "$section: " + when (kind) {
        SectionErrorKind.Offline -> "Offline."
        SectionErrorKind.NotAvailable -> "Not available."
        SectionErrorKind.AppCredential -> "Catalog configuration is unavailable."
        SectionErrorKind.SessionExpired -> "Session verification is required."
        SectionErrorKind.Generic -> "Could not load data."
    }
    if (kind.retryable) ErrorState(message, retry) else Text(message)
}

@Composable
private fun SeasonEpisodeCard(episode: Episode, today: LocalDate, narrow: Boolean, expanded: Boolean,
                              enabled: Boolean, onOpen: () -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    Panel(Modifier.fillMaxWidth().onPreviewKeyEvent {
        if (expanded && it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
            onClose(); focus.requestFocus(); true
        } else false
    }) {
        val clickable = Modifier.fillMaxWidth().testTag("season-${episode.seasonNumber}-episode-${episode.episodeNumber}")
            .focusRequester(focus).cvClickable(onOpen, enabled = enabled, onClickLabel = "Show episode details")
        if (narrow) Column(clickable, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EpisodeStill(episode, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            EpisodeSummary(episode, today)
        } else Row(clickable, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            EpisodeStill(episode, Modifier.width(240.dp).aspectRatio(16f / 9f))
            Column(Modifier.weight(1f)) { EpisodeSummary(episode, today) }
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Episode details · ${episode.episodeNumber}", modifier = Modifier.semantics { heading() })
            if (episode.isFinale) Text("Season finale")
            if (episode.guestStars.isEmpty()) Text("No guest credits supplied.")
            else {
                Text("Guest stars")
                episode.guestStars.forEach { guest -> Text(listOf(guest.name, guest.role).filter { it.isNotBlank() }.joinToString(" · ")) }
            }
            PillButton("Close details", { onClose(); focus.requestFocus() }, variant = ButtonVariant.Ghost)
        }
    }
}

@Composable
private fun EpisodeStill(episode: Episode, modifier: Modifier) {
    PaletteArt(webImage(episode.stillPath, "w300"), ArtPalette(Tokens.Colors.Accent, Tokens.Colors.Secondary), ArtStyle.Backdrop, modifier)
}

@Composable
private fun EpisodeSummary(episode: Episode, today: LocalDate) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${episode.episodeNumber} · ${episode.name}", style = displayStyle(22.sp))
        val unaired = episode.isUnaired(today)
        val date = when {
            episode.airDate == null -> "Air date unknown"
            unaired -> "Airs ${episode.airDate}"
            else -> episode.airDate.toString()
        }
        Text(date + (episode.runtimeMinutes?.let { " · $it min" } ?: ""))
        if (!unaired) Text(if (episode.voteCount > 0) "★ ${(episode.voteAverage * 10).roundToInt() / 10.0}" else "NR")
        Text(episode.overview.ifBlank { "No overview supplied." })
    }
}
