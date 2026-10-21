package com.techgv.cineverse.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.library.ImportOffer
import com.techgv.cineverse.data.library.ImportResult
import com.techgv.cineverse.data.library.ImportStep
import com.techgv.cineverse.data.library.LibraryImporter
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.ui.components.ImportDialogState
import com.techgv.cineverse.ui.components.ImportLibraryDialog
import com.techgv.cineverse.ui.components.LocalToaster
import com.techgv.cineverse.ui.components.Toaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * §4.1 step 6 / §8.3: when an account is signed in (fresh or at launch) and it was never asked, and this TV
 * has guest titles, offer the import once; an unfinished "Import" is offered for resuming once per launch. Deferred while
 * [playerActive]: the dialog shows once the Player closes.
 */
@Composable
fun ImportLibraryHost(repository: LibraryRepository, importer: LibraryImporter, playerActive: Boolean) {
    val credentials by repository.credentials.collectAsStateWithLifecycle(initialValue = null)
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val controller = remember(importer, toaster, scope) { ImportLibraryController(importer, toaster, scope) }
    LaunchedEffect(controller, credentials) { controller.onAccount(credentials) }
    SideEffect { controller.setPlayerActive(playerActive) }
    val state by controller.shown.collectAsState(initial = null)
    state?.let {
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

/** The import flow of the signed-in account; [shown] is the dialog to display (null while the Player is up). */
class ImportLibraryController(private val importer: LibraryImporter, private val toaster: Toaster, private val scope: CoroutineScope) {
    private val dialog = MutableStateFlow<ImportDialogState?>(null)
    private val playerActive = MutableStateFlow(false)
    private var account: Credentials? = null
    private var unconfirmed = emptySet<ImportStep>()
    private var job: Job? = null

    val shown: Flow<ImportDialogState?> = combine(dialog, playerActive) { d, player -> d.takeUnless { player } }

    fun setPlayerActive(active: Boolean) {
        playerActive.value = active
    }

    /** The signed-in account changed: the previous account's import stops; offer or resume for [c]. */
    suspend fun onAccount(c: Credentials?) {
        job?.cancel()
        dialog.value = null
        account = c
        c ?: return
        when (val offer = importer.offer(c)) {
            is ImportOffer.Ask -> dialog.value = ImportDialogState.Ask(c.displayName, offer.items)
            is ImportOffer.Resume -> dialog.value = ImportDialogState.Ask(c.displayName, offer.items, resume = true)
            null -> Unit
        }
    }

    fun keepSeparate() {
        val c = account ?: return
        dialog.value = null
        scope.launch { importer.keepSeparate(c) }
    }

    fun import() = run(emptySet())

    fun retry() = run(unconfirmed)

    fun close() {
        val c = account ?: return
        if (dialog.value is ImportDialogState.Unconfirmed) scope.launch { importer.finish(c) }
        dialog.value = null
    }

    /** Running / resume prompt → Later: stops the import. The ledger (incl. `attempting` steps) stays, so resuming reconciles. */
    fun later() {
        job?.cancel()
        dialog.value = null
    }

    private fun run(retry: Set<ImportStep>) {
        val c = account ?: return
        job?.cancel()
        job = scope.launch {
            dialog.value = ImportDialogState.Running
            dialog.value = when (val result = importer.import(c, retry)) {
                is ImportResult.Aborted -> ImportDialogState.Failed
                ImportResult.Superseded -> null // signed out: the new account's flow owns the dialog
                is ImportResult.Done -> if (result.unconfirmed.isEmpty()) {
                    toaster.show("Guest library imported", "Synced to your TMDB account")
                    null
                } else {
                    unconfirmed = result.unconfirmed.toSet()
                    ImportDialogState.Unconfirmed(result.written, result.unconfirmedTitles)
                }
            }
        }
    }
}
