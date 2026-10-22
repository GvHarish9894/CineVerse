package com.techgv.cineverse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.techgv.cineverse.ui.feedback.FeedbackRoot

sealed interface ImportDialogState {
    /** [resume]: an earlier import did not finish; [items] titles are left. */
    data class Ask(val accountName: String, val items: Int, val resume: Boolean = false) : ImportDialogState
    data object Running : ImportDialogState
    /** Finished with [unconfirmed] steps left: "Couldn't confirm {n} items" + Retry (with the re-add warning). */
    data class Unconfirmed(val written: Int, val titles: List<String>) : ImportDialogState
    /** Reading the account's lists failed; nothing was written. */
    data object Failed : ImportDialogState
}

/**
 * §8.3 Import dialog. Ask: **Keep separate** (default focus, also BACK) / Import; resuming: **Later** (default, also
 * BACK) / Resume. Running: **Later** (focused, also BACK) stops the import. BACK on a result closes it like Done / Later.
 */
@Composable
fun ImportLibraryDialog(
    state: ImportDialogState,
    onKeepSeparate: () -> Unit,
    onImport: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onLater: () -> Unit,
) {
    val first = remember(state) { FocusRequester() }
    val dismiss = when (state) {
        is ImportDialogState.Ask -> if (state.resume) onLater else onKeepSeparate
        ImportDialogState.Running -> onLater
        else -> onClose
    }
    Dialog(onDismissRequest = dismiss) {
        // A Dialog window is a new composition root: re-apply the design canvas and the §9 key hook.
        DesignCanvas { FeedbackRoot {
        Column(
            Modifier.width(620.dp).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large).padding(32.dp)
                // The window's initial focus enters top-down (the titles list); it belongs on [first].
                .focusProperties { onEnter = { if (requestedFocusDirection != FocusDirection.Enter) first.requestFocus() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (state) {
                is ImportDialogState.Ask -> if (state.resume) {
                    Heading("Resume importing your guest library?")
                    Body("${state.items} ${if (state.items == 1) "item" else "items"} left to import into ${state.accountName}.")
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = onLater, modifier = Modifier.focusRequester(first)) { Text("Later") }
                        OutlinedButton(onClick = onImport) { Text("Resume") }
                    }
                } else {
                    Heading("Import this TV's guest library into ${state.accountName}?")
                    Body(
                        "${state.items} ${if (state.items == 1) "title" else "titles"} from this TV will be added to your TMDB " +
                            "watchlist, favourites and ratings. Ratings you already have on TMDB are kept. " +
                            "The import can't be undone automatically.",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = onKeepSeparate, modifier = Modifier.focusRequester(first)) { Text("Keep separate") }
                        OutlinedButton(onClick = onImport) { Text("Import") }
                    }
                }
                ImportDialogState.Running -> {
                    Heading("Importing your guest library…")
                    Body("This only takes a moment. Later stops it; what was imported so far stays on TMDB.")
                    OutlinedButton(onClick = onLater, modifier = Modifier.focusRequester(first)) { Text("Later") }
                }
                is ImportDialogState.Unconfirmed -> {
                    val n = state.titles.size
                    Heading("Couldn't confirm $n ${if (n == 1) "item" else "items"}")
                    // Bounded so the warning and Done / Retry stay on screen however many titles there are.
                    ScrollableText(
                        state.titles.joinToString("\n"),
                        MaterialTheme.typography.bodyLarge,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        Modifier.heightIn(max = 180.dp),
                    )
                    Body(
                        "${state.written} imported. TMDB didn't confirm these. Retrying may re-add items " +
                            "you removed on TMDB since.",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = onClose, modifier = Modifier.focusRequester(first)) { Text("Done") }
                        OutlinedButton(onClick = onRetry) { Text("Retry") }
                    }
                }
                ImportDialogState.Failed -> {
                    Heading("Couldn't read your TMDB library")
                    Body("Nothing was imported. You can try again now or later.")
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = onRetry, modifier = Modifier.focusRequester(first)) { Text("Try again") }
                        OutlinedButton(onClick = onClose) { Text("Later") }
                    }
                }
            }
        }
    } } }
    LaunchedEffect(first) { first.requestFocus() }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
