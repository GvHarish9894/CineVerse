package com.techgv.cineverse.data.library

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.presentation.FakeAccountLibrary
import com.techgv.cineverse.presentation.InMemoryGuestLibraryStore
import com.techgv.cineverse.presentation.session
import com.techgv.cineverse.presentation.title
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real repository reads over fake producers; no origin is inferred from a UI session label. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryStateOriginTest {
    private class GuestStore(val memory: InMemoryGuestLibraryStore = InMemoryGuestLibraryStore()) : GuestLibraryStore by memory {
        var subscriptions = 0
        var failure: StorageException? = null
        var observe: (MediaRef) -> Flow<List<GuestLibraryRow>> = memory::observeTitle
        override fun observeTitle(ref: MediaRef) = flow {
            subscriptions++
            failure?.let { throw it }
            emitAll(observe(ref))
        }
    }

    private class Account(val remote: FakeAccountLibrary = FakeAccountLibrary()) : AccountLibrarySource by remote {
        val reads = mutableListOf<AuthSession>()
        var read: suspend (AuthSession, MediaRef) -> LibraryState = remote::states
        var write: suspend (AuthSession, MediaRef, LibraryKind, LibraryState) -> Unit = remote::write
        override suspend fun states(session: AuthSession, ref: MediaRef): LibraryState {
            reads += session
            return read(session, ref)
        }
        override suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState) =
            write.invoke(session, ref, kind, target)
    }

    private val a = session("a", "Same name", Long.MAX_VALUE)
    private val b = session("b", "Same name", Long.MAX_VALUE)
    private val identity = MutableStateFlow<AuthSession?>(a)
    private val store = GuestStore()
    private val guest = GuestLibrarySource(store) { 0L }
    private val account = Account()
    private val repository = LibraryRepository(identity, guest, account)
    private val movie = title(1)
    private val empty = LibraryState(true, false, false, null, emptySet())
    private val aFlags = empty.copy(watchlist = true, rating = 3.5)
    private val bFlags = empty.copy(favourite = true, rating = 8.0)

    private fun TestScope.watch(repo: LibraryRepository = repository): MutableList<LibraryStateSnapshot> {
        val history = mutableListOf<LibraryStateSnapshot>()
        backgroundScope.launch { repo.stateWithOrigin(movie.ref).collect { history += it } }
        runCurrent()
        return history
    }

    private fun LibraryStateSnapshot.assertOrigin(expected: AuthSession?, expectedState: LibraryState) {
        assertTrue(sourceIdentityKnown)
        assertEquals(expected, sourceIdentity)
        assertEquals(expectedState, state)
    }

    @Test fun manuallyConstructedSnapshotsDoNotInventSourceProof() {
        for (state in listOf(LibraryState.Unknown, empty, aFlags)) {
            val snapshot = LibraryStateSnapshot(state)
            assertFalse(snapshot.sourceIdentityKnown)
            assertNull(snapshot.sourceIdentity)
            assertEquals(state, snapshot.state)
        }
    }

    @Test fun guestOriginRequiresAnActualGuestEmissionIncludingEmptyRows() = runTest {
        identity.value = null
        val rows = MutableSharedFlow<List<GuestLibraryRow>>()
        store.observe = { rows }
        val history = watch()
        assertTrue(history.isEmpty(), "guest session alone proves no guest data")
        assertEquals(1, store.subscriptions)
        rows.emit(emptyList()); runCurrent()
        history.last().assertOrigin(null, empty)
        rows.emit(listOf(GuestLibraryRow(movie.ref, LibraryKind.WATCHLIST, null, 0L, "{}"))); runCurrent()
        history.last().assertOrigin(null, empty.copy(watchlist = true))
        assertTrue(account.reads.isEmpty())
    }

    @Test fun anUnobservedSessionDoesNotDefaultToGuest() = runTest {
        val sessions = MutableSharedFlow<AuthSession?>()
        val history = watch(LibraryRepository(sessions, guest, account))
        assertTrue(history.isEmpty())
        assertEquals(0, store.subscriptions)
        assertTrue(account.reads.isEmpty())
        sessions.emit(null); runCurrent()
        history.last().assertOrigin(null, empty)
    }

    @Test fun guestStorageFailurePropagatesAndRecoveryRequiresAnActualNewSubscription() = runTest {
        identity.value = null
        val failure = StorageException("guest fixture")
        store.failure = failure
        val history = mutableListOf<LibraryStateSnapshot>()
        val failures = mutableListOf<Throwable>()
        backgroundScope.launch {
            repository.stateWithOrigin(movie.ref).catch { failures += it }.collect { history += it }
        }
        runCurrent()
        assertSame(failure, failures.single())
        assertTrue(history.isEmpty(), "no empty guest success or proof on storage failure")
        store.failure = null
        runCurrent()
        assertTrue(history.isEmpty(), "failed cold reader does not recover by changing a fixture flag")
        val recovered = watch()
        recovered.last().assertOrigin(null, empty)
        assertEquals(2, store.subscriptions)
    }

    @Test fun legacyGuestReaderPreservesTheSameTypedStorageFailure() = runTest {
        identity.value = null
        val failure = StorageException("legacy guest fixture")
        store.failure = failure
        val failures = mutableListOf<Throwable>()
        val states = mutableListOf<LibraryState>()
        backgroundScope.launch { repository.state(movie.ref).catch { failures += it }.collect { states += it } }
        runCurrent()
        assertSame(failure, failures.single())
        assertTrue(states.isEmpty())
    }

    @Test fun accountReadFailureStaysUnknownAndANewCollectionActuallyReadsAgain() = runTest {
        val gate = CompletableDeferred<Unit>()
        account.read = { _, _ -> gate.await(); throw TmdbException(TmdbError.Network) }
        val history = watch()
        history.last().assertOrigin(a, LibraryState.Unknown)
        gate.complete(Unit); runCurrent()
        history.last().assertOrigin(a, LibraryState.Unknown)
        account.read = { _, _ -> aFlags }
        val recovered = watch()
        recovered.last().assertOrigin(a, aFlags)
        history.last().assertOrigin(a, aFlags)
        assertEquals(listOf(a, a), account.reads)
    }

    @Test fun accountStorageFailurePropagatesWithoutFabricatingKnownFlagsAndResubscribes() = runTest {
        val failure = StorageException("account fixture")
        account.read = { _, _ -> throw failure }
        val failures = mutableListOf<Throwable>()
        val history = mutableListOf<LibraryStateSnapshot>()
        backgroundScope.launch {
            repository.stateWithOrigin(movie.ref).catch { failures += it }.collect { history += it }
        }
        runCurrent()
        assertSame(failure, failures.single())
        assertTrue(history.all { !it.state.known })
        account.read = { _, _ -> aFlags }
        runCurrent()
        assertTrue(history.all { !it.state.known })
        watch().last().assertOrigin(a, aFlags)
        assertEquals(listOf(a, a), account.reads)
    }

    @Test fun sessionStorageFailureDoesNotEmitGuestProof() = runTest {
        val failure = StorageException("session fixture")
        val sessions = flow<AuthSession?> { throw failure }
        val repo = LibraryRepository(sessions, guest, account)
        val failures = mutableListOf<Throwable>()
        val history = mutableListOf<LibraryStateSnapshot>()
        backgroundScope.launch { repo.stateWithOrigin(movie.ref).catch { failures += it }.collect { history += it } }
        runCurrent()
        assertSame(failure, failures.single())
        assertTrue(history.isEmpty())
        assertEquals(0, store.subscriptions)
        assertTrue(account.reads.isEmpty())
    }

    @Test fun differentAccountsWithTheSameDisplayNameKeepDistinctOrigins() = identityReplacement(b)

    @Test fun sameAccountWithANewOpaqueGenerationKeepsADistinctOrigin() =
        identityReplacement(a.copy(sessionGeneration = Long.MIN_VALUE))

    private fun identityReplacement(replacement: AuthSession) = runTest {
        val gate = CompletableDeferred<Unit>()
        account.read = { c, _ -> if (c == a) aFlags else { gate.await(); bFlags } }
        val history = watch()
        val original = history.last()
        original.assertOrigin(a, aFlags)
        identity.value = replacement; runCurrent()
        history.last().assertOrigin(replacement, LibraryState.Unknown)
        gate.complete(Unit); runCurrent()
        history.last().assertOrigin(replacement, bFlags)
        original.assertOrigin(a, aFlags)
        assertNotEquals(original.sourceIdentity, history.last().sourceIdentity)
        assertEquals(listOf(a, replacement), account.reads)
    }

    @Test fun independentlyScheduledAuthorityAndReadProducersNeverRelabelOldFlags() = runTest {
        val sessions = MutableSharedFlow<AuthSession?>(replay = 1)
        sessions.emit(a)
        val ready = MutableStateFlow<AuthSession?>(a)
        account.read = { c, _ -> if (c == a) aFlags else bFlags }
        val history = watch(LibraryRepository(sessions, guest, account))
        val original = history.last()
        ready.value = b; runCurrent() // authority moves before the actual read producer
        history.last().assertOrigin(a, aFlags)
        assertNotEquals(ready.value, history.last().sourceIdentity)
        sessions.emit(b); runCurrent()
        history.last().assertOrigin(b, bFlags)
        sessions.emit(null); runCurrent() // data producer moves before authority
        history.last().assertOrigin(null, empty)
        assertNotEquals(ready.value, history.last().sourceIdentity)
        ready.value = null
        original.assertOrigin(a, aFlags)
        assertTrue(history.none { it.sourceIdentity == b && it.state == aFlags })
    }

    @Test fun cooperativeBlockedOldReadCannotBecomeNewAccountData() = oldBlockedRead(resistCancellation = false)

    @Test fun cancellationResistantOldReadMayDelaySwitchButCannotBecomeNewAccountData() = oldBlockedRead(resistCancellation = true)

    private fun oldBlockedRead(resistCancellation: Boolean) = runTest {
        val gate = CompletableDeferred<Unit>()
        account.read = { c, _ ->
            if (c == a) {
                if (resistCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
                aFlags
            } else bFlags
        }
        val history = watch()
        history.last().assertOrigin(a, LibraryState.Unknown)
        identity.value = b; runCurrent()
        if (resistCancellation) {
            assertEquals(listOf(a), account.reads, "legacy flatMapLatest can wait for canceled I/O")
            history.last().assertOrigin(a, LibraryState.Unknown)
        } else history.last().assertOrigin(b, bFlags)
        gate.complete(Unit); runCurrent()
        history.last().assertOrigin(b, bFlags)
        assertTrue(history.none { it.sourceIdentity == b && it.state == aFlags })
    }

    @Test fun mismatchedImmutableStoreIsUnknownAndLateOldReadCannotRelabelItsPayload() = runTest {
        // Two cold session subscribers can temporarily capture different identities during a transition.
        var subscribedIdentity = a
        val sessions = flow<AuthSession?> { emit(subscribedIdentity); awaitCancellation() }
        val repo = LibraryRepository(sessions, guest, account)
        account.read = { c, _ -> if (c == a) aFlags else bFlags }
        val old = watch(repo)
        val original = old.last()
        val gate = CompletableDeferred<Unit>()
        account.read = { c, _ ->
            if (c == a) { withContext(NonCancellable) { gate.await() }; aFlags } else bFlags
        }
        repo.onExternalWrites(a); runCurrent()
        subscribedIdentity = b
        val current = watch(repo)
        old.last().let {
            assertEquals(LibraryState.Unknown, it.state)
            assertFalse(it.sourceIdentityKnown)
            assertNull(it.sourceIdentity)
        }
        current.last().assertOrigin(b, bFlags)
        gate.complete(Unit); runCurrent()
        assertEquals(LibraryStateSnapshot(LibraryState.Unknown), old.last())
        current.last().assertOrigin(b, bFlags)
        original.assertOrigin(a, aFlags)
        assertTrue(current.none { it.sourceIdentity == b && it.state == aFlags })
    }

    @Test fun optimisticPendingPerKindRefreshAndLegacyProjectionShareTheSameReadState() = runTest {
        val history = watch()
        val legacy = mutableListOf<LibraryState>()
        backgroundScope.launch { repository.state(movie.ref).collect { legacy += it } }
        runCurrent()
        history.last().assertOrigin(a, empty)
        assertEquals(history.last().state, legacy.last())
        val gate = CompletableDeferred<Unit>()
        account.remote.writeGate = gate
        val write = async { repository.toggleWatchlist(movie, a) }
        runCurrent()
        val optimistic = empty.copy(watchlist = true, pending = setOf(LibraryKind.WATCHLIST))
        history.last().assertOrigin(a, optimistic)
        assertEquals(optimistic, legacy.last())
        account.remote.favourites += movie.ref
        val reread = watch() // a real new read may confirm another kind without replacing optimistic watchlist
        val refreshed = optimistic.copy(favourite = true)
        history.last().assertOrigin(a, refreshed)
        reread.last().assertOrigin(a, refreshed)
        assertEquals(refreshed, legacy.last())
        gate.complete(Unit)
        assertTrue(write.await() is LibraryWriteResult.Done)
        runCurrent()
        history.last().assertOrigin(a, refreshed.copy(pending = emptySet()))
        assertEquals(history.last().state, legacy.last())
        assertTrue(history.all { it.sourceIdentityKnown && it.sourceIdentity == a })
    }

    @Test fun externalWritesRefetchActiveReadersAndKeepLegacyProjectionEqual() = runTest {
        val history = watch()
        val legacy = mutableListOf<LibraryState>()
        backgroundScope.launch { repository.state(movie.ref).collect { legacy += it } }
        runCurrent()
        val reads = account.reads.size
        val gate = CompletableDeferred<Unit>()
        account.read = { _, _ -> gate.await(); bFlags }
        repository.onExternalWrites(a); runCurrent()
        history.last().assertOrigin(a, LibraryState.Unknown)
        assertEquals(LibraryState.Unknown, legacy.last())
        assertEquals(reads + 2, account.reads.size)
        gate.complete(Unit); runCurrent()
        history.last().assertOrigin(a, bFlags)
        assertEquals(history.last().state, legacy.last())
        assertTrue(account.remote.writes.isEmpty(), "refetch is read-only")
    }

    @Test fun staleReadCannotOverwriteAKindConfirmedByALaterWrite() = runTest {
        val history = watch()
        val gate = CompletableDeferred<Unit>()
        var blockNext = true
        account.read = { c, ref ->
            val captured = account.remote.states(c, ref)
            if (blockNext) { blockNext = false; gate.await() }
            captured
        }
        val stale = watch()
        assertTrue(repository.toggleWatchlist(movie, a) is LibraryWriteResult.Done)
        runCurrent()
        history.last().assertOrigin(a, empty.copy(watchlist = true))
        gate.complete(Unit); runCurrent()
        history.last().assertOrigin(a, empty.copy(watchlist = true))
        stale.last().assertOrigin(a, empty.copy(watchlist = true))
    }

    @Test fun landedThenFailedWriteReconcilesWithoutChangingOriginOrReplaying() = runTest {
        val history = watch()
        account.write = { c, ref, kind, target ->
            account.remote.write(c, ref, kind, target)
            throw TmdbException(TmdbError.Network)
        }
        assertEquals(LibraryWriteResult.Failed(TmdbError.Network), repository.toggleWatchlist(movie, a))
        runCurrent()
        history.last().assertOrigin(a, empty.copy(watchlist = true))
        assertEquals(1, account.remote.writes.size)
        assertTrue(history.last().state.pending.isEmpty())
    }

    @Test fun canceledCollectorDoesNotPublishALateCancellationResistantRead() = runTest {
        val gate = CompletableDeferred<Unit>()
        account.read = { _, _ -> withContext(NonCancellable) { gate.await() }; aFlags }
        val history = mutableListOf<LibraryStateSnapshot>()
        val reader = backgroundScope.launch { repository.stateWithOrigin(movie.ref).collect { history += it } }
        runCurrent()
        val before = history.toList()
        reader.cancel()
        gate.complete(Unit); runCurrent()
        assertEquals(before, history)
        assertTrue(history.all { !it.state.known })
    }
}
