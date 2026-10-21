package com.techgv.cineverse.ui.nav

import com.techgv.cineverse.ui.components.initials
import org.junit.Assert.assertEquals
import org.junit.Test

/** NavRail's account badge: `initials(accountName.orEmpty()).ifEmpty { "G" }`. */
class AccountInitialsTest {
    @Test
    fun `two initials at most, blank gives none so the rail shows G`() {
        assertEquals("HG", initials("harish  gv kumar"))
        assertEquals("H", initials("Harish"))
        assertEquals("", initials(""))
        assertEquals("", initials("  "))
    }
}
