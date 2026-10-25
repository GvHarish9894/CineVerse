package com.techgv.cineverse.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.SegmentedControl
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SegmentedControlTest {
    @Test
    fun selectingMovesSelectionAndReportsIndex() = runComposeUiTest {
        val reported = mutableListOf<Int>()
        setThemedContent {
            var selected by remember { mutableIntStateOf(0) }
            SegmentedControl(listOf("Movies", "TV shows", "People"), selected, { selected = it; reported += it }, Modifier.width(350.dp))
        }
        onNodeWithText("Movies").assertIsSelected()
        onNodeWithText("People").assertIsNotSelected()

        onNodeWithText("People").performClick()
        onNodeWithText("People").assertIsSelected()
        onNodeWithText("Movies").assertIsNotSelected()

        // Re-tapping the selected segment is not a change.
        onNodeWithText("People").performClick()
        onNodeWithText("TV shows").performClick()
        assertEquals(listOf(2, 1), reported)
        onNodeWithText("TV shows").assertIsSelected()
    }

    @Test
    fun webVariantBehavesTheSame() = runComposeUiTest {
        setThemedContent(ShellStyle.Web) {
            var selected by remember { mutableIntStateOf(1) }
            SegmentedControl(listOf("Today", "This week"), selected, { selected = it }, Modifier.width(176.dp), compact = true)
        }
        onNodeWithText("This week").assertIsSelected()
        onNodeWithText("Today").performClick()
        onNodeWithText("Today").assertIsSelected()
    }
}
