package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.MovieDetailDto
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.math.abs
import kotlin.test.*

/** Home card poster -> Detail hero poster through the real route transition and the real Home/Detail content. */
@OptIn(ExperimentalTestApi::class)
class WebSharedElementTest {
    private val title = Title(MediaRef(MediaType.Movie, 7), "Fixture film", "Public catalog overview", "fixture-poster", null, "2026-01-01", 8.1, 15, emptyList(), MediaType.Movie)
    // Only Trending has content, so exactly one Home card carries the poster key.
    private val home = HomeUiState(hero = Hero.OfTitle(title), rows = mapOf(HomeRow.Trending to SectionState.Content(PagedItems(listOf(Hero.OfTitle(title)), 1, false))))
    // A posterPath webImage() rejects: the poster renders (palette only) without any image request.
    private val detail = MovieDetailDto(7, title = "Fixture film", releaseDate = "2026-10-01").toDetail("US").copy(posterPath = "fixture-poster")

    private fun completed(detail: Detail): WebDetailReadState {
        val context = DetailReadContext(detail.ref, "en-US", "US", 0, 1)
        val result = SectionState.Content(detail)
        return WebDetailReadState(result, retained = detail, producer = DetailUiState(result,
            DetailSettingsRead.Ready, context, DetailReadCompletion(context, result), DetailReadContent(context, detail)))
    }

    @Test fun posterKeyIsTheExactMediaIdentity() {
        assertEquals("poster:movie:7", webPosterSharedKey(title.ref))
        assertEquals("poster:movie:7", webPosterSharedKey(detail.ref))
        assertEquals("poster:tv:7", webPosterSharedKey(MediaRef(MediaType.Tv, 7)))
    }

