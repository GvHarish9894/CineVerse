package com.techgv.cineverse.data.library

import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    private val repo = LibraryRepository(MutableStateFlow(account), guest, accountSource)
    private val importer = LibraryImporter(ledger, guest, accountSource, repo)

    private suspend fun guestHas(title: Title, watchlist: Boolean = false, favourite: Boolean = false, rating: Double? = null) {
        val target = LibraryState(known = true, watchlist = watchlist, favourite = favourite, rating = rating, pending = emptySet())
        if (watchlist) guest.write(title, LibraryKind.WATCHLIST, target)
        if (favourite) guest.write(title, LibraryKind.FAVOURITE, target)
        if (rating != null) guest.write(title, LibraryKind.RATING, target)
    }

    private fun ledgerState(ref: MediaRef, kind: LibraryKind) = ledger.entries[listOf("aoid", ref.mediaType.path, ref.id, kind.name)]

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
        assertEquals(ImportOffer.Resume, importer.offer(account))

        api.failListPage = null
        api.calls.clear()
        importer.import(account)
        val firstWrite = api.calls.indexOfFirst { !it.startsWith("list") }
        val reads = api.calls.take(firstWrite)
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
        assertEquals(ImportOffer.Resume, importer.offer(account))

        val retried = importer.import(account, retry = result.unconfirmed.toSet()) as ImportResult.Done
        assertEquals(listOf("watchlist/movie:1=true"), api.writes)
        assertTrue(retried.unconfirmed.isEmpty())
        assertEquals(ImportLedgerEntity.DONE, ledgerState(t.ref, LibraryKind.WATCHLIST))
    }
}
