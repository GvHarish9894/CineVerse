package com.techgv.cineverse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.SegmentedControl
import com.techgv.cineverse.ui.components.WebSectionHeader
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SectionHeaderTest {
    private fun ComposeUiTest.header(width: Dp): List<String> {
        val record = mutableListOf<String>()
        setThemedContent(ShellStyle.Web) {
            var selected by remember { mutableIntStateOf(1) }
            Box(Modifier.width(width).testTag("frame")) {
                WebSectionHeader("Trending", onScrollLeft = { record += "left" }, onScrollRight = { record += "right" }) {
                    SegmentedControl(listOf("Today", "This week"), selected, { selected = it; record += "toggle:$it" }, Modifier.width(196.dp))
                }
            }
        }
        val frame = onNodeWithTag("frame").getBoundsInRoot()
        val title = onNodeWithText("Trending").getBoundsInRoot()
        val toggle = onNodeWithText("Today").getBoundsInRoot()
        val right = onNodeWithContentDescription("Scroll right").getBoundsInRoot()
        // The toggle sits inline after the title; the arrows end at the frame's right edge.
        assertTrue(toggle.left > title.right && toggle.top < title.bottom, "toggle inline after the title")
        assertTrue(abs((frame.right - right.right).value) < 1f, "arrows at the right edge")
        // One line for the title: it is never squeezed into a mid-word wrap.
        assertTrue(title.bottom - title.top < 44.dp, "single-line title")
        if (width < 400.dp) assertTrue(right.top >= title.bottom, "phone: arrows wrap below") else assertTrue(right.top < title.bottom, "wide: arrows on the title line")
        onNodeWithText("Today").performClick()
        onNodeWithText("Today").assertIsSelected()
        onNodeWithContentDescription("Scroll left").performClick()
        onNodeWithContentDescription("Scroll right").performClick()
        return record
    }

    @Test fun wideHeaderKeepsToggleInlineAndArrowsAtTheRightEdge() = runComposeUiTest {
        assertEquals(listOf("toggle:0", "left", "right"), header(1200.dp))
    }

    @Test fun phoneHeaderWrapsArrowsBelowInsteadOfBreakingTheTitle() = runComposeUiTest {
        assertEquals(listOf("toggle:0", "left", "right"), header(390.dp))
    }
}
