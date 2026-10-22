package com.techgv.cineverse.domain

import kotlinx.coroutines.flow.Flow

/** Library buttons of any screen; each UI module provides it through its own CompositionLocal. */
interface LibraryActions {
    fun state(ref: MediaRef): Flow<LibraryState>
    fun toggleWatchlist(title: Title)
    fun toggleFavourite(title: Title)
    fun openRate(title: Title)
}
