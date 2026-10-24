package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.storage.StorageException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.library.LibraryWriteResult
import com.techgv.cineverse.domain.LibraryActions
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.Title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** A library write the user made. */
sealed interface LibraryWrite {
    data object ToggleWatchlist : LibraryWrite
    data object ToggleFavourite : LibraryWrite
    data class Rate(val value: Double) : LibraryWrite
    data object Unrate : LibraryWrite
}

/** A finished [write] of [title] for the shell to report (§7.12 toast). Never sent for a superseded write. */
data class LibraryWriteOutcome(val title: Title, val write: LibraryWrite, val result: LibraryWriteResult)

/** A Rate dialog for [title] (default [initial]), bound to the [identity] it was opened under. */
data class RatePrompt(val title: Title, val initial: Double?, val identity: AuthSession?)

/**
 * [LibraryActions] bound to the identity signed in at click time: each action captures [onIdentity]'s value
 * synchronously and the repository rejects it (Superseded, nothing reported) if that sign-in is gone, so it never
 * retargets. Writes run in [appScope] (they outlive the screen) and end in [onOutcome]; the Rate dialog is
 * [ratePrompt], held back while the Player is up ([setPlayerActive]) and dropped when the identity changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryActionsStateHolder(
    private val repository: LibraryRepository,
    private val appScope: CoroutineScope,
    private val uiScope: CoroutineScope,
    private val onOutcome: (LibraryWriteOutcome) -> Unit,
    private val onStorageFailure: (StorageException) -> Unit,
) : LibraryActions {
    constructor(repository: LibraryRepository, appScope: CoroutineScope, uiScope: CoroutineScope,
                onOutcome: (LibraryWriteOutcome) -> Unit) : this(repository, appScope, uiScope, onOutcome, {})
    private val reads = MutableStateFlow(0L)
    fun retryStorage() { reads.update { it + 1 } }
    private var identity: AuthSession? = null
    private val prompt = MutableStateFlow<RatePrompt?>(null)
    private val playerActive = MutableStateFlow(false)

    /** The Rate dialog to display: null while the Player is up (the prompt waits). */
    val ratePrompt: Flow<RatePrompt?> = combine(prompt, playerActive) { p, player -> p.takeUnless { player } }

    /** The collected sign-in changed: a dialog of another identity closes without writing. */
    fun onIdentity(c: AuthSession?) {
        identity = c
        if (prompt.value?.identity != c) prompt.value = null
    }

    fun setPlayerActive(active: Boolean) {
        playerActive.value = active
    }

    override fun state(ref: MediaRef) = reads.flatMapLatest {
        repository.state(ref).catch { failure ->
            if (failure !is StorageException) throw failure
            onStorageFailure(failure)
            emit(com.techgv.cineverse.domain.LibraryState.Unknown)
        }
    }

    override fun toggleWatchlist(title: Title) {
        val identity = identity
        write(title, LibraryWrite.ToggleWatchlist, identity) { repository.toggleWatchlist(title, identity) }
    }

    override fun toggleFavourite(title: Title) {
        val identity = identity
        write(title, LibraryWrite.ToggleFavourite, identity) { repository.toggleFavourite(title, identity) }
    }

    override fun openRate(title: Title) {
        val identity = identity
        uiScope.launch {
            val initial = try { repository.currentRating(identity, title.ref) }
            catch (failure: StorageException) {
                if (identity == this@LibraryActionsStateHolder.identity) onStorageFailure(failure)
                return@launch
            }
            // The sign-in may have changed while the rating was read.
            if (identity == this@LibraryActionsStateHolder.identity) prompt.value = RatePrompt(title, initial, identity)
        }
    }

    fun submitRating(value: Double) {
        val p = prompt.value ?: return
        prompt.value = null
        write(p.title, LibraryWrite.Rate(value), p.identity) { repository.rate(p.title, value, p.identity) }
    }

    fun removeRating() {
        val p = prompt.value ?: return
        prompt.value = null
        write(p.title, LibraryWrite.Unrate, p.identity) { repository.unrate(p.title, p.identity) }
    }

    fun closeRate() {
        prompt.value = null
    }

    private fun write(title: Title, write: LibraryWrite, captured: AuthSession?, operation: suspend () -> LibraryWriteResult) {
        appScope.launch {
            try { report(title, write, operation()) }
            catch (failure: StorageException) {
                if (identity == captured) {
                    onStorageFailure(failure)
                    report(title, write, LibraryWriteResult.Failed(null))
                }
            }
        }
    }

    private fun report(title: Title, write: LibraryWrite, result: LibraryWriteResult) {
        // The sign-in changed: a report would describe another identity's library.
        if (result != LibraryWriteResult.Superseded) onOutcome(LibraryWriteOutcome(title, write, result))
    }
}
