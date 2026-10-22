package com.techgv.cineverse.ui.home

import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §7.2 hero copy: eyebrow rank only within the trending row; meta = year + up to 3 genres; D5 person line. */
class HomeLogicTest {
    private fun title(media: MediaType = MediaType.Movie, date: String? = "2024-05-01", genres: List<Int> = emptyList()) =
        Title(MediaRef(media, 1), "T", "", null, null, date, 7.5, 100, genres, media)

    @Test fun `eyebrow ranks trending items and labels type`() {
        assertEquals("Movie · #3 trending this week", titleEyebrow(title(), 2, TrendingWindow.Week))
        assertEquals("TV series · #1 trending today", titleEyebrow(title(MediaType.Tv), 0, TrendingWindow.Day))
    }

    @Test fun `eyebrow outside the trending row shows type only`() {
        assertEquals("Movie", titleEyebrow(title(), -1, TrendingWindow.Week))
    }

    @Test fun `meta joins year and up to three known genres`() {
        val genres = mapOf(28 to "Action", 18 to "Drama", 12 to "Adventure", 35 to "Comedy")
        assertEquals("2024   •   Action · Drama · Adventure", titleMeta(title(genres = listOf(28, 18, 12, 35)), genres))
    }

    @Test fun `meta omits missing year and unknown genres`() {
        assertEquals("", titleMeta(title(date = null, genres = listOf(999)), emptyMap()))
    }

    @Test fun `person overview lists up to three known-for titles`() {
        assertEquals("Known for A, B, C", personOverview(listOf("A", "B", "C", "D")))
        assertEquals("", personOverview(listOf("", "  ")))
    }

    @Test fun `hero watchlist is disabled while the library state is unknown`() {
        assertFalse(heroWatchlistEnabled(hasActions = true, state = LibraryState.Unknown))
    }

    @Test fun `hero watchlist is disabled while a watchlist write is pending`() {
        val known = LibraryState.Unknown.copy(known = true)
        assertFalse(heroWatchlistEnabled(hasActions = true, state = known.copy(pending = setOf(LibraryKind.WATCHLIST))))
        assertTrue(heroWatchlistEnabled(hasActions = true, state = known.copy(pending = setOf(LibraryKind.FAVOURITE))))
    }

    @Test fun `hero watchlist is enabled for a known state, and never without LibraryActions`() {
        val known = LibraryState.Unknown.copy(known = true, watchlist = true)
        assertTrue(heroWatchlistEnabled(hasActions = true, state = known))
        assertFalse(heroWatchlistEnabled(hasActions = false, state = known))
    }
}
