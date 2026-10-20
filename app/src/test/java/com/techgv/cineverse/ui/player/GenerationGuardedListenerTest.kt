package com.techgv.cineverse.ui.player

import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerError
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.techgv.cineverse.util.GenerationCounter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class GenerationGuardedListenerTest {
    private val player = Proxy.newProxyInstance(YouTubePlayer::class.java.classLoader, arrayOf(YouTubePlayer::class.java)) { _, _, _ ->
        throw UnsupportedOperationException()
    } as YouTubePlayer

    private class Recorder : AbstractYouTubePlayerListener() {
        val events = mutableListOf<String>()
        override fun onReady(youTubePlayer: YouTubePlayer) { events += "ready" }
        override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerState) { events += "state:$state" }
        override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) { events += "second:$second" }
        override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) { events += "duration:$duration" }
        override fun onError(youTubePlayer: YouTubePlayer, error: PlayerError) { events += "error:$error" }
    }

    private fun GenerationGuardedListener.fireAll() {
        onReady(player)
        onStateChange(player, PlayerState.PLAYING)
        onCurrentSecond(player, 12f)
        onVideoDuration(player, 90f)
        onError(player, PlayerError.VIDEO_NOT_FOUND)
    }

    @Test
    fun `old-generation callbacks after a new factory are ignored`() {
        val generation = GenerationCounter()
        val old = Recorder()
        val oldListener = GenerationGuardedListener(generation, generation.bump(), old)
        val current = Recorder()
        val currentListener = GenerationGuardedListener(generation, generation.bump(), current) // factory ran again

        oldListener.fireAll()
        currentListener.fireAll()

        assertEquals(emptyList<String>(), old.events)
        assertEquals(listOf("ready", "state:PLAYING", "second:12.0", "duration:90.0", "error:VIDEO_NOT_FOUND"), current.events)
    }
}
