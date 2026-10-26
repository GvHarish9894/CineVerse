package com.techgv.cineverse.data.tmdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The display formatters every shell uses for TMDB dates and runtimes (kit: "Mar 13, 2026", "2h 8m"). */
class DisplayFormatTest {
    @Test fun isoDatesBecomeShortEnglishDates() {
        assertEquals("Mar 13, 2026", formatDate("2026-03-13"))
        assertEquals("Jan 1, 1999", formatDate("1999-01-01"))
        assertEquals("Feb 29, 2024", formatDate("2024-02-29"))
        assertEquals("Dec 31, 2025", formatDate("2025-12-31"))
    }

    @Test fun missingOrInvalidDatesAreNullNeverGuessed() {
        assertNull(formatDate(null))
        assertNull(formatDate(""))
        assertNull(formatDate("   "))
        assertNull(formatDate("2026")) // Year-only input is not a date; use yearOf.
        assertNull(formatDate("2026-13-01"))
        assertNull(formatDate("2025-02-29"))
        assertNull(formatDate("13/03/2026"))
    }

    @Test fun yearOnlyWhenThatIsAllThatIsNeeded() {
        assertEquals("2026", yearOf("2026-03-13"))
        assertNull(yearOf(null))
        assertNull(yearOf(""))
        assertNull(yearOf("not-a-date"))
    }

    @Test fun runtimesUseHoursAndMinutes() {
        assertEquals("2h 8m", formatRuntime(128))
        assertEquals("1h 2m", formatRuntime(62))
        assertEquals("45m", formatRuntime(45))
        assertEquals("2h 0m", formatRuntime(120))
    }
}
