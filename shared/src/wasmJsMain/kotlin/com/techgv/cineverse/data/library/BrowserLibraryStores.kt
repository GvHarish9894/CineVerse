package com.techgv.cineverse.data.library

import com.techgv.cineverse.data.storage.BrowserChangeTopic
import com.techgv.cineverse.data.storage.BrowserChanges
import com.techgv.cineverse.data.storage.BrowserDatabase
import com.techgv.cineverse.data.storage.BrowserStorageException
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.storage.decodeStorage
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class BrowserGuestLibraryStore(private val database: BrowserDatabase, private val changes: BrowserChanges) : GuestLibraryStore {
    private fun snapshots(): Flow<List<GuestLibraryRow>> = changes.changes
        .map { it.library to it.revalidate }.distinctUntilChanged().map { all() }

    override fun observeKind(kind: LibraryKind, media: MediaType): Flow<List<GuestLibraryRow>> =
        snapshots().map { rows -> rows.filter { it.kind == kind && it.ref.mediaType == media } }.distinctUntilChanged()

    override fun observeTitle(ref: MediaRef): Flow<List<GuestLibraryRow>> =
        snapshots().map { rows -> rows.filter { it.ref == ref } }.distinctUntilChanged()

    override suspend fun all(): List<GuestLibraryRow> = decodeStorage<List<String>>(database.all("guest"))
        .map { decodeStorage<StoredGuestRow>(it).row() }.sortedByDescending { it.addedAt }

    override suspend fun upsert(row: GuestLibraryRow) {
        val stored = StoredGuestRow(row.ref.mediaType.path, row.ref.id, row.kind.name, row.value, row.addedAt, row.titleJson)
        database.edit("guest", key(row.ref, row.kind)) { Json.encodeToString(stored) }
        changes.publish(BrowserChangeTopic.Library)
    }

    override suspend fun delete(ref: MediaRef, kind: LibraryKind) {
        database.edit("guest", key(ref, kind)) { null }
        changes.publish(BrowserChangeTopic.Library)
    }

    @Serializable
    private data class StoredGuestRow(val media: String, val id: Int, val kind: String, val value: Double?, val addedAt: Long, val titleJson: String) {
        fun row(): GuestLibraryRow = try {
            GuestLibraryRow(MediaRef(MediaType.entries.first { it.path == media }, id), LibraryKind.valueOf(kind), value, addedAt, titleJson)
        } catch (_: Exception) { throw BrowserStorageException(BrowserStorageFailure.Corrupt) }
    }
}

/** Ledger and offers share the root database, but remain isolated by complete destination-account keys. */
class BrowserImportStore(private val database: BrowserDatabase) : ImportLedgerStore, ImportOfferStore {
    override suspend fun state(accountObjectId: String, ref: MediaRef, kind: LibraryKind): ImportStepState? {
        val stored = database.read("ledger", key(ref, kind, accountObjectId)) ?: return null
        return try { ImportStepState.valueOf(decodeStorage<String>(stored)) }
        catch (_: Exception) { throw BrowserStorageException(BrowserStorageFailure.Corrupt) }
    }

    override suspend fun put(accountObjectId: String, ref: MediaRef, kind: LibraryKind, state: ImportStepState) {
        database.edit("ledger", key(ref, kind, accountObjectId)) { Json.encodeToString(state.name) }
    }

    override suspend fun offer(accountObjectId: String): ImportOfferRecord? {
        val stored = database.read("offers", accountObjectId) ?: return null
        val offer = decodeStorage<StoredOffer>(stored)
        return ImportOfferRecord(accountObjectId, offer.imported, offer.complete)
    }

    override suspend fun put(offer: ImportOfferRecord) {
        database.edit("offers", offer.accountObjectId) { Json.encodeToString(StoredOffer(offer.imported, offer.complete)) }
    }

    @Serializable
    private data class StoredOffer(val imported: Boolean, val complete: Boolean)
}

private fun key(ref: MediaRef, kind: LibraryKind, account: String? = null): String =
    Json.encodeToString(listOf(account, ref.mediaType.path, ref.id.toString(), kind.name))
