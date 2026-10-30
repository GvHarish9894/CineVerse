package com.techgv.cineverse.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
        title = "Sign in to CineVerse",
        kicker = "TMDB account",
        restoreFocus = restoreFocus,
        modifier = Modifier.testTag("web-approval-dialog"),
    ) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("web-approval-content"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("We use your TMDB account, so your watchlist, favourites and ratings live on TMDB instead of only in this browser.",
                Modifier.padding(top = 10.dp), style = bodyStyle(15.5.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.8f))
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ApprovalStep(1, "Choose Continue to TMDB to open themoviedb.org in a new tab.")
                ApprovalStep(2, "Approve CineVerse on themoviedb.org, then come back to this tab.")
            }
            Text(when (state.phase) {
                WebApprovalPhase.Starting -> "Requesting sign-in. The new tab continues to themoviedb.org once the request is ready."
                WebApprovalPhase.Waiting -> "Approve CineVerse on themoviedb.org, then come back to this tab. If no tab appeared, allow popups and choose Open TMDB again."
                WebApprovalPhase.Checking -> "Checking the browser session. This does not retry sign-in or cancellation."
                WebApprovalPhase.Cancelling -> "Cancelling browser sign-in requests. Any already committed sign-in stays signed in."
                WebApprovalPhase.Uncertain -> when (state.issue) {
                    WebApprovalIssue.CancelUnknown -> "Cancellation could not be confirmed. A session check cannot prove that pending requests were cancelled. Check session or explicitly try Not now again."
                    else -> "The request may have reached the server, but no usable approval link was received. Check session or choose Not now to cancel browser sign-in requests."
                }
                WebApprovalPhase.Error -> when (state.issue) {
                    WebApprovalIssue.StartUnsent -> "Sign-in did not start. You can choose Continue to TMDB to try again."
                    WebApprovalIssue.Open -> "The browser could not open a TMDB tab. Allow popups for this site, then try again."
                    else -> "Session could not be checked. Retry the read; sign-in requests will not be replayed."
                }
                else -> "Continue to TMDB when your browser session is verified as signed out."
            }, Modifier.padding(top = 6.dp), style = bodyStyle(15.sp), color = Tokens.Colors.TextPrimary)
            Text(when (val session = state.session) {
                WebSessionState.Checking -> "Browser identity is being verified."
                is WebSessionState.Failed -> "Browser identity is unknown. Check session before continuing to TMDB."
                is WebSessionState.Ready -> if (session.session == null) "No verified browser sign-in is currently shown. Returning from TMDB alone is not proof of sign-in."
                    else "This browser has a verified signed-in identity."
            }, style = bodyStyle(14.sp), color = Tokens.Colors.TextSecondary)
            FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.canStart) PillButton("Continue to TMDB", owner::continueToTmdb)
                if (state.canOpen) PillButton("Open TMDB again", owner::openTmdbAgain)
                PillButton("Check session", owner::approvalReturned, variant = ButtonVariant.Ghost, enabled = state.canCheck)
                PillButton("Not now", owner::dismiss, variant = ButtonVariant.Ghost, enabled = state.phase != WebApprovalPhase.Cancelling)
            }
            Text("Not now cancels all pending sign-in requests for this browser, including other tabs. It does not sign out an existing session.",
                style = bodyStyle(13.sp), color = Tokens.Colors.TextTertiary)
        }
    }
}

/** Kit `.step`: a 26dp accent circle with the number, then the instruction. */
@Composable
private fun ApprovalStep(number: Int, text: String) {
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(26.dp).background(Tokens.Colors.Accent, CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", style = bodyStyle(12.5.sp, FontWeight.Bold), color = Tokens.Colors.OnAccent)
        }
        Text(text, Modifier.padding(top = 3.dp), style = bodyStyle(15.sp), color = Tokens.Colors.TextPrimary)
    }
}
