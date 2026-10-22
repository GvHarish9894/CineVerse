package com.techgv.cineverse.data.library

import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Room actuals of the library storage contracts over `library.db` (columns: media_type = MediaType.path, kind =
// LibraryKind name, ledger state "attempting" / "done"). A row with an unknown media type or kind is skipped.

class RoomGuestLibraryStore(private val dao: LibraryDao) : GuestLibraryStore {
    override fun observeKind(kind: LibraryKind, media: MediaType): Flow<List<GuestLibraryRow>> =
        dao.observeKind(kind.name, media.path).map { rows -> rows.mapNotNull { it.toRow() } }

    override fun observeTitle(ref: MediaRef): Flow<List<GuestLibraryRow>> =
        dao.observeTitle(ref.mediaType.path, ref.id).map { rows -> rows.mapNotNull { it.toRow() } }

    override suspend fun all(): List<GuestLibraryRow> = dao.all().mapNotNull { it.toRow() }

    override suspend fun upsert(row: GuestLibraryRow) =
        dao.upsert(LibraryItemEntity(row.ref.mediaType.path, row.ref.id, row.kind.name, row.value, row.addedAt, row.titleJson))

    override suspend fun delete(ref: MediaRef, kind: LibraryKind) = dao.delete(ref.mediaType.path, ref.id, kind.name)

    private fun LibraryItemEntity.toRow(): GuestLibraryRow? {
        val media = MediaType.entries.firstOrNull { it.path == mediaType } ?: return null
        val libraryKind = LibraryKind.entries.firstOrNull { it.name == kind } ?: return null
        return GuestLibraryRow(MediaRef(media, mediaId), libraryKind, value, addedAt, titleJson)
    }
}

/** Ledger and offers live in the same database (tables `import_ledger`, `import_offer`). */
class RoomImportStore(private val dao: ImportLedgerDao) : ImportLedgerStore, ImportOfferStore {
    override suspend fun state(accountObjectId: String, ref: MediaRef, kind: LibraryKind): ImportStepState? =
        when (dao.state(accountObjectId, ref.mediaType.path, ref.id, kind.name)) {
            ImportLedgerEntity.ATTEMPTING -> ImportStepState.Attempting
            ImportLedgerEntity.DONE -> ImportStepState.Done
            else -> null
        }

    override suspend fun put(accountObjectId: String, ref: MediaRef, kind: LibraryKind, state: ImportStepState) {
        val value = when (state) {
            ImportStepState.Attempting -> ImportLedgerEntity.ATTEMPTING
            ImportStepState.Done -> ImportLedgerEntity.DONE
        }
        dao.put(ImportLedgerEntity(accountObjectId, ref.mediaType.path, ref.id, kind.name, value))
    }

    override suspend fun offer(accountObjectId: String): ImportOfferRecord? =
        dao.offer(accountObjectId)?.let { ImportOfferRecord(it.accountObjectId, it.imported, it.complete) }

    override suspend fun put(offer: ImportOfferRecord) =
        dao.putOffer(ImportOfferEntity(offer.accountObjectId, offer.imported, offer.complete))
}
