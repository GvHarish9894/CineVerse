package com.techgv.cineverse.ui.mobile

import com.techgv.cineverse.data.library.LibraryWriteResult
import com.techgv.cineverse.data.tmdb.MovieDetailDto
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.presentation.*
import kotlin.test.*

class MobileLibraryOutcomeCopyTest {
    private val title = MovieDetailDto(101, "Outcome title").toDetail("US").toTitle()
    private val state = LibraryState(true, false, false, null, emptySet())
    private fun copy(write: LibraryWrite, result: LibraryWriteResult) = mobileLibraryOutcomeMessage(LibraryWriteOutcome(title, write, result))

    @Test fun watchlistAndFavouriteCopyUsesActualResultAndLocalOrSyncedDestination() {
        for (synced in listOf(false, true)) {
            val where = if (synced) "Synced" else "Saved locally"
            assertEquals("Added to watchlist · $where", copy(LibraryWrite.ToggleWatchlist, LibraryWriteResult.Done(state.copy(watchlist = true), synced)))
            assertEquals("Removed from watchlist · $where", copy(LibraryWrite.ToggleWatchlist, LibraryWriteResult.Done(state, synced)))
            assertEquals("Added to favourites · $where", copy(LibraryWrite.ToggleFavourite, LibraryWriteResult.Done(state.copy(favourite = true), synced)))
            assertEquals("Removed from favourites · $where", copy(LibraryWrite.ToggleFavourite, LibraryWriteResult.Done(state, synced)))
        }
    }

    @Test fun rateAndUnrateCopyUsesActualCompletionAndObservedWatchlistRemoval() {
        for (synced in listOf(false, true)) {
            val where = if (synced) "Synced" else "Saved locally"
            assertEquals("Rating saved · $where", copy(LibraryWrite.Rate(7.5), LibraryWriteResult.Done(state.copy(rating = 7.5), synced)))
            assertEquals("Rating saved · $where · Removed from watchlist", copy(LibraryWrite.Rate(7.5), LibraryWriteResult.Done(state.copy(rating = 7.5), synced, removedFromWatchlist = true)))
            assertEquals("Rating removed · $where", copy(LibraryWrite.Unrate, LibraryWriteResult.Done(state, synced)))
        }
    }

    @Test fun failuresNameEveryOperationWithoutClaimingSuccessOrRetry() {
        val failed = LibraryWriteResult.Failed(null)
        assertEquals("Couldn't update watchlist. Not retried.", copy(LibraryWrite.ToggleWatchlist, failed))
        assertEquals("Couldn't update favourites. Not retried.", copy(LibraryWrite.ToggleFavourite, failed))
        assertEquals("Couldn't save rating. Not retried.", copy(LibraryWrite.Rate(7.5), failed))
        assertEquals("Couldn't remove rating. Not retried.", copy(LibraryWrite.Unrate, failed))
    }

    @Test fun supersededOrUnknownCompletionsNeverClaimSuccess() {
        for (write in listOf(LibraryWrite.ToggleWatchlist, LibraryWrite.ToggleFavourite, LibraryWrite.Rate(7.5), LibraryWrite.Unrate)) {
            assertNull(copy(write, LibraryWriteResult.Superseded))
            assertNull(copy(write, LibraryWriteResult.Done(LibraryState.Unknown, synced = false)))
        }
        assertNull(copy(LibraryWrite.Rate(7.5), LibraryWriteResult.Done(state, synced = true)))
        assertNull(copy(LibraryWrite.Unrate, LibraryWriteResult.Done(state.copy(rating = 7.5), synced = true)))
    }
}
