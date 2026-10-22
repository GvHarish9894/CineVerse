package com.techgv.cineverse.data.library

import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import kotlinx.coroutines.flow.Flow

// Storage contracts of the library (§2, §8). nonWeb: Room (`library.db`); web: browser storage.

/** §8.2 guest library row; one per (title, kind). [value] is the rating; [titleJson] the title snapshot. */
data class GuestLibraryRow(val ref: MediaRef, val kind: LibraryKind, val value: Double?, val addedAt: Long, val titleJson: String)

/** §8.2 guest library. Lists are newest first ([GuestLibraryRow.addedAt] descending). */
interface GuestLibraryStore {
    fun observeKind(kind: LibraryKind, media: MediaType): Flow<List<GuestLibraryRow>>
    fun observeTitle(ref: MediaRef): Flow<List<GuestLibraryRow>>
    suspend fun all(): List<GuestLibraryRow>
    /** Inserts or replaces the row with the same (ref, kind). */
    suspend fun upsert(row: GuestLibraryRow)
    suspend fun delete(ref: MediaRef, kind: LibraryKind)
}

/** §8.3 rule 4: a step is [Attempting] (persisted before its POST) or [Done]. */
enum class ImportStepState { Attempting, Done }

/** §8.3 per-account import ledger, keyed by (account_object_id, title, kind). */
interface ImportLedgerStore {
    suspend fun state(accountObjectId: String, ref: MediaRef, kind: LibraryKind): ImportStepState?
    suspend fun put(accountObjectId: String, ref: MediaRef, kind: LibraryKind, state: ImportStepState)
}

/** §8.3 "once per destination account": the answer to the Import dialog and whether the import finished. */
data class ImportOfferRecord(val accountObjectId: String, val imported: Boolean, val complete: Boolean)

interface ImportOfferStore {
    suspend fun offer(accountObjectId: String): ImportOfferRecord?
    suspend fun put(offer: ImportOfferRecord)
}
