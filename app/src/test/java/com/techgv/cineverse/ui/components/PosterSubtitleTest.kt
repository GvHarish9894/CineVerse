package com.techgv.cineverse.ui.components

import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PosterSubtitleTest {
    private val today = LocalDate.of(2026, 10, 21)
    private fun title(date: String?, votes: Int, average: Double = 0.0) =
        Title(MediaRef(MediaType.Movie, 1), "t", "", null, null, date, average, votes, emptyList(), MediaType.Movie)

    @Test
    fun `rated shows year and stars, unrated future shows Coming, unrated past shows year and Unrated`() {
        assertEquals("2024 · ★ 7.8", posterSubtitle(title("2024-05-01", 10, 7.8), today))
        assertEquals("Coming Dec 11, 2026", posterSubtitle(title("2026-12-11", 0), today))
        assertEquals("2020 · Unrated", posterSubtitle(title("2020-01-01", 0), today))
        assertEquals("Unrated", posterSubtitle(title(null, 0), today))
    }
}
