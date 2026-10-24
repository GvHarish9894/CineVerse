package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.storage.StorageException
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
    /** Import/preflight or local bookkeeping failed; storage diagnostics describe recovery without claiming no writes. */
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
    private val onStorageFailure: (StorageException) -> Unit,
) {
    constructor(importer: LibraryImporter, scope: CoroutineScope, onImported: () -> Unit) :
        this(importer, scope, onImported, {})
    private val dialog = MutableStateFlow<ImportPrompt?>(null)
    private val playerActive = MutableStateFlow(false)
    private var account: AuthSession? = null
    private var unconfirmed = emptySet<ImportStep>()
    private var job: Job? = null
    private var revision = 0L
    private enum class Action { Offer, KeepSeparate, Finish, Import }
    private var failed: Pair<Action, Set<ImportStep>>? = null

    /** The dialog to display: null while the Player is up. */
    val prompt: Flow<ImportPrompt?> = combine(dialog, playerActive) { d, player -> d.takeUnless { player } }

    fun setPlayerActive(active: Boolean) {
        playerActive.value = active
    }

    /** The signed-in account changed: the previous account's import stops; offer or resume for [c]. */
    suspend fun onAccount(c: AuthSession?) {
        revision++
        job?.cancel()
        failed = null
        unconfirmed = emptySet()
        dialog.value = null
        account = c
        if (c != null) perform(c, revision, Action.Offer, emptySet())
    }

    fun keepSeparate() = launch(Action.KeepSeparate)

    fun import() = launch(Action.Import)

    fun retry() {
        val failure = failed
        if (failure != null) launch(failure.first, failure.second) else launch(Action.Import, unconfirmed)
    }

    /** Retries exactly the failed local operation; a failed offer/finish never starts account POSTs. */
    fun retryStorage() { if (failed != null) retry() }

    fun close() {
        if (dialog.value is ImportPrompt.Unconfirmed) launch(Action.Finish) else dialog.value = null
    }

    /** Stops work without clearing the durable attempting ledger. */
    fun later() {
        revision++
        job?.cancel()
        failed = null
        dialog.value = null
    }

    private fun launch(action: Action, retry: Set<ImportStep> = emptySet()) {
        val c = account ?: return
        job?.cancel()
        val ticket = ++revision
        job = scope.launch { perform(c, ticket, action, retry) }
    }

    private suspend fun perform(c: AuthSession, ticket: Long, action: Action, retry: Set<ImportStep>) {
        fun current() = account == c && revision == ticket
        if (!current()) return
        failed = null
        try {
            when (action) {
                Action.Offer -> {
                    val offer = importer.offer(c)
                    if (!current()) return
                    dialog.value = when (offer) {
                        is ImportOffer.Ask -> ImportPrompt.Ask(c.displayName, offer.items)
                        is ImportOffer.Resume -> ImportPrompt.Ask(c.displayName, offer.items, resume = true)
                        null -> null
                    }
                }
                Action.KeepSeparate, Action.Finish -> {
                    if (action == Action.KeepSeparate) importer.keepSeparate(c) else importer.finish(c)
                    if (current()) dialog.value = null
                }
                Action.Import -> {
                    dialog.value = ImportPrompt.Running
                    val result = importer.import(c, retry)
                    if (!current()) return
                    dialog.value = when (result) {
                        is ImportResult.Aborted -> ImportPrompt.Failed
                        ImportResult.Superseded -> null
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
        } catch (failure: StorageException) {
            if (current()) {
                failed = action to retry
                dialog.value = ImportPrompt.Failed
                onStorageFailure(failure)
            }
        }
    }
}
