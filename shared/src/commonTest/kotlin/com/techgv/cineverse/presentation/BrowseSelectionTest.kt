package com.techgv.cineverse.presentation

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.MediaType
import kotlin.test.*

class BrowseSelectionTest {
    @Test fun everySourceRoundTripsThroughOnlySavedScalars() {
        val saver = SavedStateHandleStateSaver(SavedStateHandle())
        assertNull(BrowseSelection.restoreFrom(saver))
        val selections = MovieList.entries.map { BrowseSelection.Movies(it) } +
            TvList.entries.map { BrowseSelection.Tv(it) } + listOf(
                BrowseSelection.People,
                BrowseSelection.Trending(TrendingWindow.Day), BrowseSelection.Trending(TrendingWindow.Week),
            ) + MediaType.entries.flatMap { media -> DiscoverSort.entries.map { BrowseSelection.Discover(media, listOf(28, 18, 28), it) } }
        selections.forEach {
            it.saveTo(saver)
            assertEquals(it, BrowseSelection.restoreFrom(saver))
        }
    }

    @Test fun discoverCanonicalizesAndCopiesInputsWithoutCuratedProvenance() {
        val ids = mutableListOf(28, 18, 28)
        val selection = BrowseSelection.Discover(MediaType.Movie, ids, DiscoverSort.ReleaseDate)
        ids.clear()
        assertEquals(listOf(18, 28), selection.genreIds)
        assertEquals(BrowseSelection.Discover(MediaType.Movie, listOf(18, 28), DiscoverSort.ReleaseDate), selection)
        assertNotEquals<BrowseSelection>(BrowseSelection.Movies(MovieList.Upcoming), selection)
        assertFailsWith<IllegalArgumentException> { BrowseSelection.Discover(MediaType.Tv, listOf(0)) }
        assertFailsWith<IllegalArgumentException> { BrowseSelection.Discover(MediaType.Tv, listOf(1000000)) }
        assertFailsWith<IllegalArgumentException> { BrowseSelection.Discover(MediaType.Movie, (1..21).toList()) }
    }

    @Test fun malformedRestorationFailsAtTheBoundary() {
        val saver = SavedStateHandleStateSaver(SavedStateHandle())
        saver.putString("productBrowse.source", "NowPlayingDiscover")
        assertFailsWith<IllegalArgumentException> { BrowseSelection.restoreFrom(saver) }
        saver.putString("productBrowse.source", "Movies")
        assertFailsWith<IllegalArgumentException> { BrowseSelection.restoreFrom(saver) }
        saver.putString("productBrowse.value", "Bogus")
        assertFailsWith<IllegalArgumentException> { BrowseSelection.restoreFrom(saver) }
        BrowseSelection.Discover(MediaType.Movie).saveTo(saver)
        saver.putString("productBrowse.genres", "28,nope")
        assertFailsWith<IllegalArgumentException> { BrowseSelection.restoreFrom(saver) }
        saver.putString("productBrowse.genres", "-1")
        assertFailsWith<IllegalArgumentException> { BrowseSelection.restoreFrom(saver) }
        saver.putString("productBrowse.genres", "")
        saver.putString("productBrowse.sort", "NowPlaying")
        assertFailsWith<IllegalArgumentException> { BrowseSelection.restoreFrom(saver) }
    }

    @Test fun everyHomeHandoffKeepsItsExactEndpointAndTrendingWindow() {
        val expected = listOf(
            BrowseSelection.Trending(TrendingWindow.Day), BrowseSelection.Movies(MovieList.Popular),
            BrowseSelection.Tv(TvList.Popular), BrowseSelection.Movies(MovieList.NowPlaying),
            BrowseSelection.Movies(MovieList.Upcoming), BrowseSelection.People, BrowseSelection.Movies(MovieList.TopRated),
        )
        assertEquals(expected, HomeRow.entries.map { BrowseSelection.fromHomeRow(it, TrendingWindow.Day) })
        assertEquals(BrowseSelection.Trending(TrendingWindow.Week), BrowseSelection.fromHomeRow(HomeRow.Trending, TrendingWindow.Week))
    }
}
