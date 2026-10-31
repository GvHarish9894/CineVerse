package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techgv.cineverse.presentation.ImportPrompt
import com.techgv.cineverse.presentation.LANGUAGE_NAMES
import com.techgv.cineverse.presentation.REGION_NAMES
import com.techgv.cineverse.ui.components.ButtonVariant
import com.techgv.cineverse.ui.components.CvDialog
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.bodyStyle
import com.techgv.cineverse.ui.mobile.MobileApprovalPhase as Phase

/** Root host for the owner's single arbitrated dialog; every action passes the rendered value back for revalidation. */
@Composable
internal fun MobileAccountDialogHost(owner: MobileAccountOwner, dialog: MobileAccountDialog?) {
    when (dialog) {
        null -> Unit
        is MobileAccountDialog.SignOut -> {
            val name = dialog.session.displayName
            AccountDialog("Sign out of CineVerse", "TMDB account", { owner.dismissSignOut(dialog) }, when (dialog.phase) {
                MobileSignOutPhase.Confirm -> "Sign out $name on this device? Your TMDB account and its lists are not changed."
                MobileSignOutPhase.SigningOut -> "Signing out $name…"
                MobileSignOutPhase.Failed -> "Sign-out didn't complete. You're still signed in as $name. Nothing is retried automatically."
            }) {
                if (dialog.phase == MobileSignOutPhase.Confirm) PillButton("Sign out", { owner.confirmSignOut(dialog) })
                if (dialog.phase != MobileSignOutPhase.SigningOut) {
                    PillButton(if (dialog.phase == MobileSignOutPhase.Confirm) "Cancel" else "Close", { owner.dismissSignOut(dialog) },
                        variant = ButtonVariant.Ghost)
                }
            }
        }
        is MobileAccountDialog.Approval -> AccountDialog("Sign in with TMDB", "TMDB account", owner::dismissApproval, approvalCopy(dialog.phase)) {
            when (dialog.phase) {
                Phase.Waiting, Phase.Resume, Phase.StillWaiting, Phase.Cancelled, Phase.CheckFailed ->
                    PillButton("Check again", owner::checkAgain)
                Phase.StartFailed, Phase.Expired, Phase.Rejected, Phase.Failed ->
                    PillButton(if (dialog.phase == Phase.StartFailed) "Try again" else "Start again", owner::startAgain)
                Phase.Starting, Phase.Checking, Phase.Unavailable -> Unit
            }
            if (dialog.phase == Phase.Resume) PillButton("Start over", owner::startAgain, variant = ButtonVariant.Ghost)
            PillButton(when (dialog.phase) {
                Phase.Resume -> "Not now"
                Phase.Starting, Phase.Waiting, Phase.Checking, Phase.StillWaiting -> "Cancel"
                else -> "Close"
            }, owner::dismissApproval, variant = ButtonVariant.Ghost)
        }
        is MobileAccountDialog.Defaults -> {
            val offer = dialog.offer
            val adult = if (offer.includeAdult) "shown" else "hidden"
            AccountDialog("Use your TMDB defaults?", "Content preferences", { owner.laterDefaults(dialog) },
                "${dialog.session.displayName}'s TMDB account uses ${LANGUAGE_NAMES[offer.language]} in ${REGION_NAMES[offer.region]}, " +
                    "with adult titles $adult. Applying replaces this device's content language, region and adult-title settings.") {
                PillButton("Apply", { owner.resolveDefaults(dialog, apply = true) })
                PillButton("Keep current", { owner.resolveDefaults(dialog, apply = false) }, variant = ButtonVariant.Ghost)
            }
        }
        is MobileAccountDialog.Import -> {
            val prompt = dialog.prompt
            fun plural(n: Int, one: String) = "$n $one${if (n == 1) "" else "s"}"
            val (title, body) = when (prompt) {
                is ImportPrompt.Ask -> if (prompt.resume) "Resume importing your guest library?" to
                    "${plural(prompt.items, "title")} from this device's guest library are left to import into ${prompt.accountName}'s TMDB account."
                else "Import your guest library?" to
                    "${plural(prompt.items, "title")} from this device's guest library will be added to ${prompt.accountName}'s TMDB " +
                    "watchlist, favourites and ratings. Ratings already on TMDB are kept. The import can't be undone automatically."
                ImportPrompt.Running -> "Importing your guest library…" to "Later stops it; anything already imported stays on TMDB."
                is ImportPrompt.Unconfirmed -> "Couldn't confirm ${plural(prompt.titles.size, "item")}" to
                    "${prompt.written} imported. TMDB didn't confirm these: ${prompt.titles.joinToString()}. " +
                    "Retrying may re-add items you removed on TMDB since."
                ImportPrompt.Failed -> "Import couldn't continue" to
                    "Some items may already be on TMDB. Nothing is retried automatically; try again when storage and connection are available."
            }
            AccountDialog(title, "Guest library", { owner.laterImport(dialog) }, body) {
                when (prompt) {
                    is ImportPrompt.Ask -> {
                        PillButton(if (prompt.resume) "Resume" else "Import", { owner.importLibrary(dialog) })
                        if (!prompt.resume) PillButton("Keep separate", { owner.keepSeparate(dialog) }, variant = ButtonVariant.Ghost)
                        PillButton("Later", { owner.laterImport(dialog) }, variant = ButtonVariant.Ghost)
                    }
                    ImportPrompt.Running -> PillButton("Later", { owner.laterImport(dialog) }, variant = ButtonVariant.Ghost)
                    is ImportPrompt.Unconfirmed -> {
                        PillButton("Done", { owner.finishImport(dialog) })
                        PillButton("Retry", { owner.retryImport(dialog) }, variant = ButtonVariant.Ghost)
                    }
                    ImportPrompt.Failed -> {
                        PillButton("Try again", { owner.retryImport(dialog) })
                        PillButton("Later", { owner.laterImport(dialog) }, variant = ButtonVariant.Ghost)
                    }
                }
            }
        }
    }
}

private fun approvalCopy(phase: Phase) = when (phase) {
    Phase.Starting -> "Opening themoviedb.org…"
    Phase.Waiting -> "Approve CineVerse on themoviedb.org, then come back. Returning from the browser alone doesn't sign you in."
    Phase.Resume -> "A TMDB sign-in was started earlier. If you approved CineVerse, check again to finish signing in."
    Phase.Checking -> "Checking with TMDB…"
    Phase.StillWaiting -> "TMDB hasn't confirmed the approval yet. Approve CineVerse on themoviedb.org, then check again."
    Phase.Cancelled -> "The browser closed before sign-in finished. If you approved CineVerse, check again."
    Phase.CheckFailed -> "Couldn't check the sign-in. Check your connection, then check again."
    Phase.StartFailed -> "Couldn't start sign-in. Check your connection, then try again."
    Phase.Expired -> "This sign-in request expired."
    Phase.Rejected -> "TMDB didn't approve this sign-in."
    Phase.Failed -> "TMDB approved, but signing in couldn't finish. Nothing was saved."
    Phase.Unavailable -> "TMDB sign-in isn't available for this app right now."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountDialog(title: String, kicker: String, onDismiss: () -> Unit, body: String, actions: @Composable () -> Unit) {
    CvDialog(onDismissRequest = onDismiss, title = title, kicker = kicker) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(body, Modifier.padding(top = 10.dp), style = bodyStyle(15.5.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.8f))
            FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
        }
    }
}
