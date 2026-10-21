package com.techgv.cineverse.ui.season

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.techgv.cineverse.AppContainer
import com.techgv.cineverse.data.tmdb.formatDate
import com.techgv.cineverse.data.tmdb.formatRuntime
import com.techgv.cineverse.domain.Detail
import com.techgv.cineverse.domain.Episode
import com.techgv.cineverse.domain.Season
import com.techgv.cineverse.ui.components.DetailCardArt
import com.techgv.cineverse.ui.components.DetailCardSurface
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.FocusRestorer
import com.techgv.cineverse.ui.components.FocusRow
import com.techgv.cineverse.ui.components.SavedFocus
import com.techgv.cineverse.ui.components.ScrollableText
import com.techgv.cineverse.ui.components.Section
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.ui.components.rememberSavedLazyListState
import com.techgv.cineverse.ui.components.tmdbImageUrl
import com.techgv.cineverse.ui.detail.DetailActionButton
import com.techgv.cineverse.ui.detail.Lane2PersonCard
import com.techgv.cineverse.ui.detail.SCREEN_INSET
import com.techgv.cineverse.ui.detail.oneDecimal
import com.techgv.cineverse.ui.detail.screenFocusRoot
import com.techgv.cineverse.ui.nav.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val SEASONS = "seasons"
private const val SEASON_LIST = "list"
private const val EPISODES = "episodes"
private const val PANEL = "episodePanel"
private const val PANEL_ACTIONS = "actions"
private const val PANEL_GUESTS = "guests"
private const val NOTICE_MILLIS = 2_400L

private fun seasonKey(n: Int) = "season:$n"
private fun episodeKey(n: Int) = "episode:$n"
private fun episodesRow(seasonNumber: Int) = "season:$seasonNumber"

