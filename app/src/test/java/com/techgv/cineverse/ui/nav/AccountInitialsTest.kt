package com.techgv.cineverse.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountInitialsTest {
    @Test
    fun `two initials at most, guest is G`() {
        assertEquals("HG", accountInitials("harish  gv kumar"))
        assertEquals("H", accountInitials("Harish"))
        assertEquals("G", accountInitials(null))
        assertEquals("G", accountInitials("  "))
    }
}
