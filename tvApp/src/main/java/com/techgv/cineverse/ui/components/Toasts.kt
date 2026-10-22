package com.techgv.cineverse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

data class Toast(val title: String, val subtitle: String?)

/** §7.12 status toasts: one showing, at most one waiting (a newer one replaces the waiting one). Safe from any thread. */
class Toaster {
    private val queue = Channel<Toast>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun show(title: String, subtitle: String? = null) {
        queue.trySend(Toast(title, subtitle))
    }

    internal val toasts: Flow<Toast> = queue.receiveAsFlow()
}

val LocalToaster = staticCompositionLocalOf { Toaster() }

/**
 * Shows [toaster]'s toasts for 2.4 s each, never focusable, announced as a polite live region. Only as
 * large as the toast: the caller places it via [modifier] (`AppNav`: bottom centre, outside the player).
 */
@Composable
fun ToastHost(toaster: Toaster, modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<Toast?>(null) }
    LaunchedEffect(toaster) {
        toaster.toasts.collect {
            current = it
            delay(TOAST_MILLIS)
            current = null
        }
    }
    val toast = current ?: return
    // Prototype `.toast`: radius 18, padding 14 / 22 / 14 / 16, 36 dp icon tile.
    Row(
        modifier
            .widthIn(max = 640.dp)
            .background(Color(0xF218152C), RoundedCornerShape(18.dp))
            .padding(start = 16.dp, end = 22.dp, top = 14.dp, bottom = 14.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).background(UiColors.Accent, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Text("✓", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column {
            Text(toast.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (!toast.subtitle.isNullOrBlank()) {
                Text(toast.subtitle, color = UiColors.TextDim, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

private const val TOAST_MILLIS = 2_400L
