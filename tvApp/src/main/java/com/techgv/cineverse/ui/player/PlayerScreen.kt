package com.techgv.cineverse.ui.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerError
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.YouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import com.techgv.cineverse.ui.nav.Route
import com.techgv.cineverse.util.GenerationCounter
import kotlin.math.min

/** Scalars only (§7.10): the View/WebView (which retains the Activity) is never held here. */
class PlayerViewModel(private val handle: SavedStateHandle) : ViewModel() {
    val generation = GenerationCounter()
    var positionSeconds: Float
        get() = handle["position"] ?: 0f
        set(value) {
            handle["position"] = value
        }
}

/**
 * The player View seam (§7.10): [YouTubePlayerHost] wraps YouTubePlayerView; tests substitute a host
 * without a WebView and fire its callbacks directly.
 */
interface PlayerHost {
    val view: View

    /** Added to the screen lifecycle for the host's own lifecycle handling (YouTubePlayerView: ON_STOP / ON_DESTROY). */
    val lifecycleObserver: LifecycleObserver

    /** Called once per host; [listener] receives every player callback. */
    fun initialize(listener: YouTubePlayerListener)

    fun release()
}

class YouTubePlayerHost(context: Context) : PlayerHost {
    private val playerView = YouTubePlayerView(context).apply {
        enableAutomaticInitialization = false
        // The control strip owns D-pad focus; the WebView must never take it.
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }
    private val options = IFramePlayerOptions.Builder(context).controls(0).rel(0).ivLoadPolicy(3).build()

    override val view: View get() = playerView
    override val lifecycleObserver: LifecycleObserver get() = playerView
    override fun initialize(listener: YouTubePlayerListener) = playerView.initialize(listener, options)
    override fun release() = playerView.release()
}

/** State of one player host; a new host (e.g. after recreation) starts with `loaded = false`. */
private class PlayerViewSession(val videoKey: String, val host: PlayerHost) {
    var player: YouTubePlayer? = null
    var loaded = false
    var lifecycleObserver: LifecycleEventObserver? = null

    /** No-op until onReady stored a player; loads once, afterwards play/pause is the user's call. */
    fun startIfNeeded(startSeconds: Float) {
        val p = player ?: return
        if (!loaded) {
            loaded = true
            p.loadVideo(videoKey, startSeconds)
        }
    }
}

/**
 * Forwards every callback to [target] only while [token] is the current generation: a View created for an
 * obsolete route instance is ignored entirely (§7.10), not only in onReady.
 */
internal class GenerationGuardedListener(
    private val generation: GenerationCounter,
    private val token: Long,
    private val target: YouTubePlayerListener,
) : AbstractYouTubePlayerListener() {
    private inline fun ifCurrent(block: () -> Unit) {
        if (generation.isCurrent(token)) block()
    }

    override fun onReady(youTubePlayer: YouTubePlayer) = ifCurrent { target.onReady(youTubePlayer) }
    override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerState) = ifCurrent { target.onStateChange(youTubePlayer, state) }
    override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) = ifCurrent { target.onCurrentSecond(youTubePlayer, second) }
    override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) = ifCurrent { target.onVideoDuration(youTubePlayer, duration) }
    override fun onError(youTubePlayer: YouTubePlayer, error: PlayerError) = ifCurrent { target.onError(youTubePlayer, error) }
}

/** What the control strip renders; written by player callbacks (main thread). */
private class StripState(initialPosition: Float) {
    var controlsEnabled by mutableStateOf(false)
    var position by mutableFloatStateOf(initialPosition)
    var duration by mutableFloatStateOf(0f)
    var playerState by mutableStateOf(PlayerState.UNKNOWN)
    var error by mutableStateOf<PlayerError?>(null)
    var youTubeMissing by mutableStateOf(false)
}

