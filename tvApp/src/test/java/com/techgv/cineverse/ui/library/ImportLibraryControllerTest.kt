package com.techgv.cineverse.ui.library

import com.techgv.cineverse.data.library.accountSource
import com.techgv.cineverse.data.library.FakeAccountApi
import com.techgv.cineverse.data.library.FakeImportLedgerDao
import com.techgv.cineverse.data.library.FakeLibraryDao
import com.techgv.cineverse.data.library.guestSource
import com.techgv.cineverse.data.library.ImportLedgerEntity
import com.techgv.cineverse.data.library.ImportOffer
import com.techgv.cineverse.data.library.LibraryImporter
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.library.RoomImportStore
import com.techgv.cineverse.data.library.credentials
import com.techgv.cineverse.data.library.session
import com.techgv.cineverse.data.library.title
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.ui.components.ImportDialogState
import com.techgv.cineverse.ui.components.Toaster
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportLibraryControllerTest {
    private val api = FakeAccountApi()
    private val ledger = FakeImportLedgerDao()
    private val account = credentials().session()
    private val guest = guestSource(FakeLibraryDao())
    private val accountSource = accountSource(api)
    private val importStore = RoomImportStore(ledger)
    private val repository = LibraryRepository(MutableStateFlow(account), guest, accountSource)
    private val importer = LibraryImporter(importStore, importStore, guest, accountSource, repository)

    private suspend fun guestWatchlist(id: Int) =
        guest.write(title(id), LibraryKind.WATCHLIST, LibraryState(known = true, watchlist = true, favourite = false, rating = null, pending = emptySet()))

    private fun TestScope.controller(importer: LibraryImporter = this@ImportLibraryControllerTest.importer) =
        ImportLibraryController(importer, Toaster(), backgroundScope)

    @Test
    fun `import prompt is deferred while the Player is active`() = runTest {
        guestWatchlist(1)
        val controller = controller()
        controller.setPlayerActive(true)
        controller.onAccount(account)
        assertNull(controller.shown.first())

        controller.setPlayerActive(false)
        assertEquals(ImportDialogState.Ask("Harish", 1), controller.shown.first())
    }

    @Test
    fun `Later on a running import cancels it and keeps the attempting ledger`() = runTest {
        guestWatchlist(1)
        api.writeGate = CompletableDeferred() // the watchlist POST never answers
        val controller = controller()
        controller.onAccount(account)
        controller.import()
        runCurrent()
        assertEquals(ImportDialogState.Running, controller.shown.first())

        controller.later()
        runCurrent()

        assertNull(controller.shown.first())
        assertEquals(ImportLedgerEntity.ATTEMPTING, ledger.entries[listOf("aoid", "movie", 1, "WATCHLIST")])
        assertEquals("not finished: a later run resumes and reconciles", ImportOffer.Resume(1), importer.offer(account))
    }

    @Test
    fun `after Later the next launch asks to resume instead of auto-running, once per launch`() = runTest {
        guestWatchlist(1)
        api.writeGate = CompletableDeferred()
        val first = controller()
        first.onAccount(account)
        first.import()
        runCurrent()
        first.later()
        runCurrent()
        val writes = api.writes.toList()

        val nextLaunch = controller(LibraryImporter(importStore, importStore, guest, accountSource, repository))
        nextLaunch.onAccount(account)
        runCurrent()
        assertEquals(ImportDialogState.Ask("Harish", 1, resume = true), nextLaunch.shown.first())
        assertEquals("nothing ran by itself", writes, api.writes)

        nextLaunch.later()
        nextLaunch.onAccount(account) // e.g. signed in again in the same launch
        assertNull(nextLaunch.shown.first())
        assertEquals(ImportLedgerEntity.ATTEMPTING, ledger.entries[listOf("aoid", "movie", 1, "WATCHLIST")])
    }
}
