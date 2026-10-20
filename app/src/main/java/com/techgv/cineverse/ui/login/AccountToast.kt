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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** One §7.12 toast; identity (not equality) keys its 2.4 s timer, so an identical repeat restarts it. */
class AccountToast(val title: String, val subtitle: String?)

/**
 * shortcut: lane-3 placeholder for lane 4's `Toaster` / `LocalToaster` / `ToastHost` (ui/components/Toasts.kt),
 * same `show` signature; the merge replaces it. Queue of 1: a new toast replaces the shown one.
 */
class AccountToasts {
    var current by mutableStateOf<AccountToast?>(null)
        private set

    fun show(title: String, subtitle: String? = null) {
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
            .padding(bottom = 44.dp)
            .background(Color(0xF218152C), RoundedCornerShape(18.dp))
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(start = 16.dp, top = 14.dp, end = 22.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 18.sp) }
        Column {
            Text(toast.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            toast.subtitle?.let { Text(it, fontSize = 12.5.sp, color = Color.White.copy(alpha = 0.7f)) }
        }
    }
}

private const val TOAST_MILLIS = 2_400L
