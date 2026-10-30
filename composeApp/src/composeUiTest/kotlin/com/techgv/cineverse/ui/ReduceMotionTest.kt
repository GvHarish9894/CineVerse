package com.techgv.cineverse.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.ArtPalette
import com.techgv.cineverse.ui.components.CvBottomSheet
import com.techgv.cineverse.ui.components.GlassTopBar
import com.techgv.cineverse.ui.components.PosterCard
import com.techgv.cineverse.ui.components.QuickAction
import com.techgv.cineverse.ui.theme.AppearanceSettings
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The app's Reduce motion setting: entering primitives fade in place (no scale, rise or slide). Each check samples the
 * bounds two frames into the animation and once settled; the normal-motion twins prove the sampling sees motion.
 */
@OptIn(ExperimentalTestApi::class)
class ReduceMotionTest {
    private val palette = ArtPalette(Color(0xFFFF3D7F), Color(0xFF6B21FF))

    /** Bounds of [node] two frames after [trigger], then after every animation settled. */
    private fun ComposeUiTest.earlyAndSettled(node: () -> Rect, trigger: () -> Unit): Pair<Rect, Rect> {
        mainClock.autoAdvance = false
        trigger()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val early = node()
        mainClock.advanceTimeBy(2_000)
        return early to node()
    }

    private fun assertSame(early: Rect, settled: Rect, what: String) =
        assertTrue(
            abs(early.left - settled.left) < 0.5f && abs(early.top - settled.top) < 0.5f && abs(early.width - settled.width) < 0.5f,
            "$what moved/scaled under reduce motion: early=$early settled=$settled",
        )

    private fun assertMoved(early: Rect, settled: Rect, what: String) =
        assertTrue(abs(early.top - settled.top) > 0.5f || abs(early.width - settled.width) > 0.5f, "$what did not animate: $early")

    private fun ComposeUiTest.quickAction(reduceMotion: Boolean): Pair<Rect, Rect> {
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = reduceMotion)) {
            PosterCard("Neon Tide", "Mar 13, 2026", palette, {}, quickAction = QuickAction(false, "Add to watchlist") {})
        }
        return earlyAndSettled({ onNodeWithContentDescription("Add to watchlist").fetchSemanticsNode().boundsInRoot }) {
            onNodeWithText("Mar 13, 2026").performMouseInput { moveTo(center) }
        }
    }

    @Test
    fun reduceMotionShowsQuickActionWithoutScale() = runComposeUiTest {
        val (early, settled) = quickAction(reduceMotion = true)
        assertSame(early, settled, "quick action")
    }

    @Test
    fun normalMotionScalesQuickActionIn() = runComposeUiTest {
        val (early, settled) = quickAction(reduceMotion = false)
        assertMoved(early, settled, "quick action")
    }

    private fun ComposeUiTest.topBarTitle(reduceMotion: Boolean): Pair<Rect, Rect> {
        var solid by mutableStateOf(false)
        setThemedContent(ShellStyle.Mobile, AppearanceSettings(reduceMotion = reduceMotion)) {
            Column(Modifier.width(390.dp)) { GlassTopBar(solid, title = "Neon Tide") }
        }
        return earlyAndSettled({ onNodeWithText("Neon Tide").fetchSemanticsNode().boundsInRoot }) { solid = true }
    }

    @Test
    fun reduceMotionShowsTopBarTitleWithoutRise() = runComposeUiTest {
        val (early, settled) = topBarTitle(reduceMotion = true)
        assertSame(early, settled, "top bar title")
    }

    @Test
    fun normalMotionRaisesTopBarTitle() = runComposeUiTest {
        val (early, settled) = topBarTitle(reduceMotion = false)
        assertMoved(early, settled, "top bar title")
    }

    private fun ComposeUiTest.sheet(reduceMotion: Boolean): Pair<Rect, Rect> {
        var open by mutableStateOf(false)
        setThemedContent(ShellStyle.Mobile, AppearanceSettings(reduceMotion = reduceMotion)) {
            if (open) CvBottomSheet(onDismissRequest = { open = false }) { Text("Sheet body") }
        }
        return earlyAndSettled({ onNodeWithText("Sheet body").fetchSemanticsNode().boundsInRoot }) { open = true }
    }

    @Test
    fun reduceMotionOpensSheetWithoutSlide() = runComposeUiTest {
        val (early, settled) = sheet(reduceMotion = true)
        assertSame(early, settled, "bottom sheet")
    }

    @Test
    fun normalMotionSlidesSheetIn() = runComposeUiTest {
        val (early, settled) = sheet(reduceMotion = false)
        assertMoved(early, settled, "bottom sheet")
    }
}