    /** Detail hero poster bounds two and four frames after Home -> Detail (test perf, Mocha 2s: no full settle). */
    private fun ComposeUiTest.homeToDetail(reduceMotion: Boolean): Pair<Rect, Rect> {
        val homeEntry = WebNavigation(FakeHistory("/")).entry.value
        val detailEntry = WebDetailOwnerTest.entry()
        var entry by mutableStateOf(homeEntry)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = reduceMotion)) {
            Box(Modifier.requiredSize(1024.dp, 1100.dp)) {
                WebRouteTransition(entry) { target ->
                    if (target === homeEntry) WebHomeContent(home, homeEntry, WebSessionState.Ready(null), {}, {}, {}, {}, {}, {}, null, {})
                    else WebDetailContent(target, completed(detail), LibraryState.Unknown, WebSessionState.Checking, false, null,
                        {}, {}, { _, _, _ -> }, {}, {}, { _, _, _ -> })
                }
            }
        }
        onNodeWithTag("home-trending-movie:7").assertExists()
        mainClock.autoAdvance = false
        entry = detailEntry
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val early = onNodeWithTag("detail-poster").fetchSemanticsNode().boundsInRoot
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        return early to onNodeWithTag("detail-poster").fetchSemanticsNode().boundsInRoot
    }

    @Test fun homeCardPosterMorphsIntoTheDetailHeroPoster() = runComposeUiTest {
        val (early, later) = homeToDetail(reduceMotion = false)
        // Only a matched key (card and hero) moves the hero poster; an unmatched one fades in already at its place.
        assertTrue(abs(early.top - later.top) > 0.5f || abs(early.width - later.width) > 0.5f, "hero poster did not morph: early=$early later=$later")
    }

    @Test fun reduceMotionCutsToTheDetailHeroPosterWithoutBoundsAnimation() = runComposeUiTest {
        val (early, later) = homeToDetail(reduceMotion = true)
        mainClock.autoAdvance = true
        waitForIdle()
        val settled = onNodeWithTag("detail-poster").fetchSemanticsNode().boundsInRoot
        for (sample in listOf(early, later)) assertTrue(abs(sample.left - settled.left) < 0.5f && abs(sample.top - settled.top) < 0.5f &&
            abs(sample.width - settled.width) < 0.5f, "hero poster moved under reduce motion: $sample settled=$settled")
        onNodeWithTag("web-home").assertDoesNotExist() // The exiting route is gone, not faded.
    }

    /**
     * Forward flight: the Home card seeds the Detail entry (history scalars, URL unchanged), so the hero poster carries the
     * shared key on Detail's first frame while the detail read is still gated; the read then replaces the seed in place.
     */
    @Test fun seededPosterIsPresentInDetailFirstFrameAndMorphsForwardBeforeDetailLoads() = runComposeUiTest {
        val history = FakeHistory("/")
        val navigation = WebNavigation(history)
        val homeEntry = navigation.entry.value
        var entry by mutableStateOf(homeEntry)
        val gate = CompletableDeferred<Unit>()
        val api = WebDetailCatalogFake()
        api.movie = { id, _ -> gate.await(); MovieDetailDto(id, title = "Fixture film", posterPath = "fixture-poster", releaseDate = "2026-10-01") }
        val generation = GenerationCounter()
        val catalog = CatalogRepository(api, generation, { Settings() })
        val session = MutableStateFlow<WebSessionState>(WebSessionState.Ready(null))
        val storage = MutableStateFlow<BrowserStorageFailure?>(null)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = false)) {
            Box(Modifier.requiredSize(1024.dp, 1100.dp)) {
                WebRouteTransition(entry) { target ->
                    if (target === homeEntry) WebHomeContent(home, homeEntry, WebSessionState.Ready(null), {}, {}, {}, {}, {}, {}, null, {},
                        openDetail = { route, seed -> navigation.navigate(route, seed = seed); entry = navigation.entry.value })
                    else {
                        val scope = rememberCoroutineScope()
                        val owner = remember(target) {
                            WebDetailOwner(target, catalog, generation, flowOf(Settings()), session, storage, {}, { throw it }, null, { emptyFlow() }, scope)
                        }
                        DisposableEffect(owner) { onDispose { owner.close() } }
                        val state by owner.state.collectAsState()
                        WebDetailContent(target, state, LibraryState.Unknown, session.value, false, null, {}, {}, { _, _, _ -> }, {}, {}, { _, _, _ -> })
                    }
                }
            }
        }
        fun inDetail(text: String) = onNode(hasText(text) and hasAnyAncestor(hasTestTag("web-detail")))
        onNodeWithTag("home-trending-movie:7").assertExists()
        mainClock.autoAdvance = false
        onNodeWithTag("home-trending-movie:7").performClick()
        assertEquals("/movie/7", history.url) // The seed never reaches the URL.
        mainClock.advanceTimeByFrame()
        onNodeWithTag("detail-poster").assertExists() // First Detail frame, detail read still gated.
        inDetail("Fixture film").assertExists()
        mainClock.advanceTimeByFrame()
        val early = onNodeWithTag("detail-poster").fetchSemanticsNode().boundsInRoot
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val later = onNodeWithTag("detail-poster").fetchSemanticsNode()
        assertFalse(gate.isCompleted)
        assertTrue(abs(early.top - later.boundsInRoot.top) > 0.5f || abs(early.width - later.boundsInRoot.width) > 0.5f,
            "seeded poster did not morph: early=$early later=${later.boundsInRoot}")
        onNodeWithContentDescription("Loading title…").assertExists()
        // Completed mid-flight without a full settle (test perf, Mocha 2s): the read lands while the route still transitions.
        gate.complete(Unit)
        repeat(4) { mainClock.advanceTimeByFrame() }
        inDetail("Fixture film (2026)").assertExists()
        inDetail("Fixture film").assertDoesNotExist() // The seed title is replaced by the loaded header.
        onNodeWithContentDescription("Loading title…").assertDoesNotExist()
        assertEquals(later.id, onNodeWithTag("detail-poster").fetchSemanticsNode().id, "the hero poster remounted when the detail arrived")
    }

    @Test fun routeTransitionMountsEachHistoryEntryOnceAcrossHomeDetailBack() = runComposeUiTest {
        // Back restores a new WebEntry for the same URL (as popstate does), so Home mounts again exactly once.
        val names = listOf("/", "/movie/7", "/").map { WebNavigation(FakeHistory(it)).entry.value }.zip(listOf("home", "detail", "back")).toMap()
        val (home, detail, back) = names.keys.toList()
        var entry by mutableStateOf(home)
        var unrelated by mutableStateOf(0)
        val mounts = mutableListOf<String>()
        val disposals = mutableListOf<String>()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = false)) {
            val tick = unrelated
            WebRouteTransition(entry) { target ->
                val name = names.getValue(target)
                DisposableEffect(Unit) { mounts += name; onDispose { disposals += name } }
                Text("$name $tick")
            }
        }
        fun step(change: () -> Unit, expectedMounts: List<String>, expectedDisposals: List<String>) {
            runOnIdle(change)
            waitForIdle()
            runOnIdle { unrelated++ } // An unrelated root recomposition never remounts the shown entry.
            waitForIdle()
            assertEquals(expectedMounts, mounts)
            assertEquals(expectedDisposals, disposals)
        }
        step({}, listOf("home"), emptyList())
        step({ entry = detail }, listOf("home", "detail"), listOf("home"))
        step({ entry = back }, listOf("home", "detail", "back"), listOf("home", "detail"))
        onNodeWithText("back 3").assertExists()
    }
}
