package com.techgv.cineverse.data.library

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** §8.2 guest library row; one per (title, kind). [kind] is a `LibraryKind` name, [value] the rating. */
@Entity(tableName = "library_item", primaryKeys = ["media_type", "media_id", "kind"])
data class LibraryItemEntity(
    @ColumnInfo(name = "media_type") val mediaType: String,
    @ColumnInfo(name = "media_id") val mediaId: Int,
    val kind: String,
    val value: Double?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "title_json") val titleJson: String,
)

/** §8.3 rule 4: [state] is [ImportLedgerEntity.ATTEMPTING] before each POST, [ImportLedgerEntity.DONE] after. */
@Entity(tableName = "import_ledger", primaryKeys = ["account_object_id", "media_type", "media_id", "step"])
data class ImportLedgerEntity(
    @ColumnInfo(name = "account_object_id") val accountObjectId: String,
    @ColumnInfo(name = "media_type") val mediaType: String,
    @ColumnInfo(name = "media_id") val mediaId: Int,
    val step: String,
    val state: String,
) {
    companion object {
        const val ATTEMPTING = "attempting"
        const val DONE = "done"
    }
}

/** §8.3 "once per destination account": the answer to the Import dialog and whether the import finished. */
@Entity(tableName = "import_offer")
data class ImportOfferEntity(
    @PrimaryKey @ColumnInfo(name = "account_object_id") val accountObjectId: String,
    val imported: Boolean,
    val complete: Boolean,
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_item WHERE kind = :kind AND media_type = :mediaType ORDER BY added_at DESC")
    fun observeKind(kind: String, mediaType: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_item WHERE media_type = :mediaType AND media_id = :mediaId")
    fun observeTitle(mediaType: String, mediaId: Int): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_item ORDER BY added_at DESC")
    suspend fun all(): List<LibraryItemEntity>

    @Upsert
    suspend fun upsert(item: LibraryItemEntity)

    @Query("DELETE FROM library_item WHERE media_type = :mediaType AND media_id = :mediaId AND kind = :kind")
    suspend fun delete(mediaType: String, mediaId: Int, kind: String)
}

@Dao
interface ImportLedgerDao {
    @Query(
        "SELECT state FROM import_ledger WHERE account_object_id = :accountObjectId AND media_type = :mediaType " +
            "AND media_id = :mediaId AND step = :step",
    )
    suspend fun state(accountObjectId: String, mediaType: String, mediaId: Int, step: String): String?

    @Upsert
    suspend fun put(entry: ImportLedgerEntity)

    @Query("SELECT * FROM import_offer WHERE account_object_id = :accountObjectId")
    suspend fun offer(accountObjectId: String): ImportOfferEntity?

    @Upsert
    suspend fun putOffer(offer: ImportOfferEntity)
}

@Database(entities = [LibraryItemEntity::class, ImportLedgerEntity::class, ImportOfferEntity::class], version = 1, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun importLedgerDao(): ImportLedgerDao
}
