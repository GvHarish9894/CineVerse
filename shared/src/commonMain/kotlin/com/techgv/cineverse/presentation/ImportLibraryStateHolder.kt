package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.ImportOffer
import com.techgv.cineverse.data.library.ImportResult
import com.techgv.cineverse.data.library.ImportStep
import com.techgv.cineverse.data.library.LibraryImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The §8.3 import dialog to show; each shell renders its own copy. */
sealed interface ImportPrompt {
    /** [resume]: an earlier import did not finish; [items] titles are left. */
    data class Ask(val accountName: String, val items: Int, val resume: Boolean = false) : ImportPrompt
    data object Running : ImportPrompt
    /** Finished with unconfirmed steps left: "Couldn't confirm {n} items" + Retry (with the re-add warning). */
    data class Unconfirmed(val written: Int, val titles: List<String>) : ImportPrompt
    /** Reading the account's lists failed; nothing was written. */
    data object Failed : ImportPrompt
}

/**
 * §4.1 step 6 / §8.3: the import flow of the signed-in account. On sign-in (fresh or at launch) an account never asked,
 * with guest titles here, is offered the import once; an unfinished import is offered for resuming. [prompt] is held
 * back while the Player is up. A complete import ends in [onImported] (the shell's "imported" toast).
 */
class ImportLibraryStateHolder(
    private val importer: LibraryImporter,
    private val scope: CoroutineScope,
    private val onImported: () -> Unit,
) {
    private val dialog = MutableStateFlow<ImportPrompt?>(null)
    private val playerActive = MutableStateFlow(false)
    private var account: AuthSession? = null
    private var unconfirmed = emptySet<ImportStep>()
    private var job: Job? = null

    /** The dialog to display: null while the Player is up. */
    val prompt: Flow<ImportPrompt?> = combine(dialog, playerActive) { d, player -> d.takeUnless { player } }

    fun setPlayerActive(active: Boolean) {
        playerActive.value = active
    }

    /** The signed-in account changed: the previous account's import stops; offer or resume for [c]. */
    suspend fun onAccount(c: AuthSession?) {
        job?.cancel()
        dialog.value = null
        account = c
        c ?: return
        when (val offer = importer.offer(c)) {
            is ImportOffer.Ask -> dialog.value = ImportPrompt.Ask(c.displayName, offer.items)
            is ImportOffer.Resume -> dialog.value = ImportPrompt.Ask(c.displayName, offer.items, resume = true)
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
        if (dialog.value is ImportPrompt.Unconfirmed) scope.launch { importer.finish(c) }
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
            dialog.value = ImportPrompt.Running
            dialog.value = when (val result = importer.import(c, retry)) {
                is ImportResult.Aborted -> ImportPrompt.Failed
                ImportResult.Superseded -> null // signed out: the new account's flow owns the dialog
                is ImportResult.Done -> if (result.unconfirmed.isEmpty()) {
                    onImported()
                    null
                } else {
                    unconfirmed = result.unconfirmed.toSet()
                    ImportPrompt.Unconfirmed(result.written, result.unconfirmedTitles)
                }
            }
        }
    }
}
