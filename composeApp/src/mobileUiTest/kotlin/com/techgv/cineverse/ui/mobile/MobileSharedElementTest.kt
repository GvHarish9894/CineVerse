package com.techgv.cineverse.ui.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.components.ArtStyle
import com.techgv.cineverse.ui.components.PaletteArt
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import kotlin.math.abs
import kotlin.test.*

/**
 * A Home row card's poster flies to the Detail-header poster with the same media key; Reduce motion makes it a cut.
 * Samples the Detail poster's bounds a few frames into the route change and once settled, like ReduceMotionTest.
 */
@OptIn(ExperimentalTestApi::class)
class MobileSharedElementTest {
    private val ref = MediaRef(MediaType.Movie, 5)
    private val title = Title(ref, "Fixture title", "", null, null, null, 0.0, 0, emptyList(), MediaType.Movie)

    private fun ComposeUiTest.detailPoster(reduceMotion: Boolean): Pair<Rect, Rect> {
        var detail by mutableStateOf(false)
        setThemedContent(settings = AppearanceSettings(haptics = false, reduceMotion = reduceMotion)) {
            MobileSharedTransitionRoot {
                AnimatedContent(detail, transitionSpec = { fadeIn() togetherWith fadeOut() }) { showDetail ->
                    MobileSharedRoute(this) {
                        if (!showDetail) HomeSection(HomeRow.Trending, SectionState.Content(PagedItems(listOf(Hero.OfTitle(title)), 1, false)),
                            "", MobileNavigator {}, {}, {})
                        else Box(Modifier.fillMaxSize()) {
                            PaletteArt(null, mobilePalette(ref.key), ArtStyle.Poster,
                                mobileSharedPoster(ref).align(Alignment.BottomEnd).size(116.dp, 174.dp).testTag("detail-poster"))
                        }
                    }
                }
            }
        }
        mainClock.autoAdvance = false
        detail = true
        repeat(3) { mainClock.advanceTimeByFrame() }
        val early = onNodeWithTag("detail-poster").fetchSemanticsNode().boundsInRoot
        mainClock.advanceTimeBy(2_000)
        return early to onNodeWithTag("detail-poster").fetchSemanticsNode().boundsInRoot
    }

    @Test fun reduceMotionCutsThePosterInPlace() = runComposeUiTest {
        val (early, settled) = detailPoster(reduceMotion = true)
        assertTrue(abs(early.left - settled.left) < 0.5f && abs(early.top - settled.top) < 0.5f && abs(early.width - settled.width) < 0.5f,
            "poster animated under reduce motion: early=$early settled=$settled")
    }

    @Test fun normalMotionFliesThePosterFromTheCard() = runComposeUiTest {
        val (early, settled) = detailPoster(reduceMotion = false)
        assertTrue(abs(early.left - settled.left) > 0.5f || abs(early.top - settled.top) > 0.5f || abs(early.width - settled.width) > 0.5f,
            "poster did not fly: early=$early settled=$settled")
    }
}
