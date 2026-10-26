package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.setThemedContent
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WebChromeTest {
    @Test fun pageGutterFollowsTheKitColumnAtDesktopTabletAndPhoneWidths() = runComposeUiTest {
        val widths = listOf(1440, 900, 390)
        setThemedContent(ShellStyle.Web) {
            Column {
                widths.forEach { width ->
                    Box(Modifier.requiredWidth(width.dp).testTag("frame-$width")) {
                        WebPage { Text("content-$width", Modifier.testTag("content-$width")) }
                    }
                }
            }
        }
        // `.wrap`: max-width 1320 centred, 32dp gutter (16dp ≤ 640): 92dp at 1440.
        for ((width, inset) in listOf(1440 to 92f, 900 to 32f, 390 to 16f)) {
            val left = onNodeWithTag("content-$width").getBoundsInRoot().left - onNodeWithTag("frame-$width").getBoundsInRoot().left
            assertTrue(abs(left.value - inset) < 0.5f, "width $width: inset ${left.value}, expected $inset")
        }
    }

    @Test fun searchFieldHasNoFloatingLabelAndSubmitsOnlyWhenEnabled() = runComposeUiTest {
        val submits = mutableListOf<String>()
        var query = ""
        setThemedContent(ShellStyle.Web) {
            Column {
                WebSearchField("", { query = it }, { submits += query }, Modifier.requiredWidth(340.dp).testTag("enabled"))
                WebSearchField("kept", {}, { submits += "disabled" }, Modifier.requiredWidth(340.dp).testTag("disabled"), enabled = false)
            }
        }
        onNodeWithTag("enabled").assert(hasContentDescription("Search movies, TV and people"))
        // The placeholder shows only while empty (the disabled field holds "kept"): no label floats over typed text.
        onAllNodesWithText("Search movies, TV and people", useUnmergedTree = true).assertCountEquals(1)
        onNodeWithTag("enabled").performTextInput("arrival")
        onAllNodesWithText("Search movies, TV and people", useUnmergedTree = true).assertCountEquals(0)
        onNodeWithTag("enabled").performImeAction()
        onNodeWithTag("disabled").assertIsNotEnabled()
        assertTrue(submits == listOf("arrival"), "submits: $submits")
    }
}
