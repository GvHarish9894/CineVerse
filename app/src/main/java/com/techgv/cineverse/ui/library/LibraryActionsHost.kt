package com.techgv.cineverse.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Provides [LocalLibraryActions] to [content]: writes run in [scope] (app-wide, so a write outlives the
 * screen), outcomes become toasts (§7.12), and [LibraryActions.openRate] shows the [RateDialog].
 */
@Composable
fun LibraryActionsHost(repository: LibraryRepository, scope: CoroutineScope, content: @Composable () -> Unit) {
    val toaster = LocalToaster.current
    var rating by remember { mutableStateOf<RateRequest?>(null) }
    val uiScope = rememberCoroutineScope()
    val actions = remember(repository, scope, toaster) {
        object : LibraryActions {
            override fun state(ref: MediaRef) = repository.state(ref)

            override fun toggleWatchlist(title: Title) {
                scope.launch {
                    val result = repository.toggleWatchlist(title)
                    toaster.report(title, result) { if (it.watchlist) "Added “${title.title}” to Watchlist" else "Removed from Watchlist" }
                }
            }

            override fun toggleFavourite(title: Title) {
                scope.launch {
                    val result = repository.toggleFavourite(title)
                    toaster.report(title, result) { if (it.favourite) "Added “${title.title}” to Favourites" else "Removed from Favourites" }
                }
            }

            override fun openRate(title: Title) {
                uiScope.launch {
                    val identity = repository.credentials.first()
                    rating = RateRequest(title, repository.currentRating(identity, title.ref), identity)
                }
            }
        }
    }
    CompositionLocalProvider(LocalLibraryActions provides actions, content = content)

    // The dialog belongs to the identity it was opened under: a sign-in change closes it without writing.
    LaunchedEffect(repository) {
        repository.credentials.collect { c -> if (rating != null && rating?.identity != c) rating = null }
    }

    rating?.let { request ->
        val title = request.title
        RateDialog(
            titleName = title.title,
            initial = request.initial,
            onSubmit = { value ->
                rating = null
                scope.launch {
                    val result = repository.rate(title, value, request.identity)
                    toaster.report(title, result) { "Rated ${title.title} ${formatRating(value)} / 10" }
                }
            },
            onRemove = {
                rating = null
                scope.launch { toaster.report(title, repository.unrate(title, request.identity)) { "Rating removed" } }
            },
            onCancel = { rating = null },
        )
    }
}

private class RateRequest(val title: Title, val initial: Double?, val identity: Credentials?)

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
