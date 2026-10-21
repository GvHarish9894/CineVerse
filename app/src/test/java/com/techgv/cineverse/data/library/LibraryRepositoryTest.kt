package com.techgv.cineverse.data.library

import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepositoryTest {
    private val api = FakeAccountApi()
    private val dao = FakeLibraryDao()
    private val identity = MutableStateFlow<Credentials?>(credentials())
    private var clock = 0L
    private val repo = LibraryRepository(identity, GuestLibrarySource(dao) { ++clock }, AccountLibrarySource(tmdbRepository(api)))
    private val movie = title(1)

    /** Latest emitted state of [movie], collected for the whole test. */
    private fun TestScope.watchState(): () -> LibraryState {
        var latest = LibraryState.Unknown
        backgroundScope.launch { repo.state(movie.ref).collect { latest = it } }
        runCurrent()
        return { latest }
    }

    @Test
    fun `guest writes go to Room, newest first, and need no network`() = runTest {
        identity.value = null
        val state = watchState()
        repo.toggleWatchlist(movie)
        repo.toggleWatchlist(title(2, MediaType.Movie))
        repo.rate(movie, 8.5)
        runCurrent()
        assertEquals(LibraryState(known = true, watchlist = true, favourite = false, rating = 8.5, pending = emptySet()), state())
        assertEquals(listOf(2, 1), repo.guestEntries(LibraryKind.WATCHLIST, MediaType.Movie).first().map { it.title.ref.id })
        assertEquals(8.5, repo.guestEntries(LibraryKind.RATING, MediaType.Movie).first().single().rating)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `signed-in state is unknown until account_states loads`() = runTest {
        val gate = CompletableDeferred<Unit>().also { api.statesGates += it }
        val state = watchState()
        assertFalse(state().known)
        gate.complete(Unit)
        runCurrent()
        assertTrue(state().known)
    }

    @Test
    fun `writes per title and kind are serialized and pending while in flight`() = runTest {
        val state = watchState()
        api.writeGate = CompletableDeferred()
        val first = async { repo.toggleWatchlist(movie) }
        val second = async { repo.toggleWatchlist(movie) }
        runCurrent()
        assertEquals(listOf("watchlist/movie:1=true"), api.writes) // the second waits for the first
        assertEquals(setOf(LibraryKind.WATCHLIST), state().pending)
        assertTrue(state().watchlist) // optimistic
        api.writeGate!!.complete(Unit)
        first.await()
        second.await()
        runCurrent()
        assertEquals(listOf("watchlist/movie:1=true", "watchlist/movie:1=false"), api.writes)
        assertEquals(LibraryState(known = true, watchlist = false, favourite = false, rating = null, pending = emptySet()), state())
    }

    @Test
    fun `offline write fails visibly and rolls back`() = runTest {
        val state = watchState()
        api.failWrites = 1
        val result = repo.toggleFavourite(movie)
        runCurrent()
        assertEquals(LibraryWriteResult.Failed(TmdbError.Network), result)
        assertFalse(state().favourite)
        assertTrue(state().pending.isEmpty())
    }

    @Test
    fun `rollback never overrides a later success observed while the write was in flight`() = runTest {
        val state = watchState()
        api.writeGate = CompletableDeferred()
        api.failWrites = 1
        api.landThenFail = true
        val write = async { repo.toggleWatchlist(movie) }
        runCurrent()
        // The POST lands on TMDB but its response is lost; a fresh read sees the success first.
        api.watchlist += movie.ref
        val reread = watchState()
        assertTrue(reread().watchlist)
        api.writeGate!!.complete(Unit)
        assertTrue(write.await() is LibraryWriteResult.Failed)
        runCurrent()
        assertTrue("rolled back to the newer confirmed value, not the pre-write one", state().watchlist)
        assertTrue(state().pending.isEmpty())
    }

    @Test
    fun `a read started before a successful write never commits after it`() = runTest {
        val state = watchState() // known, not on the watchlist
        val staleGate = CompletableDeferred<Unit>().also { api.statesGates += it }
        watchState() // a second screen starts a read that will answer "not on watchlist"
        repo.toggleWatchlist(movie) // succeeds; its own post-write read (ungated) commits first
        runCurrent()
        assertTrue(state().watchlist)
        staleGate.complete(Unit)
        runCurrent()
        assertTrue("the stale read is dropped by the generation check", state().watchlist)
        assertTrue(state().pending.isEmpty())
    }

    @Test
    fun `rating reports removal from the watchlist only when observed`() = runTest {
        watchState()
        api.watchlist += movie.ref
        repo.toggleFavourite(movie) // any write refreshes states: watchlist now known true
        api.rateRemovesFromWatchlist = true
        val removed = repo.rate(movie, 9.0) as LibraryWriteResult.Done
        assertTrue(removed.removedFromWatchlist)
        assertFalse(removed.state.watchlist)
        assertEquals(9.0, removed.state.rating)

        api.rateRemovesFromWatchlist = false
        api.watchlist += movie.ref
        repo.toggleFavourite(movie)
        val kept = repo.rate(movie, 6.5) as LibraryWriteResult.Done
        assertFalse(kept.removedFromWatchlist)
        assertEquals(listOf("rate/movie:1=9.0", "rate/movie:1=6.5"), api.writes.filter { it.startsWith("rate") })
    }

    @Test
    fun `a result for a signed-out identity is dropped`() = runTest {
        val state = watchState()
        api.writeGate = CompletableDeferred()
        val write = async { repo.toggleWatchlist(movie) }
        runCurrent()
        identity.value = credentials(aoid = "other", session = "s2")
        runCurrent() // the new identity reads its own states: not on its watchlist
        api.writeGate!!.complete(Unit)
        write.await()
        runCurrent()
        assertFalse("the old identity's success and post-write read are dropped", state().watchlist)
    }

    @Test
    fun `mutation bumps the list revision`() = runTest {
        watchState()
        val before = repo.listRevision.value
        repo.toggleWatchlist(movie)
        assertTrue(repo.listRevision.value > before)
    }
}
