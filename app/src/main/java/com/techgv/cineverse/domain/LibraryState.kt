package com.techgv.cineverse.domain

enum class LibraryKind { WATCHLIST, FAVOURITE, RATING }

/**
 * Library buttons of one title for the current identity. [known] = false: signed-in `account_states` not
 * loaded (or failed) — buttons are disabled, never shown as "off". [pending] kinds have a write in flight.
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
