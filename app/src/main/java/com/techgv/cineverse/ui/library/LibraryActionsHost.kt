package com.techgv.cineverse.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.library.LibraryWriteResult
import com.techgv.cineverse.domain.LibraryActions
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.LocalLibraryActions
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.ui.components.LocalToaster
import com.techgv.cineverse.ui.components.RateDialog
import com.techgv.cineverse.ui.components.Toaster
import com.techgv.cineverse.ui.components.formatRating
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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
    LaunchedEffect(controller) { repository.credentials.collect(controller::onIdentity) }
    SideEffect { controller.setPlayerActive(playerActive) }
    CompositionLocalProvider(LocalLibraryActions provides controller, content = content)

    val request by controller.shown.collectAsState(initial = null)
    request?.let {
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
class RateRequest(val title: Title, val initial: Double?, val identity: Credentials?)

/**
 * [LibraryActions] bound to the identity signed in at click time: each action captures [identity] synchronously
 * and the repository rejects it (Superseded, no toast) if that sign-in is gone, so it never retargets.
 */
class LibraryActionsController(
    private val repository: LibraryRepository,
    private val toaster: Toaster,
    private val appScope: CoroutineScope,
    private val uiScope: CoroutineScope,
) : LibraryActions {
    private var identity: Credentials? = null
    private val request = MutableStateFlow<RateRequest?>(null)
    private val playerActive = MutableStateFlow(false)

    /** The Rate dialog to display: null while the Player is up (the request waits). */
    val shown: Flow<RateRequest?> = combine(request, playerActive) { r, player -> r.takeUnless { player } }

    /** The collected sign-in changed: a dialog of another identity closes without writing. */
    fun onIdentity(c: Credentials?) {
        identity = c
        if (request.value?.identity != c) request.value = null
    }

    fun setPlayerActive(active: Boolean) {
        playerActive.value = active
    }

    override fun state(ref: MediaRef) = repository.state(ref)

    override fun toggleWatchlist(title: Title) {
        val identity = identity
        appScope.launch {
            val result = repository.toggleWatchlist(title, identity)
            toaster.report(title, result) { if (it.watchlist) "Added “${title.title}” to Watchlist" else "Removed from Watchlist" }
        }
    }

    override fun toggleFavourite(title: Title) {
        val identity = identity
        appScope.launch {
            val result = repository.toggleFavourite(title, identity)
            toaster.report(title, result) { if (it.favourite) "Added “${title.title}” to Favourites" else "Removed from Favourites" }
        }
    }

    override fun openRate(title: Title) {
        val identity = identity
        uiScope.launch {
            val initial = repository.currentRating(identity, title.ref)
            // The sign-in may have changed while the rating was read.
            if (identity == this@LibraryActionsController.identity) request.value = RateRequest(title, initial, identity)
        }
    }

    fun submitRating(value: Double) {
        val r = request.value ?: return
        request.value = null
        appScope.launch {
            val result = repository.rate(r.title, value, r.identity)
            toaster.report(r.title, result) { "Rated ${r.title.title} ${formatRating(value)} / 10" }
        }
    }

    fun removeRating() {
        val r = request.value ?: return
        request.value = null
        appScope.launch { toaster.report(r.title, repository.unrate(r.title, r.identity)) { "Rating removed" } }
    }

    fun closeRate() {
        request.value = null
    }
}

private fun Toaster.report(title: Title, result: LibraryWriteResult, message: (LibraryState) -> String) {
    when (result) {
        is LibraryWriteResult.Done -> show(
            message(result.state),
            when {
                result.removedFromWatchlist -> "Synced · TMDB removed it from your watchlist"
                result.synced -> "Synced to your TMDB account"
                else -> "Saved on this TV · sign in to sync"
            },
        )
        is LibraryWriteResult.Failed -> show("Couldn't sync — try again", title.title)
        LibraryWriteResult.Superseded -> Unit // the sign-in changed: a toast would describe another library
    }
}
