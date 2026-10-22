package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.MediaType
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/** §3.2/§13: list items carry genre ids only, mixed lists carry `media_type`, `*_path`/dates may be "". */
class MappersTest {

    @Test fun `movie list item uses title and release date`() {
        val title = MediaItemDto(id = 603, title = "The Matrix", releaseDate = "1999-03-31", genreIds = listOf(28, 878))
            .toTitle(MediaType.Movie)!!
        assertEquals(MediaType.Movie, title.mediaType)
        assertEquals("The Matrix", title.title)
        assertEquals("1999-03-31", title.releaseDate)
        assertEquals(listOf(28, 878), title.genreIds)
    }

    @Test fun `tv list item uses name and first air date`() {
        val title = MediaItemDto(id = 1399, name = "Game of Thrones", firstAirDate = "2011-04-17").toTitle(MediaType.Tv)!!
        assertEquals("Game of Thrones", title.title)
        assertEquals("2011-04-17", title.releaseDate)
    }

    @Test fun `blank paths and dates become null`() {
        val title = MediaItemDto(id = 1, title = "X", posterPath = "", backdropPath = "  ", releaseDate = "").toTitle(MediaType.Movie)!!
        assertNull(title.posterPath)
        assertNull(title.backdropPath)
        assertNull(title.releaseDate)
    }

    @Test fun `mixed list dispatches on media_type`() {
        assertTrue(MediaItemDto(id = 1, mediaType = "movie", title = "A").toHero() is Hero.OfTitle)
        assertTrue(MediaItemDto(id = 2, mediaType = "tv", name = "B").toHero() is Hero.OfTitle)
        assertTrue(MediaItemDto(id = 3, mediaType = "person", name = "C").toHero() is Hero.OfPerson)
    }

    @Test fun `unknown media type with no list type maps to null`() {
        assertNull(MediaItemDto(id = 4, mediaType = "collection", name = "Z").toTitle())
        assertNull(MediaItemDto(id = 4, mediaType = "collection", name = "Z").toHero())
    }

    @Test fun `person keeps known_for titles`() {
        val person = MediaItemDto(
            id = 287,
            name = "Brad Pitt",
            knownForDepartment = "Acting",
            knownFor = listOf(MediaItemDto(id = 550, mediaType = "movie", title = "Fight Club")),
        ).toPerson()
        assertEquals("Acting", person.knownForDepartment)
        assertEquals(listOf("Fight Club"), person.knownFor.map { it.title })
    }

    @Test fun `youtube trailers sort official then trailer then teaser then other`() {
        val videos = listOf(
            VideoDto(key = "a", site = "YouTube", type = "Teaser"),
            VideoDto(key = "b", site = "Vimeo", type = "Trailer", official = true),
            VideoDto(key = "c", site = "YouTube", type = "Featurette"),
            VideoDto(key = "d", site = "YouTube", type = "Trailer", official = true),
            VideoDto(key = "e", site = "YouTube", type = "Trailer"),
            VideoDto(key = "", site = "YouTube", type = "Trailer", official = true),
        )
        // Vimeo and the blank key drop out; the rest sort by preference, stable within a group.
        assertEquals(listOf("d", "e", "a", "c"), videos.youTubeInTrailerOrder().map { it.key })
    }

    @Test fun `hasNextPage caps at total pages and 500`() {
        assertTrue(PageDto<Int>(page = 1, totalPages = 3, results = emptyList()).hasNextPage)
        assertTrue(!PageDto<Int>(page = 3, totalPages = 3, results = emptyList()).hasNextPage)
        assertTrue(!PageDto<Int>(page = 500, totalPages = 9000, results = emptyList()).hasNextPage)
    }
}
