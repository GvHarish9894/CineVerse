package com.techgv.cineverse.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.LibraryImporter
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.presentation.ImportLibraryStateHolder
import com.techgv.cineverse.presentation.ImportPrompt
import com.techgv.cineverse.ui.components.ImportDialogState
import com.techgv.cineverse.ui.components.ImportLibraryDialog
import com.techgv.cineverse.ui.components.LocalToaster
import com.techgv.cineverse.ui.components.Toaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * §4.1 step 6 / §8.3: when an account is signed in (fresh or at launch) and it was never asked, and this TV
 * has guest titles, offer the import once; an unfinished "Import" is offered for resuming once per launch. Deferred while
 * [playerActive]: the dialog shows once the Player closes.
 */
@Composable
fun ImportLibraryHost(repository: LibraryRepository, importer: LibraryImporter, playerActive: Boolean) {
    val session by repository.session.collectAsStateWithLifecycle(initialValue = null)
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val controller = remember(importer, toaster, scope) { ImportLibraryController(importer, toaster, scope) }
    LaunchedEffect(controller, session) { controller.onAccount(session) }
    SideEffect { controller.setPlayerActive(playerActive) }
    val state by controller.shown.collectAsState(initial = null)
    // Checked here too: `shown` follows playerActive a frame late.
    state?.takeUnless { playerActive }?.let {
        ImportLibraryDialog(
            state = it,
            onKeepSeparate = controller::keepSeparate,
            onImport = controller::import,
            onRetry = controller::retry,
            onClose = controller::close,
            onLater = controller::later,
        )
    }
}

/** TV adapter over [ImportLibraryStateHolder]: the prompt as an [ImportDialogState], the "imported" toast. */
class ImportLibraryController(importer: LibraryImporter, toaster: Toaster, scope: CoroutineScope) {
    private val holder = ImportLibraryStateHolder(importer, scope) { toaster.show("Guest library imported", "Synced to your TMDB account") }

    /** The dialog to display (null while the Player is up). */
    val shown: Flow<ImportDialogState?> = holder.prompt.map { it?.toDialogState() }

    fun setPlayerActive(active: Boolean) = holder.setPlayerActive(active)

    /** The signed-in account changed: the previous account's import stops; offer or resume for [c]. */
    suspend fun onAccount(c: AuthSession?) = holder.onAccount(c)

    fun keepSeparate() = holder.keepSeparate()

    fun import() = holder.import()

    fun retry() = holder.retry()

    fun close() = holder.close()

    /** Running / resume prompt → Later: stops the import; the ledger stays, so resuming reconciles. */
    fun later() = holder.later()
}

private fun ImportPrompt.toDialogState(): ImportDialogState = when (this) {
    is ImportPrompt.Ask -> ImportDialogState.Ask(accountName, items, resume)
    ImportPrompt.Running -> ImportDialogState.Running
    is ImportPrompt.Unconfirmed -> ImportDialogState.Unconfirmed(written, titles)
    ImportPrompt.Failed -> ImportDialogState.Failed
}
