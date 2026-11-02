package com.techgv.cineverse.ui.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.techgv.cineverse.ui.components.PersonCircle
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
    private fun ComposeUiTest.detailPosterFrames(reduceMotion: Boolean, tappedSection: String = "home:${HomeRow.Trending.key}",
                                                 row: HomeRow = HomeRow.Trending, tag: String = "detail-poster"): List<Rect> {
        val gate = CompletableDeferred<Unit>()
        var detail by mutableStateOf(false)
        val origin = MobileSharedOrigin()
        setThemedContent(settings = AppearanceSettings(haptics = false, reduceMotion = reduceMotion)) {
            MobileSharedTransitionRoot(origin) {
                // No SizeTransform, like NavHost, so the container never clips the poster's bounds.
                AnimatedContent(detail, transitionSpec = { fadeIn() togetherWith fadeOut() using null }) { showDetail ->
                    MobileSharedRoute(this) {
                        if (!showDetail) HomeSection(row, SectionState.Content(PagedItems(listOf(Hero.OfTitle(title)), 1, false)),
                            "", MobileNavigator {}, {}, {})
                        else MobileDetailUiFixture(configure = { it.api.gate = gate }, seedPosterPath = title.posterPath,
                            seedTitle = title.title, seedBackdropPath = title.backdropPath, reduceMotion = reduceMotion)
                    }
                }
            }
        }
        mainClock.autoAdvance = false
        origin.active = mobileSharedOriginId(tappedSection, ref) // What the card's click does before navigating.
        detail = true
        mainClock.advanceTimeByFrame()
        // The seeded poster is in Detail's first frame, before any Detail data exists.
        onNodeWithTag("detail-poster").assertExists()
        assertFalse(gate.isCompleted)
        val poster = { onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot }
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

    @Test fun popularOnTvLandscapeCardFliesIntoTheBackdrop() = runComposeUiTest {
        val section = "home:${HomeRow.PopularTv.key}"
        val samples = detailPosterFrames(reduceMotion = false, tappedSection = section, row = HomeRow.PopularTv, tag = "detail-backdrop")
        val settled = samples.last()
        assertFalse(samples.first().near(settled), "backdrop did not fly: first=${samples.first()} settled=$settled")
        assertTrue(samples.takeLast(10).all { it.near(settled) }, "backdrop did not converge: ${samples.takeLast(10)}")
    }

    /** Detail's cast circle → Person header photo, seeded (name + photo) while the person fetch is held. */
    @Test fun castCircleFliesIntoThePersonHeader() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        var person by mutableStateOf(false)
        val origin = MobileSharedOrigin()
        setThemedContent(settings = AppearanceSettings(haptics = false, reduceMotion = false)) {
            val scope = rememberCoroutineScope()
            val fixture = remember { MobilePersonComponentFixture(scope).also { it.api.gate = gate } }
            val model = remember { fixture.model() }
            DisposableEffect(model) { model.setActive(true); onDispose { model.close(); fixture.graph.close() } }
            MobileSharedTransitionRoot(origin) {
                AnimatedContent(person, transitionSpec = { fadeIn() togetherWith fadeOut() using null }) { showPerson ->
                    MobileSharedRoute(this) {
                        if (!showPerson) PersonCircle("Cast member", null, mobilePalette("person:42"), {},
                            artModifier = mobileSharedAvatar(42, "detail-top-cast"))
                        else MobilePersonScreen(model, MobileNavigator {}, {}, seedName = "Cast member", seedProfilePath = null)
                    }
                }
            }
        }
        mainClock.autoAdvance = false
        origin.active = mobileSharedOriginId("detail-top-cast", mobilePersonIdentity(42))
        person = true
        mainClock.advanceTimeByFrame()
        onNodeWithTag("person-avatar").assertExists() // Seeded: present before any person data exists.
        onNodeWithContentDescription("Loading person…").assertExists()
        val avatar = { onNodeWithTag("person-avatar").fetchSemanticsNode().boundsInRoot }
        var frames = 1
        while (avatar().isEmpty) {
            assertTrue(++frames < 10, "Person avatar never laid out")
            mainClock.advanceTimeByFrame()
        }
        val samples = mutableListOf(avatar())
        repeat(120) { mainClock.advanceTimeByFrame(); samples += avatar() }
        assertFalse(gate.isCompleted)
        val settled = samples.last()
        assertFalse(samples.first().near(settled), "avatar did not fly: first=${samples.first()} settled=$settled")
        assertTrue(samples.takeLast(10).all { it.near(settled) }, "avatar did not converge: ${samples.takeLast(10)}")
    }

    @Test fun aCardThatWasNotTappedNeverClaimsTheFlight() = runComposeUiTest {
        // Same title tapped elsewhere (the Top 10 row): the Trending card must not fly into Detail.
        val samples = detailPosterFrames(reduceMotion = false, tappedSection = "home:popular-ten")
        val settled = samples.last()
        assertTrue(samples.all { it.near(settled) }, "untapped duplicate card animated: $samples")
    }

    @Test fun seededHeaderIsReplacedInPlaceWhenDetailLoads() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        setThemedContent {
            MobileDetailUiFixture(configure = { it.api.gate = gate }, seedPosterPath = title.posterPath, seedTitle = title.title)
        }
        onNodeWithText("Fixture title").assertExists()
        onNodeWithContentDescription("Loading title…").assertExists()
        onNodeWithText("Loading title…").assertDoesNotExist()
        onNodeWithTag("detail-poster-skeleton").assertDoesNotExist()
        val seeded = onNodeWithTag("detail-poster").fetchSemanticsNode().id
        runOnIdle { gate.complete(Unit) }
        onNodeWithText("Detail movie en-US").assertExists()
        onNodeWithText("Fixture title").assertDoesNotExist()
        onNodeWithContentDescription("Loading title…").assertDoesNotExist()
        assertEquals(seeded, onNodeWithTag("detail-poster").fetchSemanticsNode().id, "Detail poster remounted when data loaded")
    }

    @Test fun unseededDetailShowsASkeletonPosterUntilDetailLoads() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        setThemedContent { MobileDetailUiFixture(configure = { it.api.gate = gate }) }
        onNodeWithContentDescription("Loading title…").assertExists()
        onNodeWithTag("detail-poster").assertDoesNotExist()
        onNodeWithTag("detail-poster-skeleton").assertExists()
        runOnIdle { gate.complete(Unit) }
        onNodeWithText("Detail movie en-US").assertExists()
        onNodeWithTag("detail-poster").assertExists()
        onNodeWithTag("detail-poster-skeleton").assertDoesNotExist()
        onNodeWithContentDescription("Loading title…").assertDoesNotExist()
    }
}
