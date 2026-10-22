package com.techgv.cineverse.data.library

import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Room's KSP output for `library.db` runs on this target (jvm, iOS; Android runs it in the TV app). */
abstract class LibraryDatabaseTest {
    abstract fun newDatabase(): LibraryDatabase

    private val db by lazy { newDatabase() }

    @AfterTest
    fun close() = db.close()

    private fun item(id: Int, kind: String, addedAt: Long, value: Double? = null) =
        LibraryItemEntity("movie", id, kind, value, addedAt, """{"title":"T$id"}""")

    @Test
    fun guestRowsUpsertObserveNewestFirstAndDelete() = runTest {
        val dao = db.libraryDao()
        dao.upsert(item(1, "WATCHLIST", addedAt = 1))
        dao.upsert(item(2, "WATCHLIST", addedAt = 2))
        dao.upsert(item(1, "RATING", addedAt = 3, value = 7.5))
        dao.upsert(item(1, "RATING", addedAt = 4, value = 8.0)) // same key: replaced

        assertEquals(listOf(2, 1), dao.observeKind("WATCHLIST", "movie").first().map { it.mediaId })
        assertEquals(8.0, dao.observeTitle("movie", 1).first().single { it.kind == "RATING" }.value)
        assertEquals(listOf(4L, 2L, 1L), dao.all().map { it.addedAt })

        dao.delete("movie", 1, "WATCHLIST")
        assertEquals(listOf("RATING"), dao.observeTitle("movie", 1).first().map { it.kind })
    }

    @Test
    fun ledgerAndOffersArePerAccount() = runTest {
        val ledger = db.importLedgerDao()
        ledger.put(ImportLedgerEntity("a", "movie", 1, "RATING", ImportLedgerEntity.ATTEMPTING))
        ledger.put(ImportLedgerEntity("a", "movie", 1, "RATING", ImportLedgerEntity.DONE))
        assertEquals(ImportLedgerEntity.DONE, ledger.state("a", "movie", 1, "RATING"))
        assertNull(ledger.state("b", "movie", 1, "RATING"))

        ledger.putOffer(ImportOfferEntity("a", imported = true, complete = false))
        assertEquals(ImportOfferEntity("a", imported = true, complete = false), ledger.offer("a"))
        assertNull(ledger.offer("b"))
    }

    /** The Room actuals of the storage contracts, under the common GuestLibrarySource / LibraryImporter stores. */
    @Test
    fun guestSourceAndImportStoreOverRealRoom() = runTest {
        val guest = GuestLibrarySource(RoomGuestLibraryStore(db.libraryDao())) { 42L }
        val dune = Title(MediaRef(MediaType.Movie, 438631), "Dune", "", "/p.jpg", null, "2021-09-15", 7.8, 12000, listOf(878), MediaType.Movie)
        guest.write(dune, LibraryKind.RATING, LibraryState(known = true, watchlist = false, favourite = false, rating = 8.0, pending = emptySet()))
        assertEquals(listOf(LibraryEntry(dune, 8.0)), guest.observeEntries(LibraryKind.RATING, MediaType.Movie).first())
        assertEquals(8.0, guest.state(dune.ref).rating)

        val imports = RoomImportStore(db.importLedgerDao())
        imports.put("a", dune.ref, LibraryKind.RATING, ImportStepState.Attempting)
        assertEquals(ImportStepState.Attempting, imports.state("a", dune.ref, LibraryKind.RATING))
        assertEquals("attempting", db.importLedgerDao().state("a", "movie", 438631, "RATING")) // v1 column values
        imports.put(ImportOfferRecord("a", imported = true, complete = false))
        assertEquals(ImportOfferRecord("a", imported = true, complete = false), imports.offer("a"))
    }
}
