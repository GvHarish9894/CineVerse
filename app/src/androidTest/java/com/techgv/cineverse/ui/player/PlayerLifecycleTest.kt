package com.techgv.cineverse.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.techgv.cineverse.ui.nav.Route
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** §7.10 lifecycle tests. They need a TV emulator / device with network and a WebView. */
@RunWith(AndroidJUnit4::class)
class PlayerLifecycleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val route = Route.Player(videoKey = "dQw4w9WgXcQ", title = "Never Gonna Give You Up", kicker = "Trailer")

    private fun awaitPlaying() = compose.waitUntil(PLAYBACK_TIMEOUT) {
        compose.onAllNodesWithText("Pause").fetchSemanticsNodes().isNotEmpty()
    }

    @Ignore("No emulator in CI; run on the TV emulator (Phase 8 promotes spike b)")
    @Test
    fun readyWhileBackgrounded_resumeEnablesControlsAndLoadsVideo() {
        compose.setContent { PlayerScreen(route, onBack = {}) }
        // Stopped before the WebView reports onReady: the ready player is kept, loadVideo waits for ON_RESUME.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(BACKGROUND_MILLIS)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitPlaying()
        compose.onNodeWithText("Pause").assertIsEnabled()
    }

    @Ignore("No emulator in CI; run on the TV emulator (Phase 8 promotes spike b)")
    @Test
    fun recompositionDoesNotReinitializePlayer() {
        var tick by mutableIntStateOf(0)
        compose.setContent { PlayerScreen(route.copy(kicker = "Trailer $tick"), onBack = {}) }
        awaitPlaying()
        repeat(5) {
            tick++
            compose.waitForIdle()
        }
        // A re-created / re-initialized View would disable the controls and restart from "Play".
        compose.onNodeWithText("Trailer 5").assertExists()
        compose.onNodeWithText("Pause").assertIsEnabled()
    }

    private companion object {
        const val PLAYBACK_TIMEOUT = 30_000L
        const val BACKGROUND_MILLIS = 10_000L
    }
}
