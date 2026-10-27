package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.storage.StorageException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
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
 * [LibraryActions] defaults capture [onIdentity]'s value synchronously at invocation; explicit overloads keep
 * the caller's captured identity unchanged. The repository rejects superseded writes without retargeting.
 * Writes run in [appScope] (they outlive the screen) and end in [onOutcome]; the Rate dialog is
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
    // Include the request epoch so slow collectors see value-equal replacement instances.
    private data class RatePromptState(val request: Long, val prompt: RatePrompt?)
    private val prompt = MutableStateFlow(RatePromptState(0L, null))
    private var rateRequest = 0L
    private val playerActive = MutableStateFlow(false)

    /** The Rate dialog to display: null while the Player is up (the prompt waits). */
    val ratePrompt: Flow<RatePrompt?> = combine(prompt, playerActive) { p, player -> p.prompt.takeUnless { player } }

    /** The collected sign-in changed: a dialog of another identity closes without writing. */
    fun onIdentity(c: AuthSession?) {
        if (identity == c) return
        identity = c
        closeRate()
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

    override fun toggleWatchlist(title: Title) = toggleWatchlist(title, identity)

    /** [expected] is captured intent (null means guest), not proof of an authoritative browser session. */
    fun toggleWatchlist(title: Title, expected: AuthSession?) {
        write(title, LibraryWrite.ToggleWatchlist, expected) { repository.toggleWatchlist(title, expected) }
    }

    override fun toggleFavourite(title: Title) = toggleFavourite(title, identity)

    /** [expected] is passed unchanged to the repository's session and per-title lock checks. */
    fun toggleFavourite(title: Title, expected: AuthSession?) {
        write(title, LibraryWrite.ToggleFavourite, expected) { repository.toggleFavourite(title, expected) }
    }

    override fun openRate(title: Title) = openRate(title, identity)

    /** Opens only for [expected] while both the repository session and collected identity still match. */
    fun openRate(title: Title, expected: AuthSession?) {
        val request = ++rateRequest
        uiScope.launch {
            try {
                if (repository.session.first() != expected || request != rateRequest || expected != identity) return@launch
                val initial = repository.currentRating(expected, title.ref)
                if (repository.session.first() == expected && request == rateRequest && expected == identity) {
                    prompt.value = RatePromptState(request, RatePrompt(title, initial, expected))
                }
            } catch (failure: StorageException) {
                // As before: a real storage failure is reported unless the sign-in changed (a closed dialog still reports).
                if (expected == identity) onStorageFailure(failure)
            }
        }
    }

    fun submitRating(value: Double) {
        val p = prompt.value.prompt ?: return
        submitRating(value, p)
    }

    /** Only the exact owned prompt may dispatch; a value-equal reopened prompt is a different intent. */
    fun submitRating(value: Double, expected: RatePrompt) {
        if (prompt.value.prompt !== expected) return
        closeRate()
        write(expected.title, LibraryWrite.Rate(value), expected.identity) {
            repository.rate(expected.title, value, expected.identity)
        }
    }

    fun removeRating() {
        val p = prompt.value.prompt ?: return
        removeRating(p)
    }

    /** A stale callback must neither remove a rating nor close a replacement prompt. */
    fun removeRating(expected: RatePrompt) {
        if (prompt.value.prompt !== expected) return
        closeRate()
        write(expected.title, LibraryWrite.Unrate, expected.identity) { repository.unrate(expected.title, expected.identity) }
    }

    /** A delayed dismiss may close only the exact prompt that supplied its callback. */
    fun closeRate(expected: RatePrompt) {
        if (prompt.value.prompt !== expected) return
        closeRate()
    }

    fun closeRate() {
        rateRequest++
        prompt.value = RatePromptState(rateRequest, null)
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
