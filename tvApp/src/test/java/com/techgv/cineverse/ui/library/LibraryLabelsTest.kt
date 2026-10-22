package com.techgv.cineverse.ui.library

import com.techgv.cineverse.ui.components.formatRating
import com.techgv.cineverse.ui.components.ratingWord
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryLabelsTest {
    @Test
    fun `rate dialog word labels follow the thresholds`() {
        assertEquals(
            listOf("Poor", "Poor", "Meh", "Meh", "Good", "Good", "Great", "Great", "Masterpiece", "Masterpiece"),
            listOf(0.5, 3.0, 3.5, 5.0, 5.5, 7.0, 7.5, 8.5, 9.0, 10.0).map(::ratingWord),
        )
        assertEquals("7.0", formatRating(7.0))
    }
}
