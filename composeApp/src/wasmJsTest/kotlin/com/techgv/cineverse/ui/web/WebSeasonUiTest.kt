package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.presentation.SectionState
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.LocalDate
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WebSeasonUiTest {
    private val today = LocalDate(2026, 1, 1)

    @Test fun desktopRendersRealMetadataNrAndNavigationWithoutInventedPlayback() = runComposeUiTest {
        val fake = WebSeasonFake()
        lateinit var owner: WebSeasonOwner
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            val scope = rememberCoroutineScope()
            owner = remember { fake.owner(scope) }
            val state by owner.state.collectAsState()
            DisposableEffect(owner) { onDispose { owner.close() } }
            Box(Modifier.width(1200.dp)) { WebSeasonContent(owner, state, today) }
        }
        waitUntil { owner.state.value.bodyReady }
        onNodeWithText("Real show").assertExists().performClick()
        assertEquals(WebRoute.Detail("tv", 7), fake.destinations.last())
        onNodeWithText("Real network").assertExists()
        onNodeWithTag("web-season").performScrollToNode(hasTestTag("season-7-episode-4"))
        onNodeWithText("4 · Actual episode").assertExists()
        onNodeWithText("2020-01-02 · 42 min").assertExists()
        onNodeWithText("NR").assertExists()
        onNodeWithText("Actual overview").assertExists()
        onNodeWithTag("season-7-episode-4").performClick()
        onNodeWithText("Episode details · 4").assertExists()
        onNodeWithText("Actual guest · Actual role").assertExists()
        onNodeWithText("Play").assertDoesNotExist()
        onNodeWithText("Stream episode").assertDoesNotExist()
    }

    @Test fun narrowSeasonChoiceNavigatesActualSpecialsZeroAndBackIsReal() = runComposeUiTest {
        val fake = WebSeasonFake()
        lateinit var owner: WebSeasonOwner
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            val scope = rememberCoroutineScope()
            owner = remember { fake.owner(scope) }
            val state by owner.state.collectAsState()
            Box(Modifier.width(390.dp)) { WebSeasonContent(owner, state, today) }
        }
        waitUntil { owner.state.value.bodyReady }
        onNodeWithText("Specials").performClick()
        assertEquals(WebRoute.Season(7, 0), fake.destinations.last())
        onNodeWithText("← Back").performClick()
        assertEquals(WebRoute.Home, fake.destinations.last())
        runOnIdle { owner.close() }
    }

    @Test fun futureAndUnknownDatesAreHonestAndNeverRatedAsAired() = runComposeUiTest {
        val fake = WebSeasonFake().apply {
            season = season.copy(episodes = listOf(
                EpisodeDto(1, 1, 7, "Future", airDate = "2030-02-03", voteAverage = 9.0, voteCount = 22),
                EpisodeDto(2, 2, 7, "Unknown", airDate = null, voteAverage = 8.0, voteCount = 11)))
        }
        lateinit var owner: WebSeasonOwner
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            val scope = rememberCoroutineScope()
            owner = remember { fake.owner(scope) }
            val state by owner.state.collectAsState()
            WebSeasonContent(owner, state, today)
        }
        waitUntil { owner.state.value.bodyReady }
        onNodeWithTag("web-season").performScrollToNode(hasText("Airs 2030-02-03"))
        onNodeWithText("Airs 2030-02-03").assertExists()
        onNodeWithTag("web-season").performScrollToNode(hasText("Air date unknown"))
        onNodeWithText("Air date unknown").assertExists()
        onNodeWithText("★ 9.0").assertDoesNotExist()
        onNodeWithText("★ 8.0").assertDoesNotExist()
        runOnIdle { owner.close() }
    }

    @Test fun realVotedEpisodeShowsScoreAndEmptyUnavailableRemainsDifferentFromLoading() = runComposeUiTest {
        val fake = WebSeasonFake().apply { season = season.copy(episodes = season.episodes.map { it.copy(voteCount = 1) }) }
        lateinit var owner: WebSeasonOwner
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            val scope = rememberCoroutineScope()
            owner = remember { fake.owner(scope) }
            val state by owner.state.collectAsState()
            WebSeasonContent(owner, state, today)
        }
        waitUntil { owner.state.value.bodyReady }
        onNodeWithTag("web-season").performScrollToNode(hasText("★ 8.5"))
        onNodeWithText("★ 8.5").assertExists()
        onNodeWithText("Loading episodes…").assertDoesNotExist()
        runOnIdle { owner.close() }
    }

    @Test fun unavailableRequestedSeasonNeverRendersAnotherSeasonBody() = runComposeUiTest {
        val fake = WebSeasonFake()
        lateinit var owner: WebSeasonOwner
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            val scope = rememberCoroutineScope()
            owner = remember { fake.owner(scope, fake.entry(9, mapOf("selectedSeason" to "9"))) }
            val state by owner.state.collectAsState()
            WebSeasonContent(owner, state, today)
        }
        waitUntil { owner.state.value.bodyReady }
        onNodeWithTag("web-season").performScrollToNode(hasText("Requested season is unavailable."))
        onNodeWithText("Requested season is unavailable.").assertExists()
        onNodeWithText("4 · Actual episode").assertDoesNotExist()
        assertTrue(fake.calls.none { it.startsWith("season:") })
        runOnIdle { owner.close() }
    }

    @Test fun independentEpisodeErrorExposesActualRetryAndKeepsShow() = runComposeUiTest {
        val fake = WebSeasonFake().apply { episodeError = TmdbError.Network }
        lateinit var owner: WebSeasonOwner
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            val scope = rememberCoroutineScope()
            owner = remember { fake.owner(scope) }
            val state by owner.state.collectAsState()
            WebSeasonContent(owner, state, today)
        }
        waitUntil { owner.state.value.current?.episodes is SectionState.Error }
        onNodeWithText("Real show").assertExists()
        onNodeWithTag("web-season").performScrollToNode(hasText("Episodes: Offline."))
        val original = owner.state.value.context
        runOnIdle { fake.episodeError = null }
        onNodeWithText("Retry").performClick()
        waitUntil { owner.state.value.bodyReady }
        assertTrue(owner.state.value.context !== original)
        runOnIdle { owner.close() }
    }

    @Test fun delayedRealEpisodeTopologyRestoresActualScrollAndRetryDoesNotReapply() = runComposeUiTest {
        val fake = WebSeasonFake().apply {
            episodeGate = CompletableDeferred()
            season = season.copy(episodes = (1..14).map { EpisodeDto(it, it, 7, "Episode $it", overview = "Real description $it") })
        }
        val entry = fake.entry(values = mapOf("season.index" to "5", "season.offset" to "37"))
        lateinit var owner: WebSeasonOwner
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            val scope = rememberCoroutineScope()
            owner = remember { fake.owner(scope, entry) }
            val state by owner.state.collectAsState()
            Box(Modifier.width(390.dp)) { WebSeasonContent(owner, state, today) }
        }
        waitUntil { owner.state.value.current?.show is SectionState.Content }
        runOnIdle {
            assertEquals("5", entry.getString("season.index"))
            assertEquals("37", entry.getString("season.offset"))
            fake.episodeGate!!.complete(Unit)
        }
        waitUntil { owner.state.value.bodyReady }
        waitUntil { entry.getInt("season.index") == 5 && entry.getInt("season.offset") == 37 }
        onNodeWithTag("season-7-episode-5").assertIsDisplayed()
        onNodeWithTag("web-season").performScrollToIndex(8)
        waitUntil { entry.getInt("season.index") == 8 }
        runOnIdle { owner.retry(owner.state.value.context!!) }
        waitUntil { owner.state.value.bodyReady }
        runOnIdle { assertEquals(8, entry.getInt("season.index")); owner.close() }
    }

    @Test fun initialShowErrorPreservesScrollTargetUntilActualRetryBody() = runComposeUiTest {
        val fake = WebSeasonFake().apply {
            showError = TmdbError.Network
            season = season.copy(episodes = (1..14).map { EpisodeDto(it, it, 7, "Episode $it") })
        }
        val entry = fake.entry(values = mapOf("season.index" to "4", "season.offset" to "37"))
        lateinit var owner: WebSeasonOwner
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            val scope = rememberCoroutineScope()
            owner = remember { fake.owner(scope, entry) }
            val state by owner.state.collectAsState()
            WebSeasonContent(owner, state, today)
        }
        waitUntil { owner.state.value.current?.show is SectionState.Error }
        onNodeWithText("Show: Offline.").assertExists()
        runOnIdle {
            assertEquals("4", entry.getString("season.index"))
            assertEquals("37", entry.getString("season.offset"))
            fake.showError = null
        }
        onNodeWithText("Retry").performClick()
        waitUntil { owner.state.value.bodyReady }
        waitUntil { entry.getInt("season.index") == 4 && entry.getInt("season.offset") == 37 }
        onNodeWithTag("season-7-episode-4").assertIsDisplayed()
        runOnIdle { owner.close() }
    }

    @Test fun realEmptyEpisodeListIsNotUnavailableOrFabricatedEpisode() = runComposeUiTest {
        val fake = WebSeasonFake().apply { season = season.copy(episodes = emptyList()) }
        lateinit var owner: WebSeasonOwner
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            val scope = rememberCoroutineScope()
            owner = remember { fake.owner(scope, fake.entry(values = mapOf("openEpisode" to "4"))) }
            val state by owner.state.collectAsState()
            WebSeasonContent(owner, state, today)
        }
        waitUntil { owner.state.value.bodyReady }
        onNodeWithTag("web-season").performScrollToNode(hasText("No episodes were returned for this season."))
        onNodeWithText("No episodes were returned for this season.").assertExists()
        onNodeWithText("Episode details · 4").assertDoesNotExist()
        onNodeWithText("Requested season is unavailable.").assertDoesNotExist()
        runOnIdle { owner.close() }
    }

    @Test fun keyboardOpensActualDetailsEscapeClosesAndReturnsFocus() = runComposeUiTest {
        val fake = WebSeasonFake()
        lateinit var owner: WebSeasonOwner
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            val scope = rememberCoroutineScope()
            owner = remember { fake.owner(scope) }
            val state by owner.state.collectAsState()
            WebSeasonContent(owner, state, today)
        }
        waitUntil { owner.state.value.bodyReady }
        onNodeWithTag("web-season").performScrollToNode(hasTestTag("season-7-episode-4"))
        onNodeWithTag("season-7-episode-4").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNodeWithTag("season-7-episode-4").performKeyInput { pressKey(Key.Enter) }
        onNodeWithText("Episode details · 4").assertExists()
        onNodeWithTag("season-7-episode-4").performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Episode details · 4").assertDoesNotExist()
        onNodeWithTag("season-7-episode-4").assertIsFocused()
        runOnIdle { owner.close() }
    }
}
