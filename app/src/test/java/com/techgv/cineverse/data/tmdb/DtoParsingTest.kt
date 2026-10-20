package com.techgv.cineverse.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtoParsingTest {
    @Test
    fun `account_states rated is false or an object`() {
        assertNull(TmdbJson.decodeFromString<AccountStatesDto>("""{"id":1,"favorite":false,"rated":false,"watchlist":true}""").ratedValue)
        assertEquals(7.5, TmdbJson.decodeFromString<AccountStatesDto>("""{"id":1,"rated":{"value":7.5}}""").ratedValue!!, 0.0)
    }

    @Test
    fun `nullable paths and dates, decimal vote_average, unknown keys`() {
        val page = TmdbJson.decodeFromString<PageDto<MediaItemDto>>(
            """{"page":1,"total_pages":2,"total_results":3,"results":[
              {"id":1,"media_type":"movie","title":"A","poster_path":null,"release_date":"","vote_average":7,"genre_ids":[18],"new_field":1},
              {"id":2,"media_type":"person","name":"P","profile_path":null,"known_for":[{"id":3,"media_type":"tv","name":"S"}]},
              {"id":4,"media_type":"tv","name":"T","vote_average":8.25,"account_rating":{"value":9.0,"created_at":"2024-01-01"}}
            ]}""",
        )
        val (movie, person, tv) = page.results
        assertNull(movie.posterPath)
        assertEquals("", movie.releaseDate)
        assertEquals(7.0, movie.voteAverage, 0.0)
        assertEquals("tv", person.knownFor.single().mediaType)
        assertEquals(8.25, tv.voteAverage, 0.0)
        assertEquals(9.0, tv.accountRating!!.value, 0.0)
        assertEquals(2, page.totalPages)
    }
}
