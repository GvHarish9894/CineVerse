package com.techgv.cineverse.ui.detail

import com.techgv.cineverse.data.tmdb.MovieDetailDto
import com.techgv.cineverse.data.tmdb.SeasonSummaryDto
import com.techgv.cineverse.data.tmdb.TvDetailDto
import com.techgv.cineverse.data.tmdb.VideoDto
import com.techgv.cineverse.data.tmdb.VideosDto
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.domain.LibraryActions
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.ui.nav.Route
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailButtonsTest {
    private val calls = mutableListOf<String>()
    private val navigated = mutableListOf<Route>()
    private val library = object : LibraryActions {
        override fun state(ref: MediaRef): Flow<LibraryState> = emptyFlow()
        override fun toggleWatchlist(title: Title) { calls += "watchlist:${title.ref.key}" }
        override fun toggleFavourite(title: Title) { calls += "favourite:${title.ref.key}" }
        override fun openRate(title: Title) { calls += "rate:${title.ref.key}" }
    }
    private val trailer = VideoDto(key = "yt1", name = "Official Trailer", site = "YouTube", type = "Trailer", official = true)
    private val movie = MovieDetailDto(550, title = "Fight Club", videos = VideosDto(listOf(trailer))).toDetail("IN")

    private fun buttons(state: LibraryState, actions: LibraryActions? = library, detail: com.techgv.cineverse.domain.Detail = movie) =
        detailButtons(detail, state, DetailActions(onRetry = {}, library = actions, onNavigate = { navigated += it }))
            .associateBy { it.key }

    @Test
    fun `no LibraryActions - library buttons disabled, trailer still enabled`() {
        val b = buttons(LibraryState(true, false, false, null, emptySet()), actions = null)
        assertEquals(listOf("trailer", "watchlist", "favourite", "rate"), b.keys.toList())
        assertTrue(b.getValue("trailer").enabled)
        assertFalse(b.getValue("watchlist").enabled || b.getValue("favourite").enabled || b.getValue("rate").enabled)
    }

    @Test
    fun `unknown state - library buttons disabled, never shown as on`() {
        val b = buttons(LibraryState.Unknown)
        assertFalse(b.getValue("watchlist").enabled || b.getValue("favourite").enabled || b.getValue("rate").enabled)
        assertFalse(b.getValue("watchlist").on)
    }

    @Test
    fun `known state - labels, pending kind disabled, clicks reach LibraryActions`() {
        val b = buttons(LibraryState(true, watchlist = true, favourite = false, rating = 7.5, pending = setOf(LibraryKind.FAVOURITE)))
        assertEquals("On watchlist", b.getValue("watchlist").label)
        assertEquals("Rated 7.5", b.getValue("rate").label)
        assertTrue(b.getValue("watchlist").enabled)
        assertFalse(b.getValue("favourite").enabled)
        b.getValue("watchlist").onClick()
        b.getValue("rate").onClick()
        assertEquals(listOf("watchlist:movie:550", "rate:movie:550"), calls)
    }

    @Test
    fun `play trailer opens the player route, hidden without a YouTube video`() {
        buttons(LibraryState.Unknown).getValue("trailer").onClick()
        assertEquals(Route.Player("yt1", title = "Fight Club", kicker = "Official Trailer"), navigated.single())
        val unnamed = MovieDetailDto(550, title = "Fight Club", videos = VideosDto(listOf(trailer.copy(name = "")))).toDetail("IN")
        assertEquals("Trailer", playerRoute(unnamed, unnamed.videos.single()).kicker)
        val noVideo = MovieDetailDto(1, videos = VideosDto(listOf(trailer.copy(site = "Vimeo")))).toDetail("IN")
        assertFalse("trailer" in buttons(LibraryState.Unknown, detail = noVideo))
    }

    @Test
    fun `episodes button opens the first listed season, hidden without seasons`() {
        val tv = TvDetailDto(7, seasons = listOf(SeasonSummaryDto(10, 0), SeasonSummaryDto(11, 1))).toDetail("IN")
        buttons(LibraryState.Unknown, detail = tv).getValue("episodes").onClick()
        assertEquals(Route.Season(7, 1), navigated.single())
        assertFalse("episodes" in buttons(LibraryState.Unknown, detail = TvDetailDto(8).toDetail("IN")))
    }
}
