package com.techgv.cineverse.domain

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow

interface LibraryActions {
    fun state(ref: MediaRef): Flow<LibraryState>
    fun toggleWatchlist(title: Title)
    fun toggleFavourite(title: Title)
    fun openRate(title: Title)
}

val LocalLibraryActions = staticCompositionLocalOf<LibraryActions?> { null }
