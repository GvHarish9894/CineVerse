package com.techgv.cineverse.ui.library

import com.techgv.cineverse.data.library.title
import com.techgv.cineverse.ui.components.formatRating
import com.techgv.cineverse.ui.components.ratingWord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class LibraryLabelsTest {
    @Test
    fun `rate dialog word labels follow the thresholds`() {
        assertEquals(
            listOf("Poor", "Poor", "Meh", "Meh", "Good", "Good", "Great", "Great", "Masterpiece", "Masterpiece"),
            listOf(0.5, 3.0, 3.5, 5.0, 5.5, 7.0, 7.5, 8.5, 9.0, 10.0).map(::ratingWord),
        )
        assertEquals("7.0", formatRating(7.0))
    }

    @Test
    fun `poster sub-line shows year and score, Coming for a future date, Unrated without votes`() {
        Locale.setDefault(Locale.US)
        val today = LocalDate.of(2026, 10, 21)
        assertEquals("2020 · ★ 7.0", posterSubline(title(1), today))
        assertEquals("Unrated", posterSubline(title(1).copy(voteCount = 0), today))
        assertEquals("Coming Dec 1, 2026", posterSubline(title(1).copy(releaseDate = "2026-12-01"), today))
    }
}
