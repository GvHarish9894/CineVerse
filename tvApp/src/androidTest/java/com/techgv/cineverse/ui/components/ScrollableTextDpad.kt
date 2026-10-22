package com.techgv.cineverse.ui.components

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/** The focused [ScrollableText] region (focusable and scrollable, unlike cards and lazy lists). */
val focusedScrollableText: SemanticsMatcher = isFocusable() and hasScrollAction() and isFocused()

/** A long multi-paragraph text, several screens tall in any region. */
fun longText(label: String): String =
    (1..60).joinToString("\n") { "$label paragraph $it: the quick brown fox jumps over the lazy dog, again and again." }

/**
 * From a focused [ScrollableText], presses DOWN until focus leaves it; asserts it left only at the end of the
 * text. Returns the number of DOWN presses that scrolled (pages read).
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeTestRule.readFocusedScrollableTextByDpad(): Int {
    var pages = 0
    repeat(MAX_PAGES) {
        val region = onNode(focusedScrollableText)
        val range = region.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        region.performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        if (onAllNodes(focusedScrollableText).fetchSemanticsNodes().isEmpty()) {
            assertEquals("focus left before the end of the text", range.maxValue(), range.value(), 0.5f)
            return pages
        }
        pages++
    }
    fail("DOWN never left the text")
    return pages
}

private const val MAX_PAGES = 200
