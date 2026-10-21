package com.techgv.cineverse.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** §7.12 toast content: icon glyph + title + optional subtitle. */
data class ToastMessage(val title: String, val subtitle: String = "", val icon: String = ActionIcon.CHECK)

const val TOAST_MILLIS = 2_400L

/**
 * Toast queue of 1 (§7.12): one toast shows; one more waits; a newer waiting toast replaces the waiting
 * one. [ToastHost] calls [expire] when the shown toast's time is up.
 */
class ToastState {
    var current by mutableStateOf<ToastMessage?>(null)
        private set
    var pending: ToastMessage? = null
        private set

    /** Bumped per shown toast so an identical message shown twice restarts its timer. */
    var serial by mutableStateOf(0L)
        private set

    fun show(message: ToastMessage) {
        if (current == null) {
            current = message
            serial++
        } else {
            pending = message
        }
    }

    fun expire() {
        current = pending
        pending = null
        if (current != null) serial++
    }
}

/** App-wide toasts; `AppNav` provides the instance and hosts [ToastHost]. */
val LocalToasts = staticCompositionLocalOf { ToastState() }

/**
 * Bottom-centre toast area: never focusable, announced politely through an accessibility live region.
 * Lay it out outside any YouTube player bounds (§7.10).
 */
@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    val message = state.current
    // Keeps the last toast on screen while it animates out.
    val lastShown = remember { arrayOfNulls<ToastMessage>(1) }
    if (message != null) lastShown[0] = message
    val shown = lastShown[0]
    LaunchedEffect(state.serial) {
        if (state.current == null) return@LaunchedEffect
        delay(TOAST_MILLIS)
        state.expire()
    }
    val reduceMotion = LocalSettings.current.motion
    val millis = if (reduceMotion) 0 else 300
    Box(modifier.fillMaxSize().padding(bottom = 44.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(tween(millis)) + slideInVertically(tween(millis)) { it / 3 },
            exit = fadeOut(tween(millis)) + slideOutVertically(tween(millis)) { it / 3 },
        ) {
            if (shown == null) return@AnimatedVisibility
            Row(
                Modifier
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                    .background(Color(0xF218152C), RoundedCornerShape(18.dp))
                    .padding(start = 16.dp, end = 22.dp, top = 14.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).background(UiColors.Accent, CircleShape), contentAlignment = Alignment.Center) {
                    Text(shown.icon, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text(shown.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (shown.subtitle.isNotEmpty()) Text(shown.subtitle, color = UiColors.TextDim, fontSize = 13.sp)
                }
            }
        }
    }
}
