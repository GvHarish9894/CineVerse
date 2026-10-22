package com.techgv.cineverse.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.techgv.cineverse.domain.Review
import com.techgv.cineverse.ui.components.ActionButton
import com.techgv.cineverse.ui.components.DetailCardArt
import com.techgv.cineverse.ui.components.ScrollableText

/**
 * Reading panel for one review (prototype review expansion; same inline-panel pattern as the Season episode
 * panel): the full plain text in a D-pad scroll region, and Close. Focus starts on Close and is trapped inside;
 * Close or BACK (handled by the caller) returns focus to the invoking card.
 */
@Composable
internal fun ReviewPanel(review: Review, avatarUrl: String?, onClose: () -> Unit) {
    val close = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(760.dp).heightIn(max = 600.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DetailCardArt(avatarUrl, review.author, Modifier.size(44.dp).clip(CircleShape), initials = true)
                Text(review.author, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                review.rating?.let { Text("★ ${oneDecimal(it)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) }
            }
            ScrollableText(review.text, MaterialTheme.typography.bodyLarge, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f, fill = false))
            ActionButton("Close", "✕", onClose, Modifier.focusRequester(close))
        }
    }
    LaunchedEffect(review.id) { close.requestFocus() }
}
