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
import com.techgv.cineverse.ui.components.ButtonVariant
import com.techgv.cineverse.ui.components.CvDialog
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.bodyStyle

/** Host once above routes, next to WebImportDialog. [restoreFocus] is the Settings/avatar-menu trigger. */
@Composable
fun WebSignOutDialog(owner: WebAccountOwner, restoreFocus: FocusRequester) {
    val state by owner.state.collectAsState()
    if (state.signOut == WebSignOutPhase.Closed) return
    val busy = state.signOut == WebSignOutPhase.SigningOut || state.signOut == WebSignOutPhase.Checking
    val name = state.accountName.orEmpty()
    CvDialog(
        onDismissRequest = owner::dismiss,
        title = "Sign out of CineVerse",
        kicker = "YOUR BROWSER ACCOUNT",
        restoreFocus = restoreFocus,
        modifier = Modifier.testTag("web-sign-out-dialog"),
    ) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("web-sign-out-content"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(when (state.signOut) {
                WebSignOutPhase.Confirm -> "Sign out $name from this browser? Every CineVerse tab in this browser is signed out. Your TMDB account and lists are not changed."
                WebSignOutPhase.SigningOut -> "Signing out $name. This can't be cancelled."
                WebSignOutPhase.Checking -> "Sign-out was not confirmed. Checking the browser session; sign-out is not sent again."
                WebSignOutPhase.SignedOut -> "This browser is now verified as signed out."
                WebSignOutPhase.StillSignedIn -> "Sign-out did not complete. This browser is still signed in as $name."
                WebSignOutPhase.Changed -> "This browser's account changed or is already signed out. Check the account shown before trying again."
                else -> "Sign-out may or may not have completed, and the browser session could not be checked. Check session reads it again; sign-out is never repeated automatically."
            }, style = bodyStyle(15.sp), color = Tokens.Colors.TextPrimary)
            if (state.signOut == WebSignOutPhase.Confirm || state.signOut == WebSignOutPhase.SigningOut) {
                PillButton("Sign out", owner::confirmSignOut, Modifier.fillMaxWidth(), enabled = state.canConfirmSignOut)
            }
            if (state.signOut == WebSignOutPhase.Unknown) {
                PillButton("Check session", owner::checkSignOut, Modifier.fillMaxWidth())
            }
            PillButton(if (state.signOut == WebSignOutPhase.Confirm) "Cancel" else "Close", owner::dismiss,
                Modifier.fillMaxWidth(), variant = ButtonVariant.Ghost, enabled = !busy)
        }
    }
}
