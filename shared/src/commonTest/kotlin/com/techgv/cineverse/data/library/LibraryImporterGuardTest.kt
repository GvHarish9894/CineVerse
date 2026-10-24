package com.techgv.cineverse.data.library

import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryImporterGuardTest {
    @Test fun unchangedFiveArgumentNativeDefaultImportsAndDurablyCompletes() = runTest {
        val a = session()
        val stores = InMemoryImportStores()
        val guest = GuestLibrarySource(InMemoryGuestLibraryStore())
        val account = FakeAccountLibrary()
        val repository = LibraryRepository(MutableStateFlow(a), guest, account)
        guest.write(title(1), LibraryKind.WATCHLIST, LibraryState(true, true, false, null, emptySet()))
        val importer = LibraryImporter(stores, stores, guest, account, repository)
        assertEquals(ImportOffer.Ask(1), importer.offer(a))
        assertEquals(ImportResult.Done(1, emptyList()), importer.import(a))
        assertEquals(ImportOfferRecord("aoid", true, true), stores.offer("aoid"))
        assertEquals(ImportStepState.Done, stores.state("aoid", title(1).ref, LibraryKind.WATCHLIST))
        assertEquals(listOf("watchlist/movie:1=true"), account.writes)
    }

    @Test fun injectedGuardPrecedesEvenInitialOfferWriteAndCancellationDoesNotEnterOperation() = runTest {
        val a = session()
        val stores = InMemoryImportStores()
        val guest = GuestLibrarySource(InMemoryGuestLibraryStore())
        val account = FakeAccountLibrary()
        val repository = LibraryRepository(MutableStateFlow(a), guest, account)
        val gate = CompletableDeferred<Unit>()
        var entered = false
        val importer = LibraryImporter(stores, stores, guest, account, repository, ImportOperationGuard { operation ->
            entered = true
            gate.await()
            operation()
        })
        val job = launch { importer.import(a) }
        runCurrent()
        assertTrue(entered)
        assertNull(stores.offer("aoid"))
        assertTrue(account.writes.isEmpty())
        job.cancelAndJoin()
        gate.complete(Unit)
        assertNull(stores.offer("aoid"))
    }
}