@Composable
fun PlayerScreen(route: Route.Player, onBack: () -> Unit, hostFactory: (Context) -> PlayerHost = ::YouTubePlayerHost) {
    val vm: PlayerViewModel = viewModel { PlayerViewModel(createSavedStateHandle()) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val strip = remember { StripState(vm.positionSeconds) }
    var session by remember { mutableStateOf<PlayerViewSession?>(null) }

    PlayerLayout(
        modifier = Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()
            .padding(horizontal = 48.dp, vertical = 27.dp)
            // Remote media keys act whichever strip control has focus (TV-PP).
            .onPreviewKeyEvent { strip.onMediaKey(it, session?.player) },
        player = {
            AndroidView(
                // Created AND initialized here: runs once per Player route composition, never on recomposition.
                factory = { ctx ->
                    val generation = vm.generation.bump()
                    val host = hostFactory(ctx)
                    val s = PlayerViewSession(route.videoKey, host)
                    host.initialize(GenerationGuardedListener(vm.generation, generation, object : AbstractYouTubePlayerListener() {
                        override fun onReady(youTubePlayer: YouTubePlayer) {
                            s.player = youTubePlayer
                            strip.controlsEnabled = true
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) s.startIfNeeded(vm.positionSeconds)
                        }

                        override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                            strip.position = second
                            vm.positionSeconds = second
                        }

                        override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                            strip.duration = duration
                        }

                        override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerState) {
                            strip.playerState = state
                        }

                        override fun onError(youTubePlayer: YouTubePlayer, error: PlayerError) {
                            // Every PlayerError (not found, not embeddable, invalid param, HTML5, referer, unknown).
                            strip.error = error
                        }
                    }))
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_PAUSE -> s.player?.pause() // 13.0.0 itself reacts only at ON_STOP
                            Lifecycle.Event.ON_RESUME -> s.startIfNeeded(vm.positionSeconds) // covers onReady while backgrounded
                            else -> Unit
                        }
                    }
                    s.lifecycleObserver = observer
                    lifecycle.addObserver(host.lifecycleObserver)
                    lifecycle.addObserver(observer)
                    session = s
                    host.view
                },
                onRelease = {
                    vm.generation.bump()
                    val s = session ?: return@AndroidView
                    s.lifecycleObserver?.let(lifecycle::removeObserver)
                    // A DESTROYED lifecycle already delivered ON_DESTROY, on which the view released itself.
                    val releasedByLifecycle = lifecycle.currentState == Lifecycle.State.DESTROYED
                    lifecycle.removeObserver(s.host.lifecycleObserver)
                    if (!releasedByLifecycle) s.host.release()
                },
            )
        },
        strip = {
            ControlStrip(
                route = route,
                strip = strip,
                player = { session?.player },
                onOpenInYouTube = { strip.youTubeMissing = !openInYouTube(context, route.videoKey) },
                onBack = onBack,
            )
        },
    )
}

/**
 * Fits the largest 16:9 player into the space left after measuring the strip; nothing overlaps the
 * player bounds (YouTube RMF: no overlays).
 */
@Composable
private fun PlayerLayout(modifier: Modifier, player: @Composable () -> Unit, strip: @Composable () -> Unit) {
    Layout(contents = listOf(player, strip), modifier = modifier) { (playerMeasurables, stripMeasurables), constraints ->
        val stripPlaceable = stripMeasurables.first().measure(constraints.copy(minHeight = 0))
        val available = (constraints.maxHeight - stripPlaceable.height).coerceAtLeast(0)
        val width = min(constraints.maxWidth, available * 16 / 9)
        val height = width * 9 / 16
        val playerPlaceable = playerMeasurables.first().measure(Constraints.fixed(width, height))
        layout(constraints.maxWidth, constraints.maxHeight) {
            playerPlaceable.place((constraints.maxWidth - width) / 2, (available - height) / 2)
            stripPlaceable.place(0, available)
        }
    }
}

