package com.techgv.cineverse.data.library

import com.techgv.cineverse.data.tmdb.MediaItemDto
import com.techgv.cineverse.data.tmdb.PageDto
import com.techgv.cineverse.data.tmdb.TmdbJson
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class AccountLibraryMapperTest {
    /** Shape of `GET /4/account/{id}/tv/rated` (trimmed): no media_type, account_rating per item, blank date. */
    private val ratedTv = """
        {"page":1,"total_pages":3,"total_results":41,"results":[
          {"adult":false,"backdrop_path":"/b.jpg","genre_ids":[18,80],"id":1396,"origin_country":["US"],
           "original_language":"en","original_name":"Breaking Bad","overview":"A teacher…","popularity":402.1,
           "poster_path":"/p.jpg","first_air_date":"2008-01-20","name":"Breaking Bad","vote_average":8.9,
           "vote_count":15000,"account_rating":{"created_at":"2024-05-01T10:00:00.000Z","value":9.5}},
          {"adult":false,"backdrop_path":null,"genre_ids":[],"id":7,"name":"Pilot only","overview":"",
           "poster_path":null,"first_air_date":"","vote_average":0,"vote_count":0,
           "account_rating":{"created_at":"2024-05-02T10:00:00.000Z","value":5}}
        ]}
    """.trimIndent()

    @Test
    fun `account_rating value becomes the Rated badge and the list decides the media type`() {
        val page = TmdbJson.decodeFromString<PageDto<MediaItemDto>>(ratedTv)
        val (first, second) = page.results.map { it.toLibraryEntry(MediaType.Tv) }

        assertEquals(MediaRef(MediaType.Tv, 1396), first.title.ref)
        assertEquals(MediaType.Tv, first.title.mediaType)
        assertEquals("Breaking Bad", first.title.title)
        assertEquals("2008-01-20", first.title.releaseDate)
        assertEquals(listOf(18, 80), first.title.genreIds)
        assertEquals(9.5, first.rating)

        assertEquals(5.0, second.rating)
        assertNull(second.title.releaseDate, "blank date → null")
        assertNull(second.title.posterPath)
        assertEquals(0, second.title.voteCount)
    }

    @Test
    fun `movie watchlist item without account_rating has no badge`() {
        val item = TmdbJson.decodeFromString<MediaItemDto>("""{"id":550,"title":"Fight Club","release_date":"1999-10-15","vote_average":8.4,"vote_count":30000}""")
        val entry = item.toLibraryEntry(MediaType.Movie)
        assertEquals("Fight Club", entry.title.title)
        assertEquals("1999-10-15", entry.title.releaseDate)
        assertNull(entry.rating)
    }
}
