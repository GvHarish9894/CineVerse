package com.techgv.cineverse.ui.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.abs
import kotlin.test.*

/**
 * A Home row card's poster flies to the real Detail header poster, seeded from the card while the Detail fetch is still
 * pending; Reduce motion makes it a cut. Samples the Detail poster's bounds every frame from its first laid-out frame.
 */
@OptIn(ExperimentalTestApi::class)
class MobileSharedElementTest {
    private val ref = MediaRef(MediaType.Movie, 42) // MobileDetailUiFixture's Detail id
    private val title = Title(ref, "Fixture title", "", null, null, null, 0.0, 0, emptyList(), MediaType.Movie)

    /** Every Detail-poster bound from the first non-empty one until settled, with the catalog fetch held the whole time. */
    private fun ComposeUiTest.detailPosterFrames(reduceMotion: Boolean): List<Rect> {
        val gate = CompletableDeferred<Unit>()
        var detail by mutableStateOf(false)
        setThemedContent(settings = AppearanceSettings(haptics = false, reduceMotion = reduceMotion)) {
            MobileSharedTransitionRoot {
                // No SizeTransform, like NavHost, so the container never clips the poster's bounds.
                AnimatedContent(detail, transitionSpec = { fadeIn() togetherWith fadeOut() using null }) { showDetail ->
                    MobileSharedRoute(this) {
                        if (!showDetail) HomeSection(HomeRow.Trending, SectionState.Content(PagedItems(listOf(Hero.OfTitle(title)), 1, false)),
                            "", MobileNavigator {}, {}, {})
                        else MobileDetailUiFixture(configure = { it.api.gate = gate }, seedPosterPath = title.posterPath,
                            seedTitle = title.title, reduceMotion = reduceMotion)
                    }
                }
            }
        }
        mainClock.autoAdvance = false
        detail = true
        mainClock.advanceTimeByFrame()
        // The seeded poster is in Detail's first frame, before any Detail data exists.
        onNodeWithTag("detail-poster").assertExists()
        assertFalse(gate.isCompleted)
        val poster = { onNodeWithTag("detail-poster").fetchSemanticsNode().boundsInRoot }
        var frames = 1
        while (poster().isEmpty) {
            assertTrue(++frames < 10, "Detail poster never laid out")
            mainClock.advanceTimeByFrame()
        }
        val samples = mutableListOf(poster())
        repeat(120) { mainClock.advanceTimeByFrame(); samples += poster() }
        assertFalse(gate.isCompleted)
        println("MobileSharedElementTest reduceMotion=$reduceMotion frames=$frames first=${samples.take(8)} settled=${samples.last()}")
        return samples
    }

    private fun Rect.near(other: Rect) =
        abs(left - other.left) < 0.5f && abs(top - other.top) < 0.5f && abs(width - other.width) < 0.5f && abs(height - other.height) < 0.5f

    @Test fun reduceMotionCutsThePosterInPlace() = runComposeUiTest {
        val samples = detailPosterFrames(reduceMotion = true)
        val settled = samples.last()
        assertTrue(samples.all { it.near(settled) }, "poster animated under reduce motion: $samples")
    }

    @Test fun normalMotionFliesThePosterFromTheCard() = runComposeUiTest {
        val samples = detailPosterFrames(reduceMotion = false)
        val settled = samples.last()
        assertFalse(samples.first().near(settled), "poster did not fly: first=${samples.first()} settled=$settled")
        assertTrue(samples.takeLast(10).all { it.near(settled) }, "poster did not converge: ${samples.takeLast(10)}")
    }

    @Test fun seededHeaderIsReplacedInPlaceWhenDetailLoads() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        setThemedContent {
            MobileDetailUiFixture(configure = { it.api.gate = gate }, seedPosterPath = title.posterPath, seedTitle = title.title)
        }
        onNodeWithText("Fixture title").assertExists()
        onNodeWithText("Loading title…").assertExists()
        val seeded = onNodeWithTag("detail-poster").fetchSemanticsNode().id
        runOnIdle { gate.complete(Unit) }
        onNodeWithText("Detail movie en-US").assertExists()
        onNodeWithText("Fixture title").assertDoesNotExist()
        onNodeWithText("Loading title…").assertDoesNotExist()
        assertEquals(seeded, onNodeWithTag("detail-poster").fetchSemanticsNode().id, "Detail poster remounted when data loaded")
    }

    @Test fun unseededDetailKeepsTheLoadingPlaceholderWithoutAHeader() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        setThemedContent { MobileDetailUiFixture(configure = { it.api.gate = gate }) }
        onNodeWithText("Loading title…").assertExists()
        onNodeWithTag("detail-poster").assertDoesNotExist()
        runOnIdle { gate.complete(Unit) }
        onNodeWithText("Detail movie en-US").assertExists()
        onNodeWithTag("detail-poster").assertExists()
    }
}
