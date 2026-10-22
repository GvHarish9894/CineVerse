package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.library.GuestLibrarySource
import com.techgv.cineverse.data.library.ImportOffer
import com.techgv.cineverse.data.library.ImportStepState
import com.techgv.cineverse.data.library.LibraryImporter
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** §8.3 dialog flow (rules of tvApp ImportLibraryControllerTest, as prompts instead of TV dialog states). */
class ImportLibraryStateHolderTest {
    private val account = FakeAccountLibrary()
    private val stores = InMemoryImportStores()
    private val signedIn = session()
    private val guest = GuestLibrarySource(InMemoryGuestLibraryStore())
    private val repository = LibraryRepository(MutableStateFlow(signedIn), guest, account)
    private val importer = LibraryImporter(stores, stores, guest, account, repository)
    private var imported = 0

    private suspend fun guestWatchlist(id: Int) =
        guest.write(title(id), LibraryKind.WATCHLIST, LibraryState(known = true, watchlist = true, favourite = false, rating = null, pending = emptySet()))

    private fun TestScope.holder(importer: LibraryImporter = this@ImportLibraryStateHolderTest.importer) =
        ImportLibraryStateHolder(importer, backgroundScope) { imported++ }

    @Test fun importPromptIsDeferredWhileThePlayerIsActive() = runTest {
        guestWatchlist(1)
        val holder = holder()
        holder.setPlayerActive(true)
        holder.onAccount(signedIn)
        assertNull(holder.prompt.first())

        holder.setPlayerActive(false)
        assertEquals(ImportPrompt.Ask("Harish", 1), holder.prompt.first())
    }

    @Test fun laterOnARunningImportCancelsItAndTheNextLaunchAsksToResumeOnce() = runTest {
        guestWatchlist(1)
        account.writeGate = CompletableDeferred() // the watchlist POST never answers
        val first = holder()
        first.onAccount(signedIn)
        first.import()
        runCurrent()
        assertEquals(ImportPrompt.Running, first.prompt.first())

        first.later()
        runCurrent()
        assertNull(first.prompt.first())
        assertEquals(ImportStepState.Attempting, stores.ledger[Triple("aoid", MediaRef(MediaType.Movie, 1), LibraryKind.WATCHLIST)])

        val nextLaunch = holder(LibraryImporter(stores, stores, guest, account, repository))
        nextLaunch.onAccount(signedIn)
        assertEquals(ImportPrompt.Ask("Harish", 1, resume = true), nextLaunch.prompt.first())
        nextLaunch.later()
        nextLaunch.onAccount(signedIn) // signed in again in the same launch: not offered twice
        assertNull(nextLaunch.prompt.first())
    }

    @Test fun aCompleteImportReportsAndClosesAndKeepSeparateIsRemembered() = runTest {
        guestWatchlist(1)
        val holder = holder()
        holder.onAccount(signedIn)
        holder.import()
        runCurrent()
        assertNull(holder.prompt.first())
        assertEquals(1, imported)
        assertEquals(listOf("watchlist/movie:1=true"), account.writes)

        val other = session("b", generation = 2)
        holder.onAccount(other)
        holder.keepSeparate()
        runCurrent()
        assertNull(importer.offer(other))
    }
}
