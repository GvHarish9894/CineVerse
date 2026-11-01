package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.tmdb.formatRuntime
import com.techgv.cineverse.data.tmdb.today
import com.techgv.cineverse.domain.Episode
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.bodyStyle
import com.techgv.cineverse.ui.theme.displayStyle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/** Composition renders only; the route host owns activation and disposal. */
@Composable
internal fun MobileSeasonScreen(model: MobileSeasonViewModel, navigator: MobileNavigator, onBack: () -> Unit) {
    val observed by model.state.collectAsState()
    val state = model.visibleState(observed)
    val token = state.context
    val scroll = remember(model) { LazyListState() }
    // Expansion changes openEpisode, not the completed provider or its restoration authority.
    val topology = token?.takeIf { state.bodyReady && !state.settingsFailed && !state.stale }
        ?.let { it to state.current?.episodes }
    val target = remember(model, topology) { topology?.let { model.scrollTarget } }
    var restored by remember(model, topology) { mutableStateOf(false) }
    var clamped by remember(model, topology) { mutableStateOf<MobileSeasonScroll?>(null) }
    fun ownsTopology(): Boolean {
        val latest = model.visibleState(model.state.value)
        return topology != null && latest.context === topology.first && latest.bodyReady &&
            !latest.settingsFailed && !latest.stale && latest.current?.episodes == topology.second
    }
    LaunchedEffect(model, topology) {
        if (target == null || !ownsTopology()) return@LaunchedEffect
        // scrollToItem remeasures the actual completed provider; no visible-header prerequisite.
        scroll.scrollToItem(target.index, target.offset)
        if (!ownsTopology()) return@LaunchedEffect
        val actual = MobileSeasonScroll(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset)
        clamped = actual.takeIf { it != target }
        restored = true
    }
    LaunchedEffect(model, topology, restored) {
        if (!restored || topology == null) return@LaunchedEffect
        snapshotFlow { MobileSeasonScroll(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset) }
            .distinctUntilChanged().collect { position ->
                // Preserve an unreachable saved target until the user actually moves, never save an automatic clamp.
                if (ownsTopology() && position != clamped) {
                    clamped = null
                    model.saveScroll(topology.first, position.index, position.offset)
                }
            }
    }
    DisposableEffect(model, scroll, topology) {
        onDispose {
            if (restored && clamped == null && topology != null && ownsTopology()) {
                model.saveScroll(topology.first, scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset)
            }
        }
    }
    val show = (state.current?.show as? SectionState.Content)?.value ?: state.retainedShow
    val episodes = (state.current?.episodes as? SectionState.Content)?.value
        ?: state.retainedSeason.takeIf { state.current?.episodes != SectionState.Empty }
    // Shimmer stands in while preferences are read and while each section loads; failures render their own recovery.
    val showLoading = show == null && !state.settingsFailed &&
        (state.current == null || state.current.show == SectionState.Loading)
    val episodesLoading = episodes == null && !state.settingsFailed && (state.current == null ||
        (state.current.episodes == SectionState.Loading && state.current.show !is SectionState.Error))
    val showReady = token != null && state.current?.show is SectionState.Content
    val episodesReady = token != null && state.current?.episodes is SectionState.Content
    val seasonTitle = if (model.seasonNumber == 0) "Specials" else "Season ${model.seasonNumber}"
    val palette = mobilePalette("tv:${model.seriesId}")
    val solid = rememberTopBarSolid(scroll, 140.dp)
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
        AmbientGlow(palette)
        Column {
            GlassTopBar(solid, navigation = {
                RoundIconButton(CvIcons.ChevronLeft, "Back", { model.back(state.revision, onBack) })
                if (show != null) Text(show.title, Modifier.testTag("season-show").cvClickable({ token?.let { model.show(it, navigator) } },
                    onClickLabel = "Open ${show.title}", focusShape = RoundedCornerShape(6.dp), pressedScale = 1f, enabled = showReady),
                    style = bodyStyle(16.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                else if (showLoading) Box(Modifier.size(140.dp, 16.dp).testTag("season-show-loading").clearAndSetSemantics {
                    contentDescription = "Loading show…"; liveRegion = LiveRegionMode.Polite
                }.shimmer())
            })
            LazyColumn(Modifier.fillMaxSize().testTag("mobile-season"), state = scroll,
                contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.invalid) item("invalid") {
                    Text("Invalid season. Go back and choose a season.", Modifier.padding(20.dp))
                } else {
                    if (showsSeasonReadRecovery(state)) item("reader") {
                        Column(Modifier.padding(horizontal = 20.dp).testTag("season-read-recovery"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.stale) Text("Previously loaded metadata — unverified. Actions disabled.")
                            ErrorState("Preferences unavailable or metadata unverified. Retry reads.", { model.retryReads(state.revision) })
                        }
                    }
                    item("title") {
                        Text(seasonTitle,
                            Modifier.padding(horizontal = 20.dp).testTag("season-title").semantics { heading() }, style = displayStyle(34.sp))
                    }
                    if (showLoading) item("options-loading") {
                        Row(Modifier.padding(horizontal = 20.dp).clearAndSetSemantics { },
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(SKELETON_CHIPS) { Box(Modifier.size(88.dp, 34.dp).shimmer(RoundedCornerShape(50))) }
                        }
                    }
                    if (show != null) item("options") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(show.seasons.distinctBy { it.seasonNumber }, key = { it.seasonNumber }) { season ->
                                // A seeded (unverified) show keeps the same chips, showing the route's season; taps no-op until ready.
                                val selected = if (showReady) state.current?.selected else model.seasonNumber
                                Chip(season.label, selected == season.seasonNumber,
                                    { token?.let { model.selectSeason(it, season.seasonNumber, navigator) } },
                                    Modifier.testTag("season-option-${season.seasonNumber}"))
                            }
                        }
                    }
                    when (val section = state.current?.show) {
                        is SectionState.Error -> item("show-error") {
                            Column(Modifier.padding(horizontal = 20.dp).testTag("season-show-error")) {
                                SectionFailure(section.kind) { token?.let(model::retryShow) }
                            }
                        }
                        else -> Unit
                    }
                    if (episodesLoading) item("episodes-loading") {
                        Column(Modifier.padding(horizontal = 20.dp).testTag("season-episodes-loading").clearAndSetSemantics {
                            contentDescription = "Loading episodes…"; liveRegion = LiveRegionMode.Polite
                        }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            repeat(SKELETON_EPISODES) { MobileSeasonEpisodeSkeleton() }
                        }
                    }
                    when (val section = state.current?.episodes) {
                        is SectionState.Error -> item("episodes-error") {
                            Column(Modifier.padding(horizontal = 20.dp).testTag("season-episodes-error")) {
                                SectionFailure(section.kind) { token?.let(model::retryEpisodes) }
                            }
                        }
                        SectionState.Empty -> item("unavailable") { Text("This season is not available.", Modifier.padding(20.dp)) }
                        else -> Unit
                    }
                    if (episodes != null) {
                        if (episodes.episodes.isEmpty()) item("empty") { Text("No episodes available.", Modifier.padding(20.dp)) }
                        items(episodes.episodes, key = { "episode:${it.episodeNumber}" }) { episode ->
                            val expanded = episodesReady && state.current?.openEpisode == episode.episodeNumber
                            MobileSeasonEpisode(episode, model.imageBase, expanded, episodesReady, Modifier.padding(horizontal = 20.dp)) {
                                token?.let { if (expanded) model.closeEpisode(it) else model.openEpisode(it, episode.episodeNumber) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val SKELETON_CHIPS = 4
private const val SKELETON_EPISODES = 3

/** Same footprint as [MobileSeasonEpisode]: still, label/title/metadata lines, overview and details button. */
@Composable
private fun MobileSeasonEpisodeSkeleton() {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Tokens.Colors.Tile).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(132.dp, 74.dp).shimmer(RoundedCornerShape(12.dp)))
            SkeletonLines(3, Modifier.weight(1f).padding(top = 4.dp))
        }
        SkeletonLines(2)
        Box(Modifier.size(120.dp, 32.dp).shimmer(RoundedCornerShape(50)))
    }
}

/** Reading preferences or re-verifying on return takes a moment; warn only when the read or re-verification failed. */
internal fun showsSeasonReadRecovery(state: MobileSeasonState): Boolean = state.settingsFailed ||
    (state.stale && (state.current?.show is SectionState.Error || state.current?.episodes is SectionState.Error))

@Composable
private fun MobileSeasonEpisode(episode: Episode, imageBase: String, expanded: Boolean, enabled: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    // The house EpisodeCard advertises Play; this metadata-only surface must not imply streaming.
    Column(modifier.fillMaxWidth().testTag("season-episode-${episode.episodeNumber}")
        .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
        .clip(RoundedCornerShape(18.dp)).background(Tokens.Colors.Tile).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PaletteArt(imageUrl(imageBase, "w300", episode.stillPath), mobilePalette("episode:${episode.seasonNumber}:${episode.episodeNumber}"),
                ArtStyle.Backdrop, Modifier.size(132.dp, 74.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("S${episode.seasonNumber} · E${episode.episodeNumber}", color = Tokens.Colors.TextSecondary)
                if (episode.name.isNotBlank()) Text(episode.name)
                val unaired = episode.isUnaired(today())
                val metadata = listOfNotNull(
                    episode.airDate?.let { if (unaired) "Airs $it" else it.toString() },
                    episode.runtimeMinutes?.takeIf { it > 0 }?.let(::formatRuntime),
                    episode.voteAverage.takeIf { !unaired && episode.voteCount > 0 && it.isFinite() && it in 0.0..10.0 }
                        ?.let { "★ ${(it * 10).roundToInt() / 10.0}" })
                if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), Modifier.testTag("season-meta-${episode.episodeNumber}"), color = Tokens.Colors.TextSecondary)
            }
        }
        if (episode.overview.isNotBlank()) Text(episode.overview, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        PillButton(if (expanded) "Collapse details" else "Episode details", onToggle,
            Modifier.semantics { contentDescription = "${if (expanded) "Collapse" else "Expand"} episode ${episode.episodeNumber} details" },
            variant = ButtonVariant.Ghost, small = true, enabled = enabled)
    }
}