@Composable
private fun ControlStrip(
    route: Route.Player,
    strip: StripState,
    player: () -> YouTubePlayer?,
    onOpenInYouTube: () -> Unit,
    onBack: () -> Unit,
) {
    val primary = remember { FocusRequester() }
    val openInYouTube = remember { FocusRequester() }
    // clipToBounds: focus scale / glow of the buttons can never bleed into the player above.
    Column(
        Modifier.fillMaxWidth().clipToBounds().padding(top = 16.dp, bottom = 8.dp, start = 8.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(route.kicker, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
        Text(route.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1)
        val error = strip.error
        if (error != null) {
            Text("This trailer can't be played here", style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onOpenInYouTube, modifier = Modifier.focusRequester(openInYouTube)) { Text("Open in YouTube") }
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
            LaunchedEffect(error) { openInYouTube.requestFocus() }
        } else {
            ProgressBar(strip.position, strip.duration)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { player()?.let { strip.seekBy(it, -10f) } }, enabled = strip.controlsEnabled) { Text("−10 s") }
                Button(
                    onClick = { player()?.let(strip::togglePlayPause) },
                    enabled = strip.controlsEnabled,
                    modifier = Modifier.focusRequester(primary),
                ) {
                    Text(
                        when (strip.playerState) {
                            PlayerState.PLAYING, PlayerState.BUFFERING -> "Pause"
                            PlayerState.ENDED -> "Play again"
                            else -> "Play"
                        },
                    )
                }
                OutlinedButton(onClick = { player()?.let { strip.seekBy(it, 10f) } }, enabled = strip.controlsEnabled) { Text("+10 s") }
                OutlinedButton(onClick = onOpenInYouTube, modifier = Modifier.focusRequester(openInYouTube)) { Text("Open in YouTube") }
            }
            LaunchedEffect(strip.controlsEnabled) {
                (if (strip.controlsEnabled) primary else openInYouTube).requestFocus()
            }
        }
        if (strip.youTubeMissing) {
            Text("YouTube isn't available on this TV.", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
    }
}

private val StripState.playing get() = playerState == PlayerState.PLAYING || playerState == PlayerState.BUFFERING

/** The Play / Pause / Play again button. */
private fun StripState.togglePlayPause(p: YouTubePlayer) = when {
    playing -> p.pause()
    playerState == PlayerState.ENDED -> { p.seekTo(0f); p.play() }
    else -> p.play()
}

private fun StripState.seekBy(p: YouTubePlayer, delta: Float) {
    val target = (position + delta).coerceIn(0f, duration)
    p.seekTo(target)
    position = target // onCurrentSecond does not fire while paused; repeated presses accumulate
}

/** MEDIA_PLAY_PAUSE / PLAY / PAUSE / REWIND / FAST_FORWARD: consumed; one action per press (repeats ignored). */
private fun StripState.onMediaKey(event: KeyEvent, player: YouTubePlayer?): Boolean {
    val action: (YouTubePlayer) -> Unit = when (event.key) {
        Key.MediaPlayPause -> ::togglePlayPause
        Key.MediaPlay -> { p -> if (!playing) togglePlayPause(p) }
        Key.MediaPause -> { p -> if (playing) togglePlayPause(p) }
        Key.MediaRewind -> { p -> seekBy(p, -10f) }
        Key.MediaFastForward -> { p -> seekBy(p, 10f) }
        else -> return false
    }
    val press = event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0
    if (press && controlsEnabled && error == null) player?.let(action)
    return true
}

@Composable
private fun ProgressBar(position: Float, duration: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
            val fraction = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Color.White))
        }
        Text("${formatTime(position)} / ${formatTime(duration)}", style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

private fun formatTime(seconds: Float): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** `vnd.youtube:` hands off to the YouTube app (captions, its own controls). False when it isn't installed. */
private fun openInYouTube(context: Context, videoKey: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, "vnd.youtube:$videoKey".toUri()))
    true
} catch (_: ActivityNotFoundException) {
    false
}
