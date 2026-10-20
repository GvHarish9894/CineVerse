package com.techgv.cineverse.ui.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.techgv.cineverse.ui.components.PlaceholderScreen

/** Phase 4 replaces this placeholder (QR sign-in). [message] is e.g. "Sign-in expired" (§3.3). */
@Composable
fun LoginScreen(onContinueAsGuest: () -> Unit, message: String? = null) {
    PlaceholderScreen("Sign in", "TMDB sign-in arrives in Phase 4.") {
        if (message != null) Text(message, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        val focus = remember { FocusRequester() }
        Button(onClick = onContinueAsGuest, modifier = Modifier.focusRequester(focus)) { Text("Continue as guest") }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
