package com.techgv.cineverse.ui.detail

import com.techgv.cineverse.data.tmdb.MovieDetailDto
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.presentation.HomeCard
import com.techgv.cineverse.ui.components.homeCardSharedKey
import com.techgv.cineverse.ui.components.sharedBackdropKey
import com.techgv.cineverse.ui.components.sharedPosterKey
import com.techgv.cineverse.ui.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Detail hero shows the opening card's seed until the detail loads; the loaded detail always wins. */
class DetailHeroArtTest {
    private val seeded = Route.Detail(MediaType.Movie, 550, posterPath = "/seed-poster.jpg", backdropPath = "/seed-backdrop.jpg", title = "Fight Club")
    private val loaded = MovieDetailDto(550, title = "Fight Club (loaded)", posterPath = null, backdropPath = "/real-backdrop.jpg").toDetail("IN")

    @Test
    fun `before the detail loads the seed is the hero art with the card's shared keys`() {
        val art = heroArt(detail = null, seed = seeded)!!
        assertEquals("Fight Club", art.title)
        assertEquals("/seed-poster.jpg", art.posterPath)
        assertEquals("/seed-backdrop.jpg", art.backdropPath)
        val card = MediaRef(MediaType.Movie, 550)
        assertEquals(homeCardSharedKey(card, HomeCard.Poster), sharedPosterKey(art.ref))
        assertEquals(homeCardSharedKey(card, HomeCard.Landscape), sharedBackdropKey(art.ref))
    }

    @Test
    fun `the loaded detail replaces the seed, even where it has no artwork`() {
        val art = heroArt(detail = loaded, seed = seeded)!!
        assertEquals("Fight Club (loaded)", art.title)
        assertNull(art.posterPath)
        assertEquals("/real-backdrop.jpg", art.backdropPath)
        assertEquals(sharedPosterKey(heroArt(null, seeded)!!.ref), sharedPosterKey(art.ref))
    }

    @Test
    fun `no seed - nothing before the detail loads (deep link, restore, Person, Search)`() {
        assertNull(heroArt(detail = null, seed = Route.Detail(MediaType.Movie, 550)))
        assertNull(heroArt(detail = null, seed = null))
    }
}
