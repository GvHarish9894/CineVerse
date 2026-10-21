package com.techgv.cineverse.domain

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow

/** The three library mutations a title can be in (§7.4, §7.8); [pending] holds those mid-write. */
enum class LibraryKind { Watchlist, Favourite, Rating }

/**
 * A title's library membership. [known] is false until the first read lands ([Unknown]); UI then shows a
 * neutral, still-tappable button. [pending] names the kinds whose write is in flight (button disabled).
 */
data class LibraryState(
    val known: Boolean,
    val watchlist: Boolean,
    val favourite: Boolean,
    val rating: Double?,
    val pending: Set<LibraryKind>,
) {
    companion object {
        val Unknown = LibraryState(known = false, watchlist = false, favourite = false, rating = null, pending = emptySet())
    }
}

/**
 * Cross-lane seam the Library lane (Lane 4) implements and provides through [LocalLibraryActions];
 * Home/Detail/cards consume it. A null provider means the actions aren't available yet → buttons disabled.
 */
interface LibraryActions {
    fun state(ref: MediaRef): Flow<LibraryState>
    fun toggleWatchlist(title: Title)
    fun toggleFavourite(title: Title)
    fun openRate(title: Title)
}

/** Null → library buttons render disabled (§6: focus stays visible). */
val LocalLibraryActions = staticCompositionLocalOf<LibraryActions?> { null }
