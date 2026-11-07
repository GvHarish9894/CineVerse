package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.formatDate
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.bodyStyle
import com.techgv.cineverse.ui.theme.displayStyle
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
    memory: WebSeasonMemory,
    onPerson: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentRetry by rememberUpdatedState(retryUpstream)
    val currentReport by rememberUpdatedState(reportStorageFailure)
    val currentBack by rememberUpdatedState(onBack)
    val currentShow by rememberUpdatedState(onShow)
    val currentSeason by rememberUpdatedState(onSeason)
    val owner = remember(entry, catalog, settings, storageFailure, generation, contextInvalidations) {
        WebSeasonOwner(entry, catalog, settings, storageFailure, generation, contextInvalidations, scope,
            { currentRetry() }, { currentReport(it) }, { currentBack() }, { currentShow(it) }, { id, number -> currentSeason(id, number) }, memory)
    }
    DisposableEffect(owner) { onDispose { owner.close() } }
    val state by owner.state.collectAsState()
    WebSeasonContent(owner, state, Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date, onPerson)
}

@Composable
internal fun WebSeasonContent(owner: WebSeasonOwner, state: WebSeasonState, today: LocalDate, onPerson: (Int) -> Unit = {}) {
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth <= 640.dp
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("web-season"),
            contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item("header") {
                WebPage { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // History back: season chips replace this entry, so Back leaves the season page, wherever it was opened from.
                    WebBackLink("Back", owner::back, Modifier.padding(top = 12.dp))
                    if (state.invalidSavedState) Text("This season route or saved state is invalid. Saved values have not been changed.")
                    else {
                        val show = currentShow ?: state.retainedShow?.value
                        when (state.reader) {
                            // A remembered header stays up while preferences are read; the episode skeleton below covers the wait.
                            WebSeasonReader.Unknown -> if (show == null) SeasonSkeleton("Waiting for verified preferences…")
                            is WebSeasonReader.Failed -> ErrorState("Saved preferences are unavailable (${state.reader.failure}).", owner::retryStorage)
                            is WebSeasonReader.Ready -> Unit
                        }
                        if (show != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                PaletteArt(webImage(show.posterPath, "w185"), seasonPalette, ArtStyle.Poster,
                                    Modifier.width(if (narrow) 64.dp else 88.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        val line = bodyStyle(16.sp)
                                        val lineColor = Tokens.Colors.TextPrimary.copy(alpha = 0.7f)
                                        if (currentShow != null && token != null) Text(show.title, Modifier.cvClickable({ owner.show(token) },
                                            onClickLabel = "Open ${show.title}", focusShape = RoundedCornerShape(6.dp), pressedScale = 1f),
                                            style = line.copy(textDecoration = TextDecoration.Underline), color = lineColor)
                                        else Text(show.title, style = line, color = lineColor)
                                        show.network?.takeIf { it.isNotBlank() }?.let {
                                            Text("·", style = line, color = lineColor)
                                            Text(it, style = line, color = lineColor)
                                        }
                                    }
                                    Text(if (owner.route?.number == 0) "Specials" else "Season ${owner.route?.number}",
                                        style = displayStyle(if (narrow) 36.sp else 48.sp, trackingEm = -0.03f).copy(lineHeight = 1.05.em),
                                        modifier = Modifier.semantics { heading() })
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Chips stay put while a season loads; a press only acts once this owner's own show is current.
                                show.seasons.forEach { season ->
                                    Chip(season.label, owner.route?.number == season.seasonNumber,
                                        { token?.let { owner.selectSeason(it, season.seasonNumber) } }, selectedColor = Tokens.Colors.Accent,
                                        selectedContent = Tokens.Colors.OnAccent)
                                }
                            }
                        }
                        when (val showState = state.current?.show) {
                            SectionState.Loading -> if (show == null) SeasonSkeleton("Loading show…")
                            is SectionState.Error -> SeasonError("Show", showState.kind) { token?.let(owner::retry) }
                            SectionState.Empty -> Text("Show is unavailable.")
                            else -> Unit
                        }
                        when (val episodes = state.current?.episodes) {
                            is SectionState.Error -> SeasonError("Episodes", episodes.kind) { token?.let(owner::retry) }
                            SectionState.Loading, null -> if (show != null && shownSeason == null && state.reader !is WebSeasonReader.Failed)
                                SeasonSkeleton("Loading episodes…")
                            else -> Unit
                        }
                    }
                } }
            }
            if (shownSeason != null && shownSeason.episodes.isNotEmpty()) {
                items(shownSeason.episodes, key = { "episode-${it.episodeNumber}" }) { episode ->
                    WebPage {
                        SeasonEpisodeCard(episode, today, narrow,
                            expanded = !staleEpisodes && state.current?.openEpisode == episode.episodeNumber,
                            enabled = !staleEpisodes && token != null,
                            onOpen = { token?.let { owner.openEpisode(it, episode.episodeNumber) } },
                            onClose = { token?.let(owner::closeEpisode) }, onPerson = onPerson)
                    }
                }
            } else item("body-status") {
                WebPage {
                    when {
                        unavailable -> Text("Requested season is unavailable.")
                        currentSeason != null -> Text("No episodes were returned for this season.")
                        state.invalidSavedState -> Unit
                        state.current?.episodes is SectionState.Error -> Text("Episode details are unavailable.")
                        else -> Unit
                    }
                }
            }
            item("footer") { Footer() }
        }
    }
}

