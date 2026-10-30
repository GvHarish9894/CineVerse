package com.techgv.cineverse.presentation

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.AccountLibrarySource
import com.techgv.cineverse.data.library.GuestLibrarySource
import com.techgv.cineverse.data.library.LibraryEntry
import com.techgv.cineverse.data.library.LibraryPage
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Source identity and page metadata belong to the same immutable result, never a UI session hint. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryReadProvenanceTest {
    private data class Read(val identity: AuthSession, val kind: LibraryKind, val media: MediaType, val page: Int) {
        fun result(vararg ids: Int, lastPage: Int = 3) =
            LibraryPage(ids.map { LibraryEntry(title(it, media), null) }, page, lastPage)
    }

    private class Account : AccountLibrarySource by FakeAccountLibrary() {
        val reads = mutableListOf<Read>()
        var read: suspend (Read) -> LibraryPage = { it.result(1) }
        override suspend fun page(session: AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage {
            val request = Read(session, kind, media, page)
            reads += request
            return read(request)
        }
    }

    private val account = Account()
    private val identity = MutableStateFlow<AuthSession?>(session())
    private val guest = GuestLibrarySource(InMemoryGuestLibraryStore())
    private val repository = LibraryRepository(identity, guest, account)
    private var storageFailures = 0

    private fun TestScope.holder(repo: LibraryRepository = repository, ownerScope: CoroutineScope = backgroundScope) =
        LibraryStateHolder(repo, SavedStateHandleStateSaver(SavedStateHandle()), ownerScope) { storageFailures++ }

    private fun LibraryGroup.ids() = (section as SectionState.Content).value.map { it.title.ref.id }

    @Test fun initialUnknownIsNotAnObservedGuestSource() = runTest {
        val sessions = MutableSharedFlow<AuthSession?>()
        val holder = holder(LibraryRepository(sessions, guest, account))
        assertEquals(LibraryUiState(), holder.state.value)
        runCurrent()
        assertFalse(holder.state.value.sourceIdentityKnown)
        assertNull(holder.state.value.sourceIdentity)
        assertNull(holder.state.value.movies.loadedPage)
        sessions.emit(null)
        runCurrent()
        assertTrue(holder.state.value.sourceIdentityKnown)
        assertNull(holder.state.value.sourceIdentity)
        assertEquals(SectionState.Empty, holder.state.value.movies.section)
        assertEquals(SectionState.Empty, holder.state.value.tv.section)
        assertNull(holder.state.value.movies.loadedPage)
        assertNull(holder.state.value.tv.loadedPage)
    }

    @Test fun sameNameAccountsAndNewOpaqueGenerationsResetBothGroupsAtomically() = runTest {
        val a = session("a", "Same name", Long.MAX_VALUE)
        val b = session("b", "Same name", Long.MAX_VALUE)
        identity.value = a
        val holder = holder()
        runCurrent()
        holder.loadMore(MediaType.Movie); runCurrent()
        assertEquals(2, holder.state.value.movies.loadedPage)
        for (replacement in listOf(b, b.copy(sessionGeneration = Long.MIN_VALUE))) {
            val gate = CompletableDeferred<Unit>()
            account.read = { request -> gate.await(); request.result(9, lastPage = 1) }
            identity.value = replacement
            runCurrent()
            val pending = holder.state.value
            assertTrue(pending.sourceIdentityKnown)
            assertEquals(replacement, pending.sourceIdentity)
            assertEquals("Same name", pending.accountName)
            assertEquals(LibraryGroup(), pending.movies)
            assertEquals(LibraryGroup(), pending.tv)
            gate.complete(Unit); runCurrent()
            assertEquals(replacement, holder.state.value.sourceIdentity)
            assertEquals(listOf(9), holder.state.value.movies.ids())
            assertEquals(1, holder.state.value.movies.loadedPage)
            assertEquals(1, holder.state.value.tv.loadedPage)
        }
    }

    @Test fun independentPagesAdvanceEvenWhenEmptyOrDuplicateResultsAddNoItems() = runTest {
        account.read = { request ->
            when {
                request.page > 1 -> request.result(lastPage = 3)
                request.media == MediaType.Movie -> request.result(1, 2)
                else -> request.result(8)
            }
        }
        val holder = holder(); runCurrent()
        holder.loadMore(MediaType.Movie); runCurrent()
        holder.loadMore(MediaType.Movie); runCurrent()
        assertEquals(listOf(1, 2), holder.state.value.movies.ids())
        assertEquals(3, holder.state.value.movies.loadedPage)
        assertEquals(1, holder.state.value.tv.loadedPage)
        account.read = { it.result(8, lastPage = 2) }
        holder.loadMore(MediaType.Tv); runCurrent()
        assertEquals(listOf(8), holder.state.value.tv.ids())
        assertEquals(2, holder.state.value.tv.loadedPage)
        assertEquals(3, holder.state.value.movies.loadedPage)
        account.read = { it.result(lastPage = 1) }
        holder.revalidate(); runCurrent()
        for (media in MediaType.entries) {
            assertEquals(SectionState.Empty, holder.state.value.group(media).section)
            assertEquals(1, holder.state.value.group(media).loadedPage)
        }
    }

    @Test fun emptyFirstAccountPagesAreCompletedReadsNotUnknown() = runTest {
        account.read = { it.result(lastPage = 0) }
        val holder = holder(); runCurrent()
        assertEquals(identity.value, holder.state.value.sourceIdentity)
        for (media in MediaType.entries) {
            assertEquals(SectionState.Empty, holder.state.value.group(media).section)
            assertEquals(1, holder.state.value.group(media).loadedPage)
            assertFalse(holder.state.value.group(media).hasMore)
        }
        account.read = { throw TmdbException(TmdbError.Network) }
        holder.revalidate(); runCurrent()
        assertEquals(SectionState.Error(SectionErrorKind.Offline), holder.state.value.movies.section)
        assertEquals(1, holder.state.value.movies.loadedPage)
        assertEquals(identity.value, holder.state.value.sourceIdentity)
        account.read = { it.result(lastPage = 0) }
        holder.retry(MediaType.Movie); runCurrent()
        assertEquals(SectionState.Empty, holder.state.value.movies.section)
        assertEquals(1, holder.state.value.movies.loadedPage)
    }

    @Test fun nextPageAndPartialRefreshErrorsRetainTheLastCompletedPageAndPayload() = runTest {
        account.read = { it.result(it.page) }
        val holder = holder(); runCurrent()
        account.read = { throw TmdbException(TmdbError.Network) }
        holder.loadMore(MediaType.Movie); runCurrent()
        assertEquals(1, holder.state.value.movies.loadedPage)
        assertEquals(listOf(1), holder.state.value.movies.ids())
        assertEquals(SectionErrorKind.Offline, holder.state.value.movies.moreError)
        account.read = { it.result(it.page) }
        holder.retry(MediaType.Movie); runCurrent()
        val completed = holder.state.value
        assertEquals(2, completed.movies.loadedPage)
        account.read = { if (it.page == 2) throw TmdbException(TmdbError.Network) else it.result(90) }
        holder.revalidate(); runCurrent()
        assertEquals(completed.movies.ids(), holder.state.value.movies.ids())
        assertEquals(2, holder.state.value.movies.loadedPage)
        assertEquals(completed.sourceIdentity, holder.state.value.sourceIdentity)
        assertEquals(SectionErrorKind.Offline, holder.state.value.movies.refreshError)
        account.read = { it.result(90, lastPage = 1) }
        holder.retry(MediaType.Movie); runCurrent()
        assertEquals(listOf(90), holder.state.value.movies.ids())
        assertEquals(1, holder.state.value.movies.loadedPage)
        assertNull(holder.state.value.movies.refreshError)
    }

    @Test fun revalidationSupersedesALateNextPageWithoutRetaggingTheFreshPayload() = runTest {
        val holder = holder(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        account.read = { request -> withContext(NonCancellable) { gate.await() }; request.result(2) }
        holder.loadMore(MediaType.Movie); runCurrent()
        assertEquals(1, holder.state.value.movies.loadedPage)
        assertTrue(holder.state.value.movies.loadingMore)
        account.read = { it.result(10, lastPage = 1) }
        holder.revalidate(); runCurrent()
        val fresh = holder.state.value
        assertEquals(listOf(10), fresh.movies.ids())
        assertEquals(1, fresh.movies.loadedPage)
        gate.complete(Unit); runCurrent()
        assertEquals(fresh, holder.state.value)
    }

    @Test fun loadMoreDuringUnchangedRevalidationIsCoalescedAndAppendedOnlyAfterTheReplacement() = runTest {
        val holder = holder(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        account.read = { request -> gate.await(); request.result(if (request.page == 1) 1 else 20) }
        holder.revalidate(); runCurrent()
        val readCount = account.reads.size
        repeat(3) { holder.loadMore(MediaType.Movie) }
        runCurrent()
        assertEquals(readCount, account.reads.size)
        assertEquals(listOf(1), holder.state.value.movies.ids())
        assertEquals(1, holder.state.value.movies.loadedPage)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(1, 20), holder.state.value.movies.ids())
        assertEquals(2, holder.state.value.movies.loadedPage)
        assertTrue(holder.state.value.movies.hasMore)
        assertEquals(1, account.reads.count { it.media == MediaType.Movie && it.page == 2 })
        assertEquals(1, holder.state.value.tv.loadedPage)
    }

    @Test fun pendingMoreIsDiscardedAfterRefreshFailureAndIsNotReplayedOnRecovery() = runTest {
        val holder = holder(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        account.read = { gate.await(); throw TmdbException(TmdbError.Network) }
        holder.revalidate(); runCurrent()
        holder.loadMore(MediaType.Movie)
        gate.complete(Unit); runCurrent()
        assertEquals(SectionErrorKind.Offline, holder.state.value.movies.refreshError)
        assertEquals(1, holder.state.value.movies.loadedPage)
        account.read = { it.result(1) }
        holder.retry(MediaType.Movie); runCurrent()
        assertNull(holder.state.value.movies.refreshError)
        assertEquals(1, holder.state.value.movies.loadedPage)
        assertTrue(account.reads.none { it.page == 2 })
    }

    @Test fun pendingMoreIsDiscardedWhenAnotherRevalidationSupersedesTheQueuedRead() = runTest {
        val holder = holder(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        account.read = { request -> withContext(NonCancellable) { gate.await() }; request.result(7) }
        holder.revalidate(); runCurrent()
        holder.loadMore(MediaType.Movie)
        account.read = { it.result(30) }
        holder.revalidate(); runCurrent()
        val fresh = holder.state.value
        assertEquals(listOf(30), fresh.movies.ids())
        assertEquals(1, fresh.movies.loadedPage)
        gate.complete(Unit); runCurrent()
        assertEquals(fresh, holder.state.value)
        assertTrue(account.reads.none { it.page == 2 })
    }

    @Test fun pendingMoreIsDiscardedOnAccountReplacement() = pendingMoreReplacement(changeTab = false)
    @Test fun pendingMoreIsDiscardedOnTabReplacement() = pendingMoreReplacement(changeTab = true)

    private fun pendingMoreReplacement(changeTab: Boolean) = runTest {
        val holder = holder(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        account.read = { request -> withContext(NonCancellable) { gate.await() }; request.result(7) }
        holder.revalidate(); runCurrent()
        holder.loadMore(MediaType.Movie)
        account.read = { it.result(9) }
        if (changeTab) holder.selectTab(LibraryTab.Favourites) else identity.value = session("b")
        runCurrent()
        val fresh = holder.state.value
        assertEquals(identity.value, fresh.sourceIdentity)
        assertEquals(if (changeTab) LibraryTab.Favourites else LibraryTab.Watchlist, fresh.tab)
        assertEquals(listOf(9), fresh.movies.ids())
        assertEquals(1, fresh.movies.loadedPage)
        gate.complete(Unit); runCurrent()
        assertEquals(fresh, holder.state.value)
        assertTrue(account.reads.none { it.page == 2 })
    }

    @Test fun revisionInvalidationDropsOlderRevalidationAndStopsItsRemainingPages() = runTest {
        val holder = holder(); runCurrent()
        holder.loadMore(MediaType.Movie); runCurrent()
        val gate = CompletableDeferred<Unit>()
        account.read = { request -> withContext(NonCancellable) { gate.await() }; request.result(7) }
        holder.revalidate(); runCurrent()
        account.read = { it.result(30, lastPage = 1) }
        repository.onExternalWrites(identity.value!!); runCurrent()
        val fresh = holder.state.value
        val readCount = account.reads.size
        assertEquals(1, fresh.movies.loadedPage)
        assertEquals(listOf(30), fresh.movies.ids())
        gate.complete(Unit); runCurrent()
        assertEquals(readCount, account.reads.size, "obsolete revalidation must not fetch its second page")
        assertEquals(fresh, holder.state.value)
    }

    @Test fun tabReplacementDropsLateOldTabResultsAndResetsPageMetadata() = runTest {
        val gate = CompletableDeferred<Unit>()
        account.read = { request ->
            if (request.kind == LibraryKind.WATCHLIST) withContext(NonCancellable) { gate.await() }
            request.result(if (request.kind == LibraryKind.WATCHLIST) 1 else 8, lastPage = 1)
        }
        val holder = holder(); runCurrent()
        assertTrue(holder.state.value.sourceIdentityKnown)
        assertNull(holder.state.value.movies.loadedPage)
        holder.selectTab(LibraryTab.Favourites); runCurrent()
        val fresh = holder.state.value
        assertEquals(LibraryTab.Favourites, fresh.tab)
        assertEquals(identity.value, fresh.sourceIdentity)
        assertEquals(listOf(8), fresh.movies.ids())
        assertEquals(1, fresh.movies.loadedPage)
        gate.complete(Unit); runCurrent()
        assertEquals(fresh, holder.state.value)
    }

    @Test fun otherSameNameAccountCannotReceiveLateOldAccountSuccess() =
        lateAccountCompletion(session("b", "Same name", Long.MAX_VALUE), fail = false)

    @Test fun otherSameNameAccountCannotReceiveLateOldAccountStorageFailure() =
        lateAccountCompletion(session("b", "Same name", Long.MAX_VALUE), fail = true)

    @Test fun sameAccountNewOpaqueGenerationCannotReceiveOldGenerationSuccess() =
        lateAccountCompletion(session("a", "Same name", Long.MIN_VALUE), fail = false)

    private fun lateAccountCompletion(replacement: AuthSession, fail: Boolean) = runTest {
        val a = session("a", "Same name", Long.MAX_VALUE)
        identity.value = a
        val gate = CompletableDeferred<Unit>()
        account.read = { request ->
            if (request.identity == a && request.media == MediaType.Movie) withContext(NonCancellable) {
                gate.await()
                if (fail) throw StorageException("fixture")
            }
            request.result(if (request.identity == a) 1 else 2, lastPage = 1)
        }
        val holder = holder(); runCurrent()
        identity.value = replacement; runCurrent()
        val fresh = holder.state.value
        assertEquals(replacement, fresh.sourceIdentity)
        assertEquals(listOf(2), fresh.movies.ids())
        assertEquals(1, fresh.movies.loadedPage)
        gate.complete(Unit); runCurrent()
        assertEquals(fresh, holder.state.value)
        assertEquals(0, storageFailures)
    }

    @Test fun cancelledOwnerCannotPublishLateSuccess() = lateCancelledCompletion(fail = false)
    @Test fun cancelledOwnerCannotReportLateStorageFailure() = lateCancelledCompletion(fail = true)

    private fun lateCancelledCompletion(fail: Boolean) = runTest {
        val owner = Job(backgroundScope.coroutineContext[Job])
        val gate = CompletableDeferred<Unit>()
        account.read = { request ->
            withContext(NonCancellable) {
                gate.await()
                if (fail) throw StorageException("fixture")
            }
            request.result(9)
        }
        val holder = holder(ownerScope = CoroutineScope(backgroundScope.coroutineContext + owner))
        runCurrent()
        val before = holder.state.value
        owner.cancel()
        gate.complete(Unit); runCurrent()
        assertEquals(before, holder.state.value)
        assertNull(holder.state.value.movies.loadedPage)
        assertEquals(0, storageFailures)
    }

    @Test fun ownerCancellationDiscardsPendingMoreAfterALateRefresh() = runTest {
        val owner = Job(backgroundScope.coroutineContext[Job])
        val holder = holder(ownerScope = CoroutineScope(backgroundScope.coroutineContext + owner))
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        account.read = { request -> withContext(NonCancellable) { gate.await() }; request.result(9) }
        holder.revalidate(); runCurrent()
        holder.loadMore(MediaType.Movie)
        val before = holder.state.value
        owner.cancel()
        gate.complete(Unit); runCurrent()
        assertEquals(before, holder.state.value)
        assertTrue(account.reads.none { it.page == 2 })
    }

    @Test fun page500IsTheProtocolCapEvenIfTheSourceClaimsMore() = runTest {
        account.read = { it.result(1, lastPage = 501) }
        val holder = holder(); runCurrent()
        repeat(499) { holder.loadMore(MediaType.Movie); runCurrent() }
        assertEquals(500, holder.state.value.movies.loadedPage)
        assertEquals(listOf(1), holder.state.value.movies.ids())
        assertFalse(holder.state.value.movies.hasMore)
        val readCount = account.reads.size
        holder.loadMore(MediaType.Movie); holder.retry(MediaType.Movie); runCurrent()
        assertEquals(readCount, account.reads.size)
        holder.revalidate(); runCurrent()
        assertEquals(500, holder.state.value.movies.loadedPage)
        assertTrue(account.reads.all { it.page in 1..500 })
    }

    @Test fun malformedOrMismatchedPageCannotPublishInventedPageMetadata() = runTest {
        account.read = { LibraryPage(emptyList(), 0, 1) }
        val holder = holder(); runCurrent()
        for (invalidPage in listOf(0, 2, 501)) {
            account.read = { LibraryPage(emptyList(), invalidPage, 501) }
            holder.revalidate(); runCurrent()
            assertEquals(SectionState.Error(SectionErrorKind.Generic), holder.state.value.movies.section)
            assertNull(holder.state.value.movies.loadedPage)
            assertEquals(identity.value, holder.state.value.sourceIdentity)
        }
        account.read = { it.result(1) }
        holder.retry(MediaType.Movie); runCurrent()
        account.read = { LibraryPage(emptyList(), 3, 3) }
        holder.loadMore(MediaType.Movie); runCurrent()
        assertEquals(listOf(1), holder.state.value.movies.ids())
        assertEquals(1, holder.state.value.movies.loadedPage)
        assertEquals(SectionErrorKind.Generic, holder.state.value.movies.moreError)
    }

    @Test fun guestUpdatesRetriesAndTabChangesRemainUnpagedAndAccountExitResetsPages() = runTest {
        identity.value = null
        guest.write(title(3), LibraryKind.WATCHLIST, LibraryState(true, true, false, null, emptySet()))
        val holder = holder(); runCurrent()
        assertTrue(holder.state.value.sourceIdentityKnown)
        assertNull(holder.state.value.sourceIdentity)
        assertEquals(listOf(3), holder.state.value.movies.ids())
        holder.loadMore(MediaType.Movie); holder.retry(MediaType.Movie); runCurrent()
        guest.write(title(4, MediaType.Tv), LibraryKind.WATCHLIST, LibraryState(true, true, false, null, emptySet()))
        runCurrent()
        assertEquals(listOf(4), holder.state.value.tv.ids())
        assertNull(holder.state.value.movies.loadedPage)
        assertNull(holder.state.value.tv.loadedPage)
        holder.selectTab(LibraryTab.Favourites); runCurrent()
        assertEquals(SectionState.Empty, holder.state.value.movies.section)
        assertNull(holder.state.value.movies.loadedPage)
        assertTrue(account.reads.isEmpty())
        identity.value = session(); runCurrent()
        holder.loadMore(MediaType.Movie); runCurrent()
        assertEquals(2, holder.state.value.movies.loadedPage)
        identity.value = null; runCurrent()
        assertTrue(holder.state.value.sourceIdentityKnown)
        assertNull(holder.state.value.sourceIdentity)
        assertFalse(holder.state.value.signedIn)
        assertEquals(SectionState.Empty, holder.state.value.movies.section)
        assertNull(holder.state.value.movies.loadedPage)
        assertNull(holder.state.value.tv.loadedPage)
    }
}
