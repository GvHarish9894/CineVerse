package com.techgv.cineverse.ui.spikes

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.RequestCode
import com.techgv.cineverse.data.auth.SignInOutcome
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.ui.components.PlaceholderScreen
import com.techgv.cineverse.ui.components.QrCode
import kotlinx.coroutines.launch

private const val TAG = "AuthSpike"

/** Spike a: request token → QR → poll → convert → /3/account → persist → username. */
@Composable
fun AuthSpikeScreen(auth: AuthRepository) {
    val credentials by auth.credentials.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<RequestCode?>(null) }
    var polling by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val primary = remember { FocusRequester() }

    // Polling lives in this effect: leaving the screen, BACK or app background cancels it.
    val pollingCode = code
    if (polling && pollingCode != null) {
        LaunchedEffect(pollingCode) {
            status = "Waiting for TMDB to confirm…"
            val outcome = auth.awaitApproval(pollingCode)
            // Credentials are not printed: SignedIn logs only its class name; failures log TMDB codes.
            Log.i(TAG, "outcome=" + if (outcome is SignInOutcome.SignedIn) "SignedIn" else outcome.toString())
            polling = false
            status = when (outcome) {
                is SignInOutcome.SignedIn -> "Signed in as ${outcome.credentials.displayName}".also { code = null }
                SignInOutcome.CodeExpired -> "Code expired — press OK for a new one".also { code = null }
                is SignInOutcome.Rejected -> "TMDB didn't approve this sign-in".also { code = null }
                SignInOutcome.AppCredentialError -> "App credential problem (TMDB key)"
                SignInOutcome.TimedOut -> "Still waiting — press OK to check again"
                SignInOutcome.AlreadyPolling -> "Already checking…"
                is SignInOutcome.Failed -> "Sign-in failed (${outcome.error}) — get a new code".also { code = null }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (polling) {
            polling = false
            status = "Polling cancelled (app in background)"
        }
    }

    PlaceholderScreen("Spike a · Auth", credentials?.let { "Persisted account: ${it.displayName} (v3 id ${it.accountId.value})" } ?: "Signed out") {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            val current = code
            if (current != null) {
                QrCode(current.approvalUrl, contentDescription = "QR code to approve sign-in on TMDB")
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (current != null) {
                    Text("Open on your phone:", style = MaterialTheme.typography.bodyMedium)
                    Text(current.approvalUrl, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
                // One button for both steps, so D-pad focus stays on it when the code arrives.
                Button(
                    onClick = {
                        if (current != null) {
                            polling = true
                        } else {
                            scope.launch {
                                status = "Requesting code…"
                                status = try {
                                    code = auth.createRequestCode()
                                    "Scan the QR, approve, then press \"I've approved it\""
                                } catch (e: TmdbException) {
                                    "Couldn't get a code (${e.error})"
                                }
                            }
                        }
                    },
                    enabled = !polling,
                    modifier = Modifier.focusRequester(primary),
                ) { Text(if (current != null) "I've approved it" else "Get sign-in code") }
                if (credentials != null) {
                    Button(onClick = { scope.launch { auth.signOut(); status = "Signed out · Your library stays on this TV" } }) {
                        Text("Sign out")
                    }
                }
                Text(status, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    LaunchedEffect(polling) { if (!polling) primary.requestFocus() }
}
