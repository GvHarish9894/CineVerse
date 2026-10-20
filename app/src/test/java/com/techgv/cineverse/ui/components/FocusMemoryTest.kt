package com.techgv.cineverse.ui.components

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class FocusMemoryTest {
    private val memory = FocusMemory(SavedStateHandle())
    private val restorer = FocusRestorer(memory)
    private val keys = (0..9).map { "movie:$it" }
    private val rowA = FocusRow("rows", "A", keys)
    private val rowB = FocusRow("rows", "B", keys)

    @Test
    fun `row A col 4, then row B, then back to A restores col 4`() {
        memory.save(SavedFocus("rows", "A", "movie:4", 4))
        memory.save(SavedFocus("rows", "B", "movie:0", 0))
        assertEquals(4, restorer.entryIndex(rowA))
        assertEquals(0, restorer.entryIndex(rowB))
        assertEquals(1 to 0, restorer.restoreTarget(listOf(rowA, rowB))) // last focus wins on screen restore
    }

    @Test
    fun `missing item falls back to min(savedIndex, lastIndex)`() {
        memory.save(SavedFocus("rows", "A", "movie:gone", 25))
        assertEquals(9, restorer.entryIndex(rowA))
    }

    @Test
    fun `empty saved row falls back to the first item of the first non-empty row`() {
        memory.save(SavedFocus("rows", "A", "movie:4", 4))
        val emptyA = FocusRow("rows", "A", emptyList())
        val emptyC = FocusRow("rows", "C", emptyList())
        assertEquals(2 to 0, restorer.restoreTarget(listOf(emptyA, emptyC, rowB)))
        assertNull(restorer.restoreTarget(listOf(emptyA, emptyC)))
    }

    @Test
    fun `same row and item keys in two panels do not collide`() {
        memory.save(SavedFocus("left", "r", "x", 3))
        memory.save(SavedFocus("right", "r", "x", 1))
        assertEquals(3, memory.savedIn("left", "r")!!.index)
        assertEquals(1, memory.savedIn("right", "r")!!.index)
        assertNotSame(restorer.requester("left", "r", "x"), restorer.requester("right", "r", "x"))
        assertEquals(restorer.requester("left", "r", "x"), restorer.requester("left", "r", "x"))
    }
}
