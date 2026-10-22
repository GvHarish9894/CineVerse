package com.techgv.cineverse.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.library.LibraryWriteResult
import com.techgv.cineverse.domain.LibraryActions
import com.techgv.cineverse.domain.LocalLibraryActions
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.presentation.LibraryActionsStateHolder
import com.techgv.cineverse.presentation.LibraryWrite
import com.techgv.cineverse.presentation.LibraryWriteOutcome
import com.techgv.cineverse.ui.components.LocalToaster
import com.techgv.cineverse.ui.components.RateDialog
import com.techgv.cineverse.ui.components.Toaster
import com.techgv.cineverse.ui.components.formatRating
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Provides [LocalLibraryActions] to [content]: writes run in [scope] (app-wide, so a write outlives the
 * screen), outcomes become toasts (§7.12), and [LibraryActions.openRate] shows the [RateDialog] — never over the
 * Player ([playerActive]: a rate request waits until it closes).
 */
@Composable
fun LibraryActionsHost(repository: LibraryRepository, scope: CoroutineScope, playerActive: Boolean, content: @Composable () -> Unit) {
    val toaster = LocalToaster.current
    val uiScope = rememberCoroutineScope()
    val controller = remember(repository, scope, toaster, uiScope) { LibraryActionsController(repository, toaster, scope, uiScope) }
    LaunchedEffect(controller) { repository.session.collect(controller::onIdentity) }
    SideEffect { controller.setPlayerActive(playerActive) }
    CompositionLocalProvider(LocalLibraryActions provides controller, content = content)

    val request by controller.shown.collectAsState(initial = null)
    // Checked here too: `shown` follows playerActive a frame late.
    request?.takeUnless { playerActive }?.let {
        RateDialog(
            titleName = it.title.title,
            initial = it.initial,
            onSubmit = controller::submitRating,
            onRemove = controller::removeRating,
            onCancel = controller::closeRate,
        )
    }
}

/** A Rate dialog for [title], bound to the [identity] it was opened under. */
class RateRequest(val title: Title, val initial: Double?, val identity: AuthSession?)

/**
 * TV adapter over [LibraryActionsStateHolder]: [LibraryActions] bound to the identity signed in at click time
 * (a superseded write reports nothing), outcomes as toasts (§7.12) and the Rate dialog as a [RateRequest].
 */
class LibraryActionsController(
    repository: LibraryRepository,
    private val toaster: Toaster,
    appScope: CoroutineScope,
    uiScope: CoroutineScope,
) : LibraryActions {
    private val holder = LibraryActionsStateHolder(repository, appScope, uiScope) { toaster.report(it) }

    /** The Rate dialog to display: null while the Player is up (the request waits). */
    val shown: Flow<RateRequest?> = holder.ratePrompt.map { p -> p?.let { RateRequest(it.title, it.initial, it.identity) } }

    /** The collected sign-in changed: a dialog of another identity closes without writing. */
    fun onIdentity(c: AuthSession?) = holder.onIdentity(c)

    fun setPlayerActive(active: Boolean) = holder.setPlayerActive(active)

    override fun state(ref: MediaRef) = holder.state(ref)

    override fun toggleWatchlist(title: Title) = holder.toggleWatchlist(title)

    override fun toggleFavourite(title: Title) = holder.toggleFavourite(title)

    override fun openRate(title: Title) = holder.openRate(title)

    fun submitRating(value: Double) = holder.submitRating(value)

    fun removeRating() = holder.removeRating()

    fun closeRate() = holder.closeRate()
}

private fun Toaster.report(outcome: LibraryWriteOutcome) {
    val title = outcome.title
    when (val result = outcome.result) {
        is LibraryWriteResult.Done -> show(
            when (val write = outcome.write) {
                LibraryWrite.ToggleWatchlist -> if (result.state.watchlist) "Added “${title.title}” to Watchlist" else "Removed from Watchlist"
                LibraryWrite.ToggleFavourite -> if (result.state.favourite) "Added “${title.title}” to Favourites" else "Removed from Favourites"
                is LibraryWrite.Rate -> "Rated ${title.title} ${formatRating(write.value)} / 10"
                LibraryWrite.Unrate -> "Rating removed"
            },
            when {
                result.removedFromWatchlist -> "Synced · TMDB removed it from your watchlist"
                result.synced -> "Synced to your TMDB account"
                else -> "Saved on this TV · sign in to sync"
            },
        )
        is LibraryWriteResult.Failed -> show("Couldn't sync — try again", title.title)
        LibraryWriteResult.Superseded -> Unit // never reported by the holder
    }
}
