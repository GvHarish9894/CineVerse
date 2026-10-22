package com.techgv.cineverse.ui.components

import androidx.compose.ui.focus.FocusDirection
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.test.runTest

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
    fun `no memory - a D-pad entry into a row targets its first card, a programmatic one always keeps its own target`() {
        assertEquals("movie:0", restorer.entryKey(rowA, FocusDirection.Down))
        assertEquals("movie:0", restorer.entryKey(rowA, FocusDirection.Up))
        assertNull(restorer.entryKey(rowA, FocusDirection.Enter))
        memory.save(SavedFocus("rows", "A", "movie:4", 4))
        assertEquals("movie:4", restorer.entryKey(rowA, FocusDirection.Down))
        assertNull(restorer.entryKey(rowA, FocusDirection.Enter)) // remembered or not (c864c85)
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

    @Test
    fun `initial restore uses the focus saved at entry, not an incidental default-focus save`() = runTest {
        memory.save(SavedFocus("rows", "A", "movie:8", 8))
        val entered = FocusRestorer(memory)
        memory.save(SavedFocus("rows", "A", "movie:0", 0)) // default focus lands before restore runs
        assertEquals(0 to 8, entered.restoreTarget(listOf(rowA, rowB)))

        // Later restores are not frozen: after the first restore they follow the memory again.
        entered.restore(listOf(FocusRow("rows", "A", emptyList()))) // nothing focusable: returns at once
        assertEquals(0 to 0, entered.restoreTarget(listOf(rowA, rowB)))
    }
}