private val seasonPalette = ArtPalette(Tokens.Colors.Accent, Tokens.Colors.Secondary)

@Composable
private fun SeasonSkeleton(label: String) {
    Column(Modifier.fillMaxWidth().semantics { contentDescription = label; liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(10) {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Box(Modifier.width(240.dp).aspectRatio(16f / 9f).shimmer(RoundedCornerShape(14.dp)))
                SkeletonLines(3, Modifier.weight(1f).padding(top = 8.dp))
            }
        }
    }
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
                              enabled: Boolean, onOpen: () -> Unit, onClose: () -> Unit, onPerson: (Int) -> Unit) {
    val focus = remember { FocusRequester() }
    Panel(Modifier.fillMaxWidth().onPreviewKeyEvent {
        if (expanded && it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
            onClose(); focus.requestFocus(); true
        } else false
    }) { Box(Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth()) {
        // Expanded: keep the summary clear of the collapse icon in the top-right corner.
        val clickable = Modifier.fillMaxWidth().padding(end = if (expanded) 48.dp else 0.dp)
            .testTag("season-${episode.seasonNumber}-episode-${episode.episodeNumber}")
            .focusRequester(focus).cvClickable(onOpen, enabled = enabled, onClickLabel = "Show episode details")
        if (narrow) Column(clickable, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EpisodeStill(episode, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            EpisodeSummary(episode, today)
        } else Row(clickable, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            EpisodeStill(episode, Modifier.width(240.dp).aspectRatio(16f / 9f))
            Column(Modifier.weight(1f)) { EpisodeSummary(episode, today) }
        }
        // Inline metadata only: this build has no episode player, so no play glyph or CTA.
        if (expanded) Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Episode details · ${episode.episodeNumber}", modifier = Modifier.semantics { heading() }, style = bodyStyle(16.sp, FontWeight.Bold))
            if (episode.isFinale) Text("Season finale")
            if (episode.guestStars.isEmpty()) Text("No guest credits supplied.")
            else {
                Text("Guest stars", style = bodyStyle(13.sp, FontWeight.Bold), color = Tokens.Colors.TextPrimary.copy(alpha = 0.62f))
                // Guest stars (with photos) arrive in the season response itself: no extra request per episode.
                LazyRow(Modifier.ringBleed(), contentPadding = PaddingValues(start = RingBleed, end = RingBleed, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(episode.guestStars.distinctBy { it.personId }, key = { it.personId }) { guest ->
                        PersonCard(guest.name, guest.role.takeIf { it.isNotBlank() }, seasonPalette,
                            seededOpen(WebRoute.Person(guest.personId), webPersonSeed(guest.name, guest.profilePath)) { onPerson(guest.personId) },
                            Modifier.testTag("season-guest-${guest.personId}"), webImage(guest.profilePath, "w185"), width = 120.dp,
                            artModifier = Modifier.webSharedPerson(guest.personId))
                    }
                }
            }
        }
    }
        // Own Box: on web RoundIconButton sits inside a TooltipBox, which would swallow an align modifier.
        if (expanded) Box(Modifier.align(Alignment.TopEnd)) {
            RoundIconButton(CvIcons.ChevronUp, "Hide episode details", { onClose(); focus.requestFocus() },
                diameter = 36.dp, container = Color.White.copy(alpha = 0.08f))
        }
    } }
}

@Composable
private fun EpisodeStill(episode: Episode, modifier: Modifier) {
    PaletteArt(webImage(episode.stillPath, "w300"), seasonPalette, ArtStyle.Backdrop, modifier.clip(RoundedCornerShape(14.dp)))
}

@Composable
private fun EpisodeSummary(episode: Episode, today: LocalDate) {
    Column {
        val unaired = episode.isUnaired(today)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            Text("${episode.episodeNumber}", style = displayStyle(22.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.5f))
            Text(episode.name, style = bodyStyle(18.sp, FontWeight.Bold))
            // Unaired or unvoted: no badge (and no "NR"), never a guessed score.
            if (!unaired && episode.voteCount > 0) GoldBadge("★ ${oneDecimal(episode.voteAverage)}")
        }
        val airDate = episode.airDate
        val date = when {
            airDate == null -> "Air date unknown"
            unaired -> "Airs ${formatDate(airDate)}"
            else -> formatDate(airDate)
        }
        Text(date + (episode.runtimeMinutes?.takeIf { it > 0 }?.let { " · ${it}m" } ?: ""), Modifier.padding(top = 4.dp),
            style = bodyStyle(13.5.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.66f))
        if (episode.overview.isNotBlank()) Text(episode.overview, Modifier.padding(top = 8.dp), style = bodyStyle(15.sp).copy(lineHeight = 23.sp),
            color = Tokens.Colors.TextPrimary.copy(alpha = 0.86f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
