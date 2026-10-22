package com.techgv.cineverse.presentation

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
class LibraryActionsStateHolder(
    private val repository: LibraryRepository,
    private val appScope: CoroutineScope,
    private val uiScope: CoroutineScope,
    private val onOutcome: (LibraryWriteOutcome) -> Unit,
) : LibraryActions {
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

    override fun state(ref: MediaRef) = repository.state(ref)

    override fun toggleWatchlist(title: Title) {
        val identity = identity
        appScope.launch { report(title, LibraryWrite.ToggleWatchlist, repository.toggleWatchlist(title, identity)) }
    }

    override fun toggleFavourite(title: Title) {
        val identity = identity
        appScope.launch { report(title, LibraryWrite.ToggleFavourite, repository.toggleFavourite(title, identity)) }
    }

    override fun openRate(title: Title) {
        val identity = identity
        uiScope.launch {
            val initial = repository.currentRating(identity, title.ref)
            // The sign-in may have changed while the rating was read.
            if (identity == this@LibraryActionsStateHolder.identity) prompt.value = RatePrompt(title, initial, identity)
        }
    }

    fun submitRating(value: Double) {
        val p = prompt.value ?: return
        prompt.value = null
        appScope.launch { report(p.title, LibraryWrite.Rate(value), repository.rate(p.title, value, p.identity)) }
    }

    fun removeRating() {
        val p = prompt.value ?: return
        prompt.value = null
        appScope.launch { report(p.title, LibraryWrite.Unrate, repository.unrate(p.title, p.identity)) }
    }

    fun closeRate() {
        prompt.value = null
    }

    private fun report(title: Title, write: LibraryWrite, result: LibraryWriteResult) {
        // The sign-in changed: a report would describe another identity's library.
        if (result != LibraryWriteResult.Superseded) onOutcome(LibraryWriteOutcome(title, write, result))
    }
}
