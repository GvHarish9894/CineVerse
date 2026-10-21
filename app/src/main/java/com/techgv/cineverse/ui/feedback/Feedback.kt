package com.techgv.cineverse.ui.feedback

import android.media.AudioManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import com.techgv.cineverse.data.settings.Settings

/**
 * §9 feedback hooks, provided by [ProvideFeedback]. Components read [reduceMotion] / [ambientGlow]
 * (§6) from `LocalFeedback.current`; they are snapshot-backed, so readers recompose on a Settings change.
 */
interface Feedback {
    fun focusMove(direction: FocusDirection = FocusDirection.Next) {}
    fun rowEdge(direction: FocusDirection = FocusDirection.Next) {}
    fun confirm() {}
    val reduceMotion: Boolean get() = false
    val ambientGlow: Boolean get() = true
}

object NoFeedback : Feedback

val LocalFeedback = staticCompositionLocalOf<Feedback> { NoFeedback }

/** §9 row-edge haptic: two pulses of the strength, 40 ms apart. */
fun edgeWaveform(strengthMillis: Long) = longArrayOf(0, strengthMillis, 40, strengthMillis)

/** `playSoundEffect(NAVIGATION_*)` for a D-pad move. */
fun navigationSound(direction: FocusDirection): Int = when (direction) {
    FocusDirection.Up -> AudioManager.FX_FOCUS_NAVIGATION_UP
    FocusDirection.Down -> AudioManager.FX_FOCUS_NAVIGATION_DOWN
    FocusDirection.Left -> AudioManager.FX_FOCUS_NAVIGATION_LEFT
    FocusDirection.Right -> AudioManager.FX_FOCUS_NAVIGATION_RIGHT
    else -> AudioManager.FX_FOCUS_NAVIGATION_DOWN
}

/**
 * Haptics go to the device's own [Vibrator] (the TV / box, not the remote: Android has no API for remote
 * vibration) and only when it has one — most TVs do not.
 */
class AndroidFeedback(
    private val view: View,
    private val vibrator: Vibrator?,
    private val audio: AudioManager?,
    private val settings: State<Settings>,
) : Feedback {
    private val hasVibrator by lazy { vibrator?.hasVibrator() == true }

    private fun haptics(block: (Vibrator) -> Unit) {
        if (settings.value.haptics && hasVibrator) vibrator?.let(block)
    }

    // The volume overload plays even with system touch sounds off: the in-app toggle is the user's choice.
    private fun sound(effect: Int, volume: Float = DEFAULT_VOLUME) {
        if (settings.value.sounds) audio?.playSoundEffect(effect, volume)
    }

    override fun focusMove(direction: FocusDirection) {
        haptics { it.vibrate(VibrationEffect.createOneShot(settings.value.strength.millis, VibrationEffect.DEFAULT_AMPLITUDE)) }
        sound(navigationSound(direction))
    }

    override fun rowEdge(direction: FocusDirection) {
        haptics { it.vibrate(VibrationEffect.createWaveform(edgeWaveform(settings.value.strength.millis), -1)) }
        sound(navigationSound(direction), EDGE_VOLUME)
    }

    override fun confirm() {
        haptics { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
        sound(AudioManager.FX_KEY_CLICK)
    }

    override val reduceMotion: Boolean get() = settings.value.motion
    override val ambientGlow: Boolean get() = settings.value.glow

    private companion object {
        /** AudioManager: -1 = music stream volume minus 3 dB. */
        const val DEFAULT_VOLUME = -1f
        const val EDGE_VOLUME = 0.2f
    }
}

/** Provides [LocalFeedback] for [settings]; while custom sounds are on, native view sound effects are off (§9). */
@Composable
fun ProvideFeedback(settings: State<Settings>, content: @Composable () -> Unit) {
    val view = LocalView.current
    val feedback = remember(view, settings) {
        AndroidFeedback(
            view,
            view.context.getSystemService(Vibrator::class.java),
            view.context.getSystemService(AudioManager::class.java),
            settings,
        )
    }
    val sounds = settings.value.sounds
    SideEffect {
        view.isSoundEffectsEnabled = !sounds
        view.rootView.isSoundEffectsEnabled = !sounds
    }
    CompositionLocalProvider(LocalFeedback provides feedback, content = content)
}

/**
 * The one §9 hook for a D-pad UI root (rail + content): OK → [Feedback.confirm]; an arrow key nobody
 * consumed moves focus here (same focus search as the default handling) → [Feedback.focusMove], or
 * [Feedback.rowEdge] when there is nothing in that direction. Components that consume arrows themselves
 * (e.g. ←→ value rows) give their own feedback.
 */
fun Modifier.navigationFeedback(feedback: Feedback, focusManager: FocusManager): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 && event.key in OK_KEYS) feedback.confirm()
        false
    }.onKeyEvent { event ->
        val direction = DPAD[event.key] ?: return@onKeyEvent false
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        if (focusManager.moveFocus(direction)) feedback.focusMove(direction) else feedback.rowEdge(direction)
        true
    }

/**
 * [navigationFeedback] for a `Dialog` / `Popup`: each is its own window and focus root, so key events
 * never reach AppNav's hook. Wrap the dialog content once (ExitDialog, Rate, Import); the main content
 * must not use it (AppNav already hooks the main window).
 */
@Composable
fun FeedbackRoot(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.navigationFeedback(LocalFeedback.current, LocalFocusManager.current)) { content() }
}

private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
private val DPAD = mapOf(
    Key.DirectionUp to FocusDirection.Up,
    Key.DirectionDown to FocusDirection.Down,
    Key.DirectionLeft to FocusDirection.Left,
    Key.DirectionRight to FocusDirection.Right,
)
