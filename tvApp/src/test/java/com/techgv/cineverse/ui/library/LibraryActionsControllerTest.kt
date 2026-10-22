package com.techgv.cineverse.ui.library

import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.library.AccountLibrarySource
import com.techgv.cineverse.data.library.FakeAccountApi
import com.techgv.cineverse.data.library.FakeLibraryDao
import com.techgv.cineverse.data.library.GuestLibrarySource
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.library.credentials
import com.techgv.cineverse.data.library.title
import com.techgv.cineverse.data.library.tmdbRepository
import com.techgv.cineverse.ui.components.Toast
import com.techgv.cineverse.ui.components.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryActionsControllerTest {
    private val api = FakeAccountApi()
    private val a = credentials()
    private val b = credentials(aoid = "b", session = "sb")
    private val identity = MutableStateFlow<Credentials?>(a)
    private val repository = LibraryRepository(identity, GuestLibrarySource(FakeLibraryDao()) { 0L }, AccountLibrarySource(tmdbRepository(api)))
    private val toaster = Toaster()
    private val movie = title(1)

    /** Actions dispatch on the test scheduler: launched on click, run at the next runCurrent. */
    private fun TestScope.controller() = LibraryActionsController(repository, toaster, backgroundScope, backgroundScope).also { it.onIdentity(a) }

    private fun TestScope.toasts(): List<Toast> = mutableListOf<Toast>().also { list -> backgroundScope.launch { toaster.toasts.collect { list += it } } }

    private fun LibraryActionsController.switchTo(c: Credentials?) {
        identity.value = c
        onIdentity(c)
    }

    @Test
    fun `a toggle clicked under A and dispatched after a switch to B is superseded and B is untouched`() = runTest {
        val toasts = toasts()
        val controller = controller()
        controller.toggleWatchlist(movie)
        controller.toggleFavourite(movie)
        controller.switchTo(b)
        runCurrent()
        assertTrue("never retargeted to B", api.writes.isEmpty())
        assertTrue("superseded: no toast", toasts.isEmpty())

        controller.toggleWatchlist(movie) // clicked under B: written to B
        runCurrent()
        assertEquals(listOf("watchlist/movie:1=true"), api.writes)
        assertEquals(1, toasts.size)
    }

    @Test
    fun `a rate dialog requested before the Player started is not shown over it`() = runTest {
        val controller = controller()
        controller.openRate(movie)
        controller.setPlayerActive(true)
        runCurrent()
        assertNull(controller.shown.first())

        controller.setPlayerActive(false)
        assertEquals(movie, controller.shown.first()?.title)
    }

    @Test
    fun `a rate dialog whose sign-in changed before it opened is not shown`() = runTest {
        val controller = controller()
        controller.openRate(movie)
        controller.switchTo(b)
        runCurrent()
        assertNull(controller.shown.first())
    }
}
