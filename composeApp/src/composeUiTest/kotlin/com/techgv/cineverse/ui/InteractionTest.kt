package com.techgv.cineverse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.ActionTile
import com.techgv.cineverse.ui.components.ArtPalette
import com.techgv.cineverse.ui.components.CvIcons
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.components.PosterCard
import com.techgv.cineverse.ui.components.QuickAction
import com.techgv.cineverse.ui.components.RoundIconButton
import com.techgv.cineverse.ui.components.SegmentedControl
import com.techgv.cineverse.ui.haptics.HapticKind
import com.techgv.cineverse.ui.haptics.Haptics
import com.techgv.cineverse.ui.haptics.LocalHaptics
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class InteractionTest {
    private val palette = ArtPalette(Color(0xFFFF3D7F), Color(0xFF6B21FF))

    @Test
    fun disabledControlsAnnounceDisabledAndStayQuiet() = runComposeUiTest {
        val haptics = mutableListOf<HapticKind>()
        val clicks = mutableListOf<String>()
        setThemedContent(ShellStyle.Web) {
            CompositionLocalProvider(LocalHaptics provides Haptics { haptics += it }) {
                Column {
                    PillButton("Enabled pill", { clicks += "enabled" })
                    PillButton("Pill", { clicks += "pill" }, enabled = false)
                    RoundIconButton(CvIcons.Plus, "Round", { clicks += "round" }, enabled = false)
                    ActionTile("Tile", CvIcons.Plus, { clicks += "tile" }, enabled = false)
                    PosterCard("Neon Tide", null, palette, {}, quickAction = QuickAction(true, "Quick", enabled = false) { clicks += "quick" })
                }
            }
        }
        // Control: an enabled button clicks and ticks through the same recorder.
        onNodeWithText("Enabled pill").assertIsEnabled().performClick()
        assertEquals(listOf("enabled"), clicks)
        assertEquals(listOf(HapticKind.Select), haptics)

        for (node in listOf(onNodeWithText("Pill"), onNodeWithContentDescription("Round"), onNodeWithText("Tile"), onNodeWithContentDescription("Quick"))) {
            node.assertIsNotEnabled().performClick()
        }
        waitForIdle()
        assertEquals(listOf("enabled"), clicks, "a disabled control ran its callback")
        assertEquals(listOf(HapticKind.Select), haptics, "a disabled control fired a haptic")
    }

    @Test
    fun keyboardFocusReachesQuickActionWithAnUnclippedRing() = runComposeUiTest {
        setThemedContent(ShellStyle.Web) {
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
            Box(Modifier.padding(24.dp)) {
                PosterCard("Neon Tide", "Mar 13, 2026", palette, {}, quickAction = QuickAction(false, "Add to watchlist") {})
            }
        }
        onNodeWithText("Mar 13, 2026").requestFocus()
        waitForIdle()
        onNodeWithText("Mar 13, 2026").performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        val action = onNodeWithContentDescription("Add to watchlist").assertIsFocused()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val bounds = action.fetchSemanticsNode().boundsInRoot
        // The ring: 2dp white stroke centred 4dp outside the 48dp target, here at its top.
        val x = bounds.center.x.roundToInt()
        val y = (bounds.top - with(density) { 4.dp.toPx() }).roundToInt()
        val pixel = onRoot().captureToImage().toPixelMap()[x, y]
        assertTrue(pixel.red > 0.9f && pixel.green > 0.9f && pixel.blue > 0.9f, "no focus ring above the quick action at ($x, $y): $pixel")
    }

    @Test
    fun mobileSegmentsAreFullTouchTargets() = runComposeUiTest {
        setThemedContent(ShellStyle.Mobile) {
            Column {
                SegmentedControl(listOf("Movies", "TV shows"), 0, {}, Modifier.width(300.dp))
                SegmentedControl(listOf("Today", "This week"), 0, {}, Modifier.width(176.dp), compact = true)
            }
        }
        for (label in listOf("Movies", "TV shows", "Today", "This week")) {
            onNodeWithText(label).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun webRoundButtonShowsItsLabelOnHover() = runComposeUiTest {
        setThemedContent(ShellStyle.Web) { Box(Modifier.padding(80.dp)) { RoundIconButton(CvIcons.Plus, "Add to list", {}) } }
        onNodeWithText("Add to list").assertDoesNotExist()
        onNodeWithContentDescription("Add to list").performMouseInput { moveTo(center) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Add to list")).fetchSemanticsNodes().isNotEmpty() }
    }
}
