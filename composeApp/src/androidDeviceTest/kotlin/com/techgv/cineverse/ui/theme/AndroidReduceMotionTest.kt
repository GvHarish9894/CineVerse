package com.techgv.cineverse.ui.theme

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.ui.ReduceMotionLabel
import com.techgv.cineverse.ui.deviceShell
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.withDeviceSetting
import kotlin.test.Test

/** The OS preference is observed: changing the animator duration scale while the UI is shown updates the theme. */
@OptIn(ExperimentalTestApi::class)
class AndroidReduceMotionTest {
    @Test
    fun animatorScaleChangeUpdatesTheTheme() = runComposeUiTest {
        withDeviceSetting("global", "animator_duration_scale", "1") {
            setThemedContent { ReduceMotionLabel() }
            onNodeWithText("motion-normal").assertExists()
            deviceShell("settings put global animator_duration_scale 0")
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("motion-reduced").fetchSemanticsNodes().isNotEmpty() }
            deviceShell("settings put global animator_duration_scale 1")
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("motion-normal").fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
