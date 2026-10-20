package com.techgv.cineverse.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** One §7.12 toast; identity (not equality) keys its 2.4 s timer, so an identical repeat restarts it. */
class AccountToast(val title: String, val subtitle: String)

/**
 * shortcut: lane-3 stand-in for the shared §7.12 toast host (lane 1 owns ui/components/Toast.kt); the merge
 * replaces it. Queue of 1: a new toast replaces the shown one.
 */
class AccountToasts {
    var current by mutableStateOf<AccountToast?>(null)
        private set

    fun show(title: String, subtitle: String) {
        current = AccountToast(title, subtitle)
    }

    fun dismiss(toast: AccountToast) {
        if (current === toast) current = null
    }
}

/** Bottom-centre, never focusable, announced as a polite live region. */
@Composable
fun AccountToastHost(toasts: AccountToasts, modifier: Modifier = Modifier) {
    val toast = toasts.current ?: return
    LaunchedEffect(toast) {
        delay(TOAST_MILLIS)
        toasts.dismiss(toast)
    }
    Row(
        modifier
            .padding(bottom = 33.dp)
            .background(Color(0xF218152C), RoundedCornerShape(18.dp))
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(start = 12.dp, top = 10.dp, end = 18.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge) }
        Column {
            Text(toast.title, style = MaterialTheme.typography.titleSmall, color = Color.White)
            if (toast.subtitle.isNotEmpty()) {
                Text(toast.subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

private const val TOAST_MILLIS = 2_400L
