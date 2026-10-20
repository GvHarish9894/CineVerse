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
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException

/** Per-section async state (§1.1): a failed row never blanks the screen. */
sealed interface SectionState<out T> {
    data object Loading : SectionState<Nothing>
    data class Content<T>(val value: T) : SectionState<T>
    data object Empty : SectionState<Nothing>
    data class Error(val kind: SectionErrorKind) : SectionState<Nothing>
}

enum class SectionErrorKind(val message: String, val retryable: Boolean) {
    /** Network failure: distinct from empty (§3.4 offline state). */
    Offline("You're offline. Check your connection.", true),
    NotAvailable("Not available", false),
    AppCredential("CineVerse can't reach TMDB right now (app configuration).", false),
    /** The app is already signing out and returning to Login (AuthRepository.sessionExpired). */
    SessionExpired("Sign-in expired", false),
    Generic("Something went wrong.", true),
}

fun Throwable.toSectionErrorKind(): SectionErrorKind = when ((this as? TmdbException)?.error) {
    TmdbError.Network -> SectionErrorKind.Offline
    TmdbError.NotFound -> SectionErrorKind.NotAvailable
    TmdbError.AppCredential -> SectionErrorKind.AppCredential
    TmdbError.SessionExpired -> SectionErrorKind.SessionExpired
    else -> SectionErrorKind.Generic
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
