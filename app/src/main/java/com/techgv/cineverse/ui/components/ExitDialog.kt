package com.techgv.cineverse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text

/** §7.11 Exit: Stay (default focus, also BACK) / Exit app. */
@Composable
fun ExitDialog(onStay: () -> Unit, onExit: () -> Unit) {
    val stay = remember { FocusRequester() }
    Dialog(onDismissRequest = onStay) {
        Column(
            Modifier.background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text("Exit CineVerse?", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onStay, modifier = Modifier.focusRequester(stay)) { Text("Stay") }
                OutlinedButton(onClick = onExit) { Text("Exit app") }
            }
        }
    }
    LaunchedEffect(Unit) { stay.requestFocus() }
}
