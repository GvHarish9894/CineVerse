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
import com.techgv.cineverse.presentation.ImportPrompt
import com.techgv.cineverse.ui.components.ButtonVariant
import com.techgv.cineverse.ui.components.CvDialog
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.design.Tokens
import com.techgv.cineverse.ui.theme.bodyStyle

/**
 * §8.3 guest-library import, hosted once above routes. Escape/scrim/Close is Later: never the durable Keep separate
 * or Done. Hidden while the sign-out dialog is open so only one modal owns focus.
 */
@Composable
fun WebImportDialog(owner: WebAccountOwner, restoreFocus: FocusRequester, onRestoreFocus: (() -> Unit)? = null) {
    val state by owner.state.collectAsState()
    if (state.signOut != WebSignOutPhase.Closed) return
    val prompt = state.importPrompt
    val notice = state.importNotice
    if (prompt == null && notice == null) return
    fun plural(n: Int, one: String) = "$n $one${if (n == 1) "" else "s"}"
    val (title, body) = when (prompt) {
        is ImportPrompt.Ask -> if (prompt.resume) "Resume importing your guest library?" to
            "${plural(prompt.items, "title")} from this browser's guest library are left to import into ${prompt.accountName}'s TMDB account."
        else "Import your guest library?" to
            "${plural(prompt.items, "title")} from this browser's guest library will be added to ${prompt.accountName}'s TMDB " +
            "watchlist, favourites and ratings. Ratings already on TMDB are kept. The import can't be undone automatically."
        ImportPrompt.Running -> "Importing your guest library…" to
            "Later stops it; anything already imported stays on TMDB."
        is ImportPrompt.Unconfirmed -> "Couldn't confirm ${plural(prompt.titles.size, "item")}" to
            "${prompt.written} imported. TMDB didn't confirm these. Retrying may re-add items you removed on TMDB since."
        ImportPrompt.Failed -> "Import couldn't continue" to
            "Some items may already be on TMDB. Nothing is retried automatically; try again when storage and connection are available."
        null -> when (notice) {
            WebImportNotice.Imported -> "Guest library imported" to "Your guest titles are now on your TMDB account."
            WebImportNotice.NothingToImport -> "Nothing to import" to "There is no guest library import to offer for this account right now."
            else -> "Account changed" to "The signed-in account changed, so nothing was imported. Check the account shown before trying again."
        }
    }
    CvDialog(
        onDismissRequest = owner::dismiss,
        title = title,
        kicker = "GUEST LIBRARY",
        restoreFocus = restoreFocus,
        onRestoreFocus = onRestoreFocus,
        modifier = Modifier.testTag("web-import-dialog"),
    ) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("web-import-content"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (prompt is ImportPrompt.Unconfirmed) {
                Text(prompt.titles.joinToString("\n"), style = bodyStyle(15.sp), color = Tokens.Colors.TextPrimary)
            }
            Text(body, style = bodyStyle(15.5.sp), color = Tokens.Colors.TextPrimary.copy(alpha = 0.8f))
            val enabled = !state.importBusy
            FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (prompt) {
                    is ImportPrompt.Ask -> {
                        PillButton(if (prompt.resume) "Resume" else "Import", owner::importLibrary, enabled = enabled)
                        if (!prompt.resume) PillButton("Keep separate", owner::keepSeparate, variant = ButtonVariant.Ghost, enabled = enabled)
                        PillButton("Later", owner::later, variant = ButtonVariant.Ghost)
                    }
                    ImportPrompt.Running -> PillButton("Later", owner::later, variant = ButtonVariant.Ghost)
                    is ImportPrompt.Unconfirmed -> {
                        PillButton("Done", owner::finishImport, enabled = enabled)
                        PillButton("Retry", owner::retryImport, variant = ButtonVariant.Ghost, enabled = enabled)
                    }
                    ImportPrompt.Failed -> {
                        PillButton("Try again", owner::retryImport, enabled = enabled)
                        PillButton("Later", owner::later, variant = ButtonVariant.Ghost)
                    }
                    null -> PillButton("Close", owner::dismiss, variant = ButtonVariant.Ghost)
                }
            }
        }
    }
}
