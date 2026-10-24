package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.MediaRef
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

    @Test fun forcedRetryPermissionIsConsumedByPreflightAndNeverRestoredByStorageRecovery() = runTest {
        val a = session()
        val stores = InMemoryImportStores()
        val guest = GuestLibrarySource(InMemoryGuestLibraryStore())
        guest.write(title(1), LibraryKind.WATCHLIST, LibraryState(true, true, false, null, emptySet()))
        val remote = FakeAccountLibrary()
        var failPreflight = false; var failPost = true; var failDone = false; var posts = 0
        val account = object : AccountLibrarySource by remote {
            override suspend fun page(session: AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage {
                if (failPreflight) throw TmdbException(TmdbError.Network)
                return remote.page(session, kind, media, page)
            }
            override suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState) {
                posts++
                if (failPost) throw TmdbException(TmdbError.Network)
                remote.write(session, ref, kind, target)
            }
        }
        val ledger = object : ImportLedgerStore by stores {
            override suspend fun put(accountObjectId: String, ref: MediaRef, kind: LibraryKind, state: ImportStepState) {
                if (state == ImportStepState.Done && failDone) throw StorageException("fixture")
                stores.put(accountObjectId, ref, kind, state)
            }
        }
        val importer = LibraryImporter(ledger, stores, guest, account, LibraryRepository(MutableStateFlow(a), guest, account))
        var imported = 0
        val holder = ImportLibraryStateHolder(importer, backgroundScope, { imported++ }, {})
        holder.onAccount(a); holder.import(); runCurrent()
        assertIs<ImportPrompt.Unconfirmed>(holder.prompt.first())
        assertEquals(1, posts)
        failPreflight = true
        holder.retry(); runCurrent() // The warned authorization is consumed even before its failed preflight.
        assertEquals(ImportPrompt.Failed, holder.prompt.first())
        failPreflight = false
        holder.retry(); runCurrent() // Ordinary failure recovery is not a fresh warned authorization.
        assertEquals(1, posts)
        assertIs<ImportPrompt.Unconfirmed>(holder.prompt.first())
        failPost = false; failDone = true
        holder.retry(); runCurrent() // Renewed explicit confirmation allows exactly this operation.
        assertEquals(2, posts)
        assertEquals(ImportPrompt.Failed, holder.prompt.first())
        assertEquals(0, imported)
        remote.watchlist.clear(); remote.invalidateLists(); failDone = false
        holder.retryStorage(); runCurrent()
        assertEquals(2, posts)
        assertIs<ImportPrompt.Unconfirmed>(holder.prompt.first())
        assertEquals(ImportStepState.Attempting, stores.state("aoid", title(1).ref, LibraryKind.WATCHLIST))
        assertEquals(ImportOfferRecord("aoid", true, false), stores.offer("aoid"))
        holder.retry(); runCurrent()
        assertEquals(3, posts); assertEquals(1, imported)
        assertNull(holder.prompt.first())
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
