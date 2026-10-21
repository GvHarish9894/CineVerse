package com.techgv.cineverse.data.library

import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryImporterTest {
    private val api = FakeAccountApi()
    private val dao = FakeLibraryDao()
    private val ledger = FakeImportLedgerDao()
    private val account = credentials()
    private var clock = 0L
    private val guest = GuestLibrarySource(dao) { ++clock }
    private val accountSource = AccountLibrarySource(tmdbRepository(api))
    private val identity = MutableStateFlow<Credentials?>(account)
    private val repo = LibraryRepository(identity, guest, accountSource)
    private val importer = LibraryImporter(ledger, guest, accountSource, repo)

    private suspend fun guestHas(title: Title, watchlist: Boolean = false, favourite: Boolean = false, rating: Double? = null) {
        val target = LibraryState(known = true, watchlist = watchlist, favourite = favourite, rating = rating, pending = emptySet())
        if (watchlist) guest.write(title, LibraryKind.WATCHLIST, target)
        if (favourite) guest.write(title, LibraryKind.FAVOURITE, target)
        if (rating != null) guest.write(title, LibraryKind.RATING, target)
    }

    private fun ledgerState(ref: MediaRef, kind: LibraryKind) = ledger.entries[listOf("aoid", ref.mediaType.path, ref.id, kind.name)]

    /** Every emitted button state of [ref], collected for the whole test. */
    private fun TestScope.watchState(ref: MediaRef): List<LibraryState> {
        val history = mutableListOf<LibraryState>()
        backgroundScope.launch { repo.state(ref).collect { history += it } }
        runCurrent()
        return history
    }

    private fun ledgerAttempting(ref: MediaRef, kind: LibraryKind) {
        ledger.entries[listOf("aoid", ref.mediaType.path, ref.id, kind.name)] = ImportLedgerEntity.ATTEMPTING
    }

    @Test
    fun `offered once per account and only with guest data`() = runTest {
        assertNull(importer.offer(account))
        guestHas(title(1), watchlist = true, rating = 7.0)
        assertEquals(ImportOffer.Ask(1), importer.offer(account))
        importer.keepSeparate(account)
        assertNull(importer.offer(account))
        assertEquals(ImportOffer.Ask(1), importer.offer(credentials(aoid = "another")))
    }

    @Test
    fun `reads all pages of the six lists before any write, and aborts on a read failure`() = runTest {
        api.pageSize = 1
        api.watchlist += listOf(MediaRef(MediaType.Movie, 10), MediaRef(MediaType.Movie, 11))
        guestHas(title(1), watchlist = true)
        api.failListPage = 2
        val aborted = importer.import(account)
        assertTrue(aborted is ImportResult.Aborted)
        assertTrue("nothing written", api.writes.isEmpty())
        assertEquals(ImportOffer.Resume(1), importer.offer(account))

        api.failListPage = null
        api.calls.clear()
        importer.import(account)
        val firstWrite = api.calls.indexOfFirst { !it.startsWith("list") && !it.startsWith("states") }
        val reads = api.calls.take(firstWrite).filter { it.startsWith("list") }
        assertEquals(
            setOf("movie/watchlist", "movie/favorites", "movie/rated", "tv/watchlist", "tv/favorites", "tv/rated"),
            reads.map { it.split("/").let { p -> "${p[1]}/${p[2]}" } }.toSet(),
        )
        assertTrue("list/movie/watchlist/2" in reads)
        assertEquals("watchlist/movie:1=true", api.calls[firstWrite])
    }

    @Test
    fun `union for flags, TMDB rating wins, order rating then favourite then watchlist`() = runTest {
        val kept = title(1)
        val fresh = title(2, MediaType.Tv)
        api.ratings[kept.ref] = 9.0
        api.watchlist += kept.ref
        guestHas(kept, watchlist = true, favourite = true, rating = 4.0)
        guestHas(fresh, watchlist = true, favourite = true, rating = 6.5)

        val result = importer.import(account) as ImportResult.Done

        assertEquals(9.0, api.ratings[kept.ref]) // TMDB's rating wins
        assertEquals(6.5, api.ratings[fresh.ref])
        assertEquals(
            // Newest guest title first; per title rating → favourite → watchlist; satisfied steps are skipped.
            listOf("rate/tv:2=6.5", "favorite/tv:2=true", "watchlist/tv:2=true", "favorite/movie:1=true"),
            api.writes,
        )
        assertEquals(4, result.written)
        assertTrue(result.unconfirmed.isEmpty())
        assertEquals(ImportLedgerEntity.DONE, ledgerState(kept.ref, LibraryKind.RATING)) // skipped as satisfied
        assertNull(importer.offer(account)) // complete
        assertEquals("guest rows are retained", 6, dao.rows.value.size)
    }

    @Test
    fun `an unconfirmed rating blocks that title's watchlist write`() = runTest {
        val t = title(1)
        guestHas(t, watchlist = true, rating = 8.0)
        api.failWrites = 1
        val result = importer.import(account) as ImportResult.Done
        assertEquals(listOf(ImportStep(t.ref, LibraryKind.RATING)), result.unconfirmed)
        assertEquals(listOf("rate/movie:1=8.0"), api.writes)
        assertEquals(ImportLedgerEntity.ATTEMPTING, ledgerState(t.ref, LibraryKind.RATING))
    }

    @Test
    fun `unknown outcome that actually landed is confirmed by account_states`() = runTest {
        val t = title(1)
        guestHas(t, watchlist = true, rating = 8.0)
        api.failWrites = 1
        api.landThenFail = true
        val result = importer.import(account) as ImportResult.Done
        assertTrue(result.unconfirmed.isEmpty())
        assertEquals(listOf("rate/movie:1=8.0", "watchlist/movie:1=true"), api.writes)
    }

    /** Crash window: POST succeeded → process died before `done` → user changed the rating on TMDB. */
    @Test
    fun `resume reconciles an attempting step through account_states without writing`() = runTest {
        val t = title(1)
        guestHas(t, watchlist = true, rating = 8.0)
        ledgerAttempting(t.ref, LibraryKind.RATING)
        api.ratings[t.ref] = 5.5 // edited on TMDB after our crashed POST

        val result = importer.import(account) as ImportResult.Done

        assertEquals(5.5, api.ratings[t.ref])
        assertEquals(listOf("watchlist/movie:1=true"), api.writes) // no rating write; the title proceeds
        assertTrue(result.unconfirmed.isEmpty())
        assertEquals(ImportLedgerEntity.DONE, ledgerState(t.ref, LibraryKind.RATING))
        assertTrue(api.calls.contains("states/movie:1"))
    }

    /** Ambiguous: a prior attempt's outcome is unknown and TMDB now lacks the flag (maybe removed remotely). */
    @Test
    fun `ambiguous removal is not resumed automatically, only by an explicit retry`() = runTest {
        val t = title(1)
        guestHas(t, watchlist = true)
        ledgerAttempting(t.ref, LibraryKind.WATCHLIST)

        val result = importer.import(account) as ImportResult.Done

        assertTrue(api.writes.isEmpty())
        assertEquals(listOf(ImportStep(t.ref, LibraryKind.WATCHLIST)), result.unconfirmed)
        assertEquals(listOf("Title 1"), result.unconfirmedTitles)
        assertEquals(ImportOffer.Resume(1), importer.offer(account))

        val retried = importer.import(account, retry = result.unconfirmed.toSet()) as ImportResult.Done
        assertEquals(listOf("watchlist/movie:1=true"), api.writes)
        assertTrue(retried.unconfirmed.isEmpty())
        assertEquals(ImportLedgerEntity.DONE, ledgerState(t.ref, LibraryKind.WATCHLIST))
    }

    /** D6: TMDB drops a rated title from the watchlist; the snapshot saw it there, the union must still hold. */
    @Test
    fun `an existing remote watchlist entry survives a new local rating that removes it`() = runTest {
        val t = title(1)
        api.watchlist += t.ref
        api.rateRemovesFromWatchlist = true
        guestHas(t, watchlist = true, rating = 8.0)

        importer.import(account)

        assertEquals(8.0, api.ratings[t.ref])
        assertTrue("watchlist re-checked after the rating side effect", t.ref in api.watchlist)
        assertEquals(listOf("rate/movie:1=8.0", "watchlist/movie:1=true"), api.writes)
    }

    @Test
    fun `a user rating committed while the import waits for the lock is not overwritten`() = runTest {
        val t = title(1)
        guestHas(t, rating = 4.0)
        api.writeGate = CompletableDeferred()
        val user = async { repo.rate(t, 9.0, account) }
        runCurrent() // the user's POST is in flight, holding the (title, rating) lock
        val import = async { importer.import(account) }
        runCurrent() // the snapshot has no rating; the rating step waits for the lock
        api.writeGate!!.complete(Unit)
        user.await()
        import.await()

        assertEquals("TMDB's rating at execution time wins", 9.0, api.ratings[t.ref])
        assertEquals(listOf("rate/movie:1=9.0"), api.writes)
    }

    @Test
    fun `preflight reads the account lists from TMDB, not a warm cache`() = runTest {
        accountSource.page(account, LibraryKind.WATCHLIST, MediaType.Movie, 1) // warms the 10-min list cache
        guestHas(title(1), favourite = true)
        api.calls.clear()
        importer.import(account)
        assertTrue("list/movie/watchlist/1" in api.calls)
    }

    @Test
    fun `an active collector shows the imported state`() = runTest {
        val t = title(1)
        guestHas(t, watchlist = true)
        val history = watchState(t.ref)
        assertTrue(history.last().known)
        assertFalse(history.last().watchlist)

        importer.import(account)
        runCurrent()

        assertTrue("refetched, not left Unknown", history.last().known)
        assertTrue(history.last().watchlist)
    }

    @Test
    fun `an import write that lands then errors still revalidates`() = runTest {
        val t = title(1)
        guestHas(t, watchlist = true)
        val history = watchState(t.ref)
        api.failWrites = 1
        api.landThenFail = true
        val before = repo.listRevision.value

        val result = importer.import(account) as ImportResult.Done
        runCurrent()

        assertEquals("confirmed by account_states, not counted as written", 0, result.written)
        assertTrue(repo.listRevision.value > before)
        assertTrue(history.last().watchlist)
    }

    @Test
    fun `a cancelled old-account import does not touch the new account`() = runTest {
        val waiting = title(1)
        guestHas(waiting, watchlist = true)
        guestHas(title(2), watchlist = true) // newest: imported first
        api.statesGates += CompletableDeferred(Unit) // title 2's in-lock read answers ...
        api.statesGates += CompletableDeferred() // ... title 1's never does
        val import = launch { importer.import(account) }
        runCurrent()
        assertEquals(listOf("watchlist/movie:2=true"), api.writes)
        identity.value = credentials(aoid = "b", session = "sb")
        val history = watchState(waiting.ref)
        assertTrue(history.last().known)
        val emitted = history.size

        import.cancel()
        runCurrent()

        assertEquals("B's states neither cleared nor refetched", emitted, history.size)
    }
}
