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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.data.tmdb.formatRuntime
import com.techgv.cineverse.data.tmdb.today
import com.techgv.cineverse.domain.Episode
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.*
import com.techgv.cineverse.ui.design.Tokens
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
    val target = remember(model) { model.scrollTarget }
    var restored by remember(model) { mutableStateOf(false) }
    var clamped by remember(model) { mutableStateOf<MobileSeasonScroll?>(null) }
    val topologyReady = token != null && state.bodyReady && !state.settingsFailed && !state.stale
    val ready by rememberUpdatedState(topologyReady && restored)
    val currentToken by rememberUpdatedState(token)
    LaunchedEffect(model, topologyReady) {
        if (!topologyReady || restored) return@LaunchedEffect
        // scrollToItem remeasures the actual completed provider; no visible-header prerequisite.
        scroll.scrollToItem(target.index, target.offset)
        val actual = MobileSeasonScroll(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset)
        clamped = actual.takeIf { it != target }
        restored = true
    }
    LaunchedEffect(model, topologyReady, restored) {
        if (!ready) return@LaunchedEffect
        snapshotFlow { MobileSeasonScroll(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset) }
            .distinctUntilChanged().collect { position ->
                // Preserve an unreachable saved target until the user actually moves, never save an automatic clamp.
                if (ready && position != clamped) {
                    clamped = null
                    currentToken?.let { model.saveScroll(it, position.index, position.offset) }
                }
            }
    }
    DisposableEffect(model, scroll) {
        onDispose {
            if (ready && clamped == null) currentToken?.let {
                model.saveScroll(it, scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset)
            }
        }
    }
    val show = (state.current?.show as? SectionState.Content)?.value ?: state.retainedShow
    val episodes = (state.current?.episodes as? SectionState.Content)?.value
        ?: state.retainedSeason.takeIf { state.current?.episodes != SectionState.Empty }
    val showReady = token != null && state.current?.show is SectionState.Content
    val episodesReady = token != null && state.current?.episodes is SectionState.Content
    val seasonTitle = if (model.seasonNumber == 0) "Specials" else "Season ${model.seasonNumber}"
    val palette = mobilePalette("tv:${model.seriesId}")
    val solid = rememberTopBarSolid(scroll, 140.dp)
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
        AmbientGlow(palette)
        Column {
            GlassTopBar(solid, title = seasonTitle.takeIf { solid },
                navigation = { RoundIconButton(CvIcons.ChevronLeft, "Back", { model.back(onBack) }) })
            LazyColumn(Modifier.fillMaxSize().testTag("mobile-season"), state = scroll,
                contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.invalid) item("invalid") {
                    Text("Invalid season. Go back and choose a season.", Modifier.padding(20.dp))
                } else {
                    if (state.settingsFailed || state.stale || token == null) item("reader") {
                        Column(Modifier.padding(horizontal = 20.dp).testTag("season-read-recovery"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.stale) Text("Previously loaded metadata — unverified. Actions disabled.")
                            if (state.settingsFailed || state.stale) ErrorState("Preferences unavailable or metadata unverified. Retry reads.", model::retryReads)
                            else {
                                Text("Waiting for saved preferences…")
                                PillButton("Retry reads", model::retryReads, variant = ButtonVariant.Ghost, small = true)
                            }
                        }
                    }
                    if (show != null) item("header") {
                        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PillButton(show.title, { token?.let { model.show(it, navigator) } }, Modifier.testTag("season-show"),
                                variant = ButtonVariant.Ghost, enabled = showReady)
                            show.network?.takeIf { it.isNotBlank() }?.let { Text(it, color = Tokens.Colors.TextSecondary) }
                        }
                    }
                    item("title") {
                        Text(seasonTitle,
                            Modifier.padding(horizontal = 20.dp).testTag("season-title").semantics { heading() }, style = displayStyle(34.sp))
                    }
                    if (show != null) item("options") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(show.seasons.distinctBy { it.seasonNumber }, key = { it.seasonNumber }) { season ->
                                if (showReady) Chip(season.label, state.current?.selected == season.seasonNumber,
                                    { token?.let { model.selectSeason(it, season.seasonNumber, navigator) } },
                                    Modifier.testTag("season-option-${season.seasonNumber}"))
                                else MetaChip(season.label, Modifier.testTag("season-option-${season.seasonNumber}"))
                            }
                        }
                    }
                    when (val section = state.current?.show) {
                        is SectionState.Error -> item("show-error") {
                            Column(Modifier.padding(horizontal = 20.dp).testTag("season-show-error")) {
                                SectionFailure(section.kind) { token?.let(model::retryShow) }
                            }
                        }
                        SectionState.Loading -> if (show == null) item("show-loading") { Text("Loading show…", Modifier.padding(20.dp)) }
                        else -> Unit
                    }
                    when (val section = state.current?.episodes) {
                        is SectionState.Error -> item("episodes-error") {
                            Column(Modifier.padding(horizontal = 20.dp).testTag("season-episodes-error")) {
                                SectionFailure(section.kind) { token?.let(model::retryEpisodes) }
                            }
                        }
                        SectionState.Loading -> if (state.current?.show !is SectionState.Error) item("episodes-loading") {
                            Text("Loading episodes…", Modifier.padding(20.dp))
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
