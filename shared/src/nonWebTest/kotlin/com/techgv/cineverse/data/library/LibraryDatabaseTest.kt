package com.techgv.cineverse.data.library

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
}
