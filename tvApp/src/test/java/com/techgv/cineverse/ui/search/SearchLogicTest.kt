package com.techgv.cineverse.ui.search

import com.techgv.cineverse.ui.components.KeyboardKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** §7.7 query editing: 28-char cap, Delete drops the last char, Clear empties, Space/Char append. */
class SearchLogicTest {
    @Test fun `chars and space append until the cap`() {
        assertEquals("ab", editQuery("a", KeyboardKey.Char('b')))
        assertEquals("a ", editQuery("a", KeyboardKey.Space))
    }

    @Test fun `typing stops at 28 characters`() {
        val full = "a".repeat(SEARCH_MAX_CHARS)
        assertEquals(full, typeIntoQuery(full, 'x'))
        assertEquals(28, editQuery(full, KeyboardKey.Char('x')).length)
    }

    @Test fun `delete and clear`() {
        assertEquals("ab", editQuery("abc", KeyboardKey.Delete))
        assertEquals("", editQuery("abc", KeyboardKey.Clear))
        assertEquals("", editQuery("", KeyboardKey.Delete))
    }
}
