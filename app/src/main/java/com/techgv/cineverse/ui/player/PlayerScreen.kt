package com.techgv.cineverse.ui.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

/** State of one YouTubePlayerView instance; a new View (e.g. after recreation) starts with `loaded = false`. */
private class PlayerViewSession(val videoKey: String) {
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
fun PlayerScreen(route: Route.Player, onBack: () -> Unit) {
    val vm: PlayerViewModel = viewModel { PlayerViewModel(createSavedStateHandle()) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val strip = remember { StripState(vm.positionSeconds) }
    var session by remember { mutableStateOf<PlayerViewSession?>(null) }

    PlayerLayout(
        modifier = Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()
            .padding(horizontal = 48.dp, vertical = 27.dp),
        player = {
            AndroidView(
                // Created AND initialized here: runs once per Player route composition, never on recomposition.
                factory = { ctx ->
                    val generation = vm.generation.bump()
                    val s = PlayerViewSession(route.videoKey)
                    val view = YouTubePlayerView(ctx).apply {
                        enableAutomaticInitialization = false
                        // The control strip owns D-pad focus; the WebView must never take it.
                        isFocusable = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    }
                    val options = IFramePlayerOptions.Builder(ctx).controls(0).rel(0).ivLoadPolicy(3).build()
                    view.initialize(object : AbstractYouTubePlayerListener() {
                        override fun onReady(youTubePlayer: YouTubePlayer) {
                            if (!vm.generation.isCurrent(generation)) return // obsolete route instance
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
                    }, options)
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_PAUSE -> s.player?.pause() // 13.0.0 itself reacts only at ON_STOP
                            Lifecycle.Event.ON_RESUME -> s.startIfNeeded(vm.positionSeconds) // covers onReady while backgrounded
                            else -> Unit
                        }
                    }
                    s.lifecycleObserver = observer
                    lifecycle.addObserver(view)
                    lifecycle.addObserver(observer)
                    session = s
                    view
                },
                onRelease = { view ->
                    vm.generation.bump()
                    session?.lifecycleObserver?.let(lifecycle::removeObserver)
                    // A DESTROYED lifecycle already delivered ON_DESTROY, on which the view released itself.
                    val releasedByLifecycle = lifecycle.currentState == Lifecycle.State.DESTROYED
                    lifecycle.removeObserver(view)
                    if (!releasedByLifecycle) view.release()
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
                fun seekBy(delta: Float) {
                    val p = player() ?: return
                    val target = (strip.position + delta).coerceIn(0f, strip.duration)
                    p.seekTo(target)
                    strip.position = target // onCurrentSecond does not fire while paused; repeated presses accumulate
                }
                OutlinedButton(onClick = { seekBy(-10f) }, enabled = strip.controlsEnabled) { Text("−10 s") }
                Button(
                    onClick = {
                        val p = player() ?: return@Button
                        when (strip.playerState) {
                            PlayerState.PLAYING, PlayerState.BUFFERING -> p.pause()
                            PlayerState.ENDED -> { p.seekTo(0f); p.play() }
                            else -> p.play()
                        }
                    },
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
                OutlinedButton(onClick = { seekBy(10f) }, enabled = strip.controlsEnabled) { Text("+10 s") }
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
