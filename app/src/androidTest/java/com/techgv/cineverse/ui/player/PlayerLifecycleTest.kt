package com.techgv.cineverse.ui.player

import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.YouTubePlayerListener
import com.techgv.cineverse.ui.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/** Counts host creation, initialize and loadVideo; [fireReady] delivers onReady like the WebView would. */
private class FakePlayers {
    var factoryCount = 0
    var loadCount = 0
    val hosts = mutableListOf<Host>()

    private val player = Proxy.newProxyInstance(YouTubePlayer::class.java.classLoader, arrayOf(YouTubePlayer::class.java)) { _, method, _ ->
        if (method.name == "loadVideo") loadCount++
        null // every call PlayerScreen makes (loadVideo, play, pause, seekTo) returns Unit
    } as YouTubePlayer

    inner class Host(context: Context) : PlayerHost {
        var initializeCount = 0
        var listener: YouTubePlayerListener? = null
        override val view = View(context)
        override val lifecycleObserver = LifecycleEventObserver { _, _ -> }
        override fun initialize(listener: YouTubePlayerListener) {
            initializeCount++
            this.listener = listener
        }
        override fun release() = Unit
    }

    fun create(context: Context): PlayerHost = Host(context).also {
        factoryCount++
        hosts += it
    }

    fun fireReady() = hosts.last().listener!!.onReady(player)
}

/** §7.10 lifecycle races, driven through the [PlayerHost] seam: no WebView, no network. */
@RunWith(AndroidJUnit4::class)
class PlayerLifecycleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val route = Route.Player(videoKey = "dQw4w9WgXcQ", title = "Never Gonna Give You Up", kicker = "Trailer")
    private val fakes = FakePlayers()

    @Test
    fun readyWhilePaused_resumeLoadsExactlyOnce() {
        compose.setContent { PlayerScreen(route, onBack = {}, hostFactory = fakes::create) }
        compose.waitForIdle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED) // ON_PAUSE delivered
        compose.runOnUiThread { fakes.fireReady() }
        compose.runOnUiThread { assertEquals(0, fakes.loadCount) }

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertEquals(1, fakes.loadCount) }
        // A later pause / resume never loads again.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertEquals(1, fakes.loadCount) }
        compose.onNodeWithText("Play").assertIsEnabled()
    }

    @Test
    fun recompositionDoesNotRecreateOrReinitializePlayer() {
        var tick by mutableIntStateOf(0)
        compose.setContent { PlayerScreen(route.copy(kicker = "Trailer $tick"), onBack = {}, hostFactory = fakes::create) }
        compose.runOnIdle { fakes.fireReady() }
        repeat(5) {
            tick++
            compose.waitForIdle()
        }
        compose.onNodeWithText("Trailer 5").assertExists() // recomposition really happened
        compose.runOnIdle {
            assertEquals(1, fakes.factoryCount)
            assertEquals(1, fakes.hosts.single().initializeCount)
            assertEquals(1, fakes.loadCount)
        }
    }

    @Ignore("Optional live smoke: needs a TV emulator / device with network and a WebView")
    @Test
    fun liveWebView_smoke_playsTrailer() {
        compose.setContent { PlayerScreen(route, onBack = {}) }
        compose.waitUntil(PLAYBACK_TIMEOUT) { compose.onAllNodesWithText("Pause").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pause").assertIsEnabled()
    }

    private companion object {
        const val PLAYBACK_TIMEOUT = 30_000L
    }
}
