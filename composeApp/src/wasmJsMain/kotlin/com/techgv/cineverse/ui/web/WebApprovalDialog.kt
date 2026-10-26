package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.ui.components.ButtonVariant
import com.techgv.cineverse.ui.components.CvDialog
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.bodyStyle

/** Host once above routes. Removing this host does not dispose the app-lifetime owner. */
@Composable
fun WebApprovalDialog(owner: WebApprovalOwner, restoreFocus: FocusRequester) {
    val state by owner.state.collectAsState()
    if (state.phase == WebApprovalPhase.Closed) return
    CvDialog(
        onDismissRequest = owner::dismiss,
        title = "Sign in with TMDB",
        kicker = "YOUR BROWSER ACCOUNT",
        restoreFocus = restoreFocus,
        modifier = Modifier.testTag("web-approval-dialog"),
    ) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("web-approval-content"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("1. Prepare a sign-in request.\n2. Continue to TMDB in a separate tab and approve.\n3. Return here and check your browser session.",
                style = bodyStyle(15.sp), color = Tokens.Colors.TextPrimary)
            Text(when (state.phase) {
                WebApprovalPhase.Starting -> "Preparing your request. No TMDB tab has been opened."
                WebApprovalPhase.Waiting -> if (state.openRequested) "An open was requested. If no tab appeared, allow popups and choose Open TMDB again. After approving, return here and check session."
                    else "Your request is ready. Choose Continue to TMDB to request a new tab."
                WebApprovalPhase.Checking -> "Checking the browser session. This does not retry sign-in or cancellation."
                WebApprovalPhase.Cancelling -> "Cancelling browser sign-in requests. Any already committed sign-in stays signed in."
                WebApprovalPhase.Uncertain -> when (state.issue) {
                    WebApprovalIssue.CancelUnknown -> "Cancellation could not be confirmed. A session check cannot prove that pending requests were cancelled. Check session or explicitly try Not now again."
                    else -> "The request may have reached the server, but no usable approval link was received. Check session or choose Not now to cancel browser sign-in requests."
                }
                WebApprovalPhase.Error -> when (state.issue) {
                    WebApprovalIssue.StartUnsent -> "Approval did not start. You can explicitly try preparing it again."
                    WebApprovalIssue.Open -> "The browser could not request a new tab. You can try opening the same request again."
                    else -> "Session could not be checked. Retry the read; sign-in requests will not be replayed."
                }
                else -> "Prepare a request when your browser session is verified as signed out."
            }, style = bodyStyle(15.sp), color = Tokens.Colors.TextSecondary)
            Text(when (val session = state.session) {
                WebSessionState.Checking -> "Browser identity is being verified."
                is WebSessionState.Failed -> "Browser identity is unknown. Check session before starting or opening TMDB."
                is WebSessionState.Ready -> if (session.session == null) "No verified browser sign-in is currently shown. Returning from TMDB alone is not proof of sign-in."
                    else "This browser has a verified signed-in identity."
            }, style = bodyStyle(15.sp), color = Tokens.Colors.TextSecondary)
            Text("Not now cancels all pending sign-in requests for this browser, including other tabs. It does not sign out an existing session.",
                style = bodyStyle(15.sp), color = Tokens.Colors.TextSecondary)
            if (state.canStart) PillButton("Prepare sign-in", owner::startApproval, Modifier.fillMaxWidth())
            if (state.canOpen) PillButton(if (state.openRequested) "Open TMDB again" else "Continue to TMDB", owner::continueToTmdb, Modifier.fillMaxWidth())
            PillButton("Check session", owner::approvalReturned, Modifier.fillMaxWidth(), variant = ButtonVariant.Ghost, enabled = state.canCheck)
            PillButton("Not now", owner::dismiss, Modifier.fillMaxWidth(), variant = ButtonVariant.Ghost,
                enabled = state.phase != WebApprovalPhase.Cancelling)
        }
    }
}
