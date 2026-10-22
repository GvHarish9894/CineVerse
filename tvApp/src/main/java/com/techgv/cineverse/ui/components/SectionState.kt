package com.techgv.cineverse.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.techgv.cineverse.presentation.SectionErrorKind
import com.techgv.cineverse.presentation.SectionState

/** §3.3 copy of each section error (TV). */
val SectionErrorKind.message: String
    get() = when (this) {
        SectionErrorKind.Offline -> "You're offline. Check your connection."
        SectionErrorKind.NotAvailable -> "Not available"
        SectionErrorKind.AppCredential -> "CineVerse can't reach TMDB right now (app configuration)."
        SectionErrorKind.SessionExpired -> "Sign-in expired"
        SectionErrorKind.Generic -> "Something went wrong."
    }

/** Loading / error with a focusable "Try again" / empty / content for one section. */
@Composable
fun <T> Section(
    state: SectionState<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    empty: @Composable () -> Unit = { SectionMessage("Nothing here yet") },
    content: @Composable (T) -> Unit,
) {
    Box(modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.CenterStart) {
        when (state) {
            SectionState.Loading -> SectionMessage("Loading…")
            SectionState.Empty -> empty()
            is SectionState.Content -> content(state.value)
            is SectionState.Error -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionMessage(state.kind.message)
                if (state.kind.retryable) Button(onClick = onRetry) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun SectionMessage(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
