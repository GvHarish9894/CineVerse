package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Late non-cancellable persistence completions must not resolve or report another account's dialog. */
@OptIn(ExperimentalCoroutinesApi::class)
class StorageConsumerRecoveryTest {
    @Test fun lateAStorageFailureDoesNotReplaceBPromptOrReportAFailureForB() = staleKeepSeparate(fail = true)
    @Test fun lateAStorageSuccessDoesNotDismissBPrompt() = staleKeepSeparate(fail = false)

    private fun staleKeepSeparate(fail: Boolean) = runTest {
        val a = session("a"); val b = session("b", generation = 2)
        val identity = MutableStateFlow<AuthSession?>(a)
        val guest = GuestLibrarySource(InMemoryGuestLibraryStore())
        guest.write(title(1), LibraryKind.WATCHLIST, LibraryState(true, true, false, null, emptySet()))
        val stores = InMemoryImportStores()
        val gate = CompletableDeferred<Unit>()
        val offers = object : ImportOfferStore by stores {
            override suspend fun put(offer: ImportOfferRecord) {
                if (offer.accountObjectId == "a") withContext(NonCancellable) {
                    gate.await()
                    if (fail) throw StorageException("fixture")
                    stores.put(offer)
                } else stores.put(offer)
            }
        }
        val account = FakeAccountLibrary()
        val repository = LibraryRepository(identity, guest, account)
        val importer = LibraryImporter(stores, offers, guest, account, repository)
        var failures = 0; var imported = 0
        val holder = ImportLibraryStateHolder(importer, backgroundScope, { imported++ }, { failures++ })
        holder.onAccount(a); holder.keepSeparate(); runCurrent()
        identity.value = b
        holder.onAccount(b)
        gate.complete(Unit); runCurrent()
        assertEquals(ImportPrompt.Ask("Harish", 1), holder.prompt.first())
        assertEquals(0, failures); assertEquals(0, imported)
        holder.retryStorage(); runCurrent()
        assertNull(stores.offer("b"))
        assertTrue(account.writes.isEmpty())
    }

    @Test fun resumeOfferReadFailureDoesNotConsumeOncePerLaunchAndRetryDoesNotPost() = runTest {
        val a = session()
        val identity = MutableStateFlow<AuthSession?>(a)
        val guest = GuestLibrarySource(InMemoryGuestLibraryStore())
        guest.write(title(1), LibraryKind.WATCHLIST, LibraryState(true, true, false, null, emptySet()))
        val stores = InMemoryImportStores()
        stores.put(ImportOfferRecord("aoid", true, false))
        var failing = true
        val ledger = object : ImportLedgerStore by stores {
            override suspend fun state(accountObjectId: String, ref: com.techgv.cineverse.domain.MediaRef, kind: LibraryKind): ImportStepState? {
                if (failing) throw StorageException("fixture")
                return stores.state(accountObjectId, ref, kind)
            }
        }
        val account = FakeAccountLibrary()
        val importer = LibraryImporter(ledger, stores, guest, account, LibraryRepository(identity, guest, account))
        var failures = 0
        val holder = ImportLibraryStateHolder(importer, backgroundScope, {}, { failures++ })
        holder.onAccount(a)
        assertEquals(ImportPrompt.Failed, holder.prompt.first())
        assertEquals(1, failures)
        failing = false
        holder.retryStorage(); runCurrent()
        assertEquals(ImportPrompt.Ask("Harish", 1, resume = true), holder.prompt.first())
        assertTrue(account.writes.isEmpty())
        assertNull(importer.offer(a))
    }
}