@Composable
fun SeasonScreen(route: Route.Season, container: AppContainer, onNavigate: (Route) -> Unit) {
    val vm: SeasonViewModel = viewModel {
        SeasonViewModel(route, createSavedStateHandle(), container.tmdbRepository, container.settingsRepository.settings, container.appGeneration)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    SeasonContent(
        state, vm.focusMemory, vm.imageBase,
        SeasonActions(
            onRetry = vm::retry,
            onRetryEpisodes = vm::retryEpisodes,
            onSelectSeason = vm::selectSeason,
            onOpenEpisode = vm::openEpisode,
            onCloseEpisode = vm::closeEpisode,
            onNoticeShown = vm::noticeShown,
            onNavigate = onNavigate,
        ),
    )
}

class SeasonActions(
    val onRetry: () -> Unit,
    val onRetryEpisodes: () -> Unit,
    val onSelectSeason: (Int) -> Unit,
    val onOpenEpisode: (Int) -> Unit,
    val onCloseEpisode: () -> Unit,
    val onNoticeShown: () -> Unit,
    val onNavigate: (Route) -> Unit,
)

@Composable
fun SeasonContent(state: SeasonUiState, focusMemory: FocusMemory, imageBase: String, actions: SeasonActions) {
    // ScreenFocusRoot: AppNav's focusRestorer must not act as onEnter of the panel / lists inside.
    Box(Modifier.screenFocusRoot().background(MaterialTheme.colorScheme.background)) {
        when (val show = state.show) {
            is SectionState.Content -> SeasonBody(show.value, state, focusMemory, imageBase, actions)
            else -> Section(show, onRetry = actions.onRetry, modifier = Modifier.padding(SCREEN_INSET)) {}
        }
        state.notice?.let { notice ->
            Text(
                notice,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            LaunchedEffect(notice) {
                delay(NOTICE_MILLIS)
                actions.onNoticeShown()
            }
        }
    }
}

@Composable
private fun SeasonBody(show: Detail, state: SeasonUiState, memory: FocusMemory, imageBase: String, actions: SeasonActions) {
    val restorer = remember(memory) { FocusRestorer(memory) }
    val scope = rememberCoroutineScope()
    val seasonsState = rememberSavedLazyListState(memory, SEASONS)
    val episodesState = rememberSavedLazyListState(memory, EPISODES)
    val selected = state.selected
    val selectedSummary = show.seasons.firstOrNull { it.seasonNumber == selected }
    val episodes = (state.episodes as? SectionState.Content)?.value?.takeIf { it.seasonNumber == selected }
    val seasonKeys = show.seasons.map { seasonKey(it.seasonNumber) }
    val episodeKeys = episodes?.episodes?.map { episodeKey(it.episodeNumber) }.orEmpty()
    val episodeRow = episodesRow(selected ?: -1)
    // Episode focus target after the panel closes (§7.6 BACK contract).
    var refocusEpisode by remember { mutableStateOf<Int?>(null) }

    fun firstEpisode(): FocusRequester =
        episodeKeys.firstOrNull()?.let { restorer.attachedRequester(EPISODES, episodeRow, it) } ?: FocusRequester.Default

    fun selectedSeason(): FocusRequester =
        selected?.let { restorer.attachedRequester(SEASONS, SEASON_LIST, seasonKey(it)) } ?: FocusRequester.Default

    Column(Modifier.fillMaxSize().padding(start = SCREEN_INSET, end = SCREEN_INSET, top = 32.dp)) {
        Text(
            listOfNotNull(show.title, show.network).joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            selectedSummary?.let { "${it.label} · ${episodes?.episodes?.size ?: it.episodeCount} episodes" } ?: "No seasons available",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            LazyColumn(
                state = seasonsState,
                modifier = Modifier.width(240.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
            ) {
                itemsIndexed(show.seasons, key = { _, s -> s.seasonNumber }) { index, season ->
                    SeasonItem(
                        title = season.label,
                        subtitle = listOfNotNull("${season.episodeCount} episodes", season.airDate?.take(4)).joinToString(" · "),
                        selected = season.seasonNumber == selected,
                        // OK on a season moves focus to the episodes (§7.6).
                        onClick = { firstEpisode().takeIf { it != FocusRequester.Default }?.requestFocus() },
                        modifier = with(restorer) { Modifier.rememberFocus(SEASONS, SEASON_LIST, seasonKeys[index], index) }
                            .onFocusChanged {
                                if (it.isFocused) {
                                    actions.onSelectSeason(season.seasonNumber)
                                    // Focus on a season resets the episode selection to the first episode.
                                    scope.launch { episodesState.scrollToItem(0) }
                                }
                            }
                            .focusProperties { right = firstEpisode() },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (selected != null) {
                    Section(state.episodes, onRetry = actions.onRetryEpisodes, empty = { Text("No episodes yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }) {
                        val season = episodes ?: return@Section
                        LazyColumn(
                            state = episodesState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp, start = 8.dp, end = 8.dp),
                        ) {
                            itemsIndexed(season.episodes, key = { _, e -> e.episodeNumber }) { index, episode ->
                                EpisodeCard(
                                    episode, tmdbImageUrl(imageBase, "w300", episode.stillPath),
                                    onClick = { actions.onOpenEpisode(episode.episodeNumber) },
                                    // LEFT from episodes goes to the selected season, never the rail (§1.2).
                                    modifier = with(restorer) { Modifier.rememberFocus(EPISODES, episodeRow, episodeKeys[index], index) }
                                        .focusProperties { left = selectedSeason() },
                                )
                            }
                        }
                    }
                }
                val open = state.openEpisode
                val openEpisode = episodes?.episodes?.firstOrNull { it.episodeNumber == open }
                if (openEpisode != null) {
                    EpisodePanel(openEpisode, restorer, imageBase, actions) {
                        actions.onCloseEpisode()
                        refocusEpisode = openEpisode.episodeNumber
                    }
                }
            }
        }
    }

    // BACK closes the panel and returns focus to its episode; with no panel, AppNav pops.
    BackHandler(enabled = state.openEpisode != null) {
        refocusEpisode = state.openEpisode
        actions.onCloseEpisode()
    }
    LaunchedEffect(refocusEpisode) {
        val n = refocusEpisode ?: return@LaunchedEffect
        memory.save(SavedFocus(EPISODES, episodeRow, episodeKey(n), episodeKeys.indexOf(episodeKey(n)).coerceAtLeast(0)))
        restorer.restore(listOf(FocusRow(EPISODES, episodeRow, episodeKeys, episodesState)))
        refocusEpisode = null
    }

    // §1.3 restore once the current generation's data is composed: seasons always; episodes too when the
    // saved focus is in them. First entry focuses the route's (selected) season.
    var restored by remember { mutableStateOf(false) }
    val episodesSettled = state.episodes !is SectionState.Loading
    LaunchedEffect(selected, episodesSettled, state.openEpisode != null) {
        if (restored) return@LaunchedEffect
        // Entered with the panel open: the panel restores its own focus, and closing it refocuses the episode.
        if (state.openEpisode != null) {
            restored = true
            return@LaunchedEffect
        }
        val saved = memory.saved
        if (saved?.panelKey == EPISODES && !episodesSettled) return@LaunchedEffect
        if (saved == null && selected != null) {
            memory.save(SavedFocus(SEASONS, SEASON_LIST, seasonKey(selected), show.seasons.indexOfFirst { it.seasonNumber == selected }))
        }
        restored = true
        restorer.restore(
            listOf(
                FocusRow(SEASONS, SEASON_LIST, seasonKeys, seasonsState),
                FocusRow(EPISODES, episodeRow, episodeKeys, episodesState),
            ),
        )
    }
}

@Composable
private fun SeasonItem(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    DetailCardSurface(onClick = onClick, modifier = modifier.fillMaxWidth().height(64.dp)) {
        Column(
            Modifier.fillMaxSize()
                .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else Color.Transparent)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Air date / "Airs {date}", runtime, ★ rating (none when unaired); [today] decides "unaired". */
internal fun episodeMeta(episode: Episode, today: LocalDate): List<String> {
    val unaired = episode.isUnaired(today)
    val date = episode.airDate?.let { if (unaired) "Airs ${formatDate(it)}" else formatDate(it) }
    val rating = if (!unaired && episode.voteCount > 0) "★ ${oneDecimal(episode.voteAverage)}" else null
    return listOfNotNull(date, episode.runtimeMinutes?.let(::formatRuntime), rating)
}

@Composable
private fun EpisodeCard(episode: Episode, stillUrl: String?, onClick: () -> Unit, modifier: Modifier) {
    DetailCardSurface(onClick = onClick, modifier = modifier.fillMaxWidth().height(132.dp)) {
        Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            DetailCardArt(stillUrl, "E${episode.episodeNumber}", Modifier.size(192.dp, 108.dp).clip(RoundedCornerShape(12.dp)))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "E${episode.episodeNumber} · ${episode.name}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (episode.isFinale) FinaleBadge()
                }
                Text(
                    episodeMeta(episode, LocalDate.now()).joinToString("   "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    episode.overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FinaleBadge() {
    Text(
        "FINALE",
        style = MaterialTheme.typography.labelSmall,
        color = Color.Black,
        modifier = Modifier.background(Color(0xFFFFB020), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * §7.6 inline episode panel over the episodes list: full overview and guest stars. Focus is trapped
 * inside; Close (or BACK, handled by the caller) returns focus to the episode.
 */
@Composable
private fun EpisodePanel(episode: Episode, restorer: FocusRestorer, imageBase: String, actions: SeasonActions, onClose: () -> Unit) {
    val guestKeys = episode.guestStars.map { "person:${it.personId}" }
    val guestsState = remember(episode.episodeNumber) { LazyListState() }
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "E${episode.episodeNumber} · ${episode.name}",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (episode.isFinale) FinaleBadge()
        }
        Text(
            episodeMeta(episode, LocalDate.now()).joinToString("   "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Takes the room left by the rest; UP from Close enters it, DOWN pages through it back to Close.
        ScrollableText(
            episode.overview.ifBlank { "No overview available" }, MaterialTheme.typography.bodyLarge, MaterialTheme.colorScheme.onSurface,
            Modifier.weight(1f, fill = false),
        )
        DetailActionButton(
            "✕", "Close", onClose,
            modifier = with(restorer) { Modifier.rememberFocus(PANEL, PANEL_ACTIONS, "close", 0) },
        )
        if (episode.guestStars.isNotEmpty()) {
            Text("Guest stars", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            LazyRow(state = guestsState, horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(episode.guestStars, key = { index, _ -> guestKeys[index] }) { index, guest ->
                    Lane2PersonCard(
                        guest.name, guest.role, tmdbImageUrl(imageBase, "w185", guest.profilePath),
                        onClick = { actions.onNavigate(Route.Person(guest.personId)) },
                        modifier = with(restorer) { Modifier.rememberFocus(PANEL, PANEL_GUESTS, guestKeys[index], index) },
                    )
                }
            }
        }
    }
    // Opening (or returning from a guest star's Person page): the remembered panel item, else Close.
    LaunchedEffect(episode.episodeNumber) {
        restorer.restore(
            listOf(
                FocusRow(PANEL, PANEL_ACTIONS, listOf("close")),
                FocusRow(PANEL, PANEL_GUESTS, guestKeys, guestsState),
            ),
        )
    }
}
