package com.techgv.cineverse.data.library

import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** One guest library row with its title snapshot. */
data class GuestItem(val title: Title, val kind: LibraryKind, val value: Double?)

/** §8.2 guest library over a [GuestLibraryStore] (TV: Room `library.db`); newest first. */
class GuestLibrarySource(
    private val store: GuestLibraryStore,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    fun observeState(ref: MediaRef): Flow<LibraryState> = store.observeTitle(ref).map { rows ->
        val kinds = rows.associateBy { it.kind }
        LibraryState(
            known = true,
            watchlist = LibraryKind.WATCHLIST in kinds,
            favourite = LibraryKind.FAVOURITE in kinds,
            rating = kinds[LibraryKind.RATING]?.value,
            pending = emptySet(),
        )
    }

    suspend fun state(ref: MediaRef): LibraryState = observeState(ref).first()

    fun observeEntries(kind: LibraryKind, media: MediaType): Flow<List<LibraryEntry>> =
        store.observeKind(kind, media).map { rows -> rows.map { LibraryEntry(it.title(), it.value) } }

    suspend fun all(): List<GuestItem> = store.all().map { GuestItem(it.title(), it.kind, it.value) }

    /** Applies [kind] of [target] to [title]: present (with the rating as value) or removed. */
    suspend fun write(title: Title, kind: LibraryKind, target: LibraryState) {
        val ref = title.ref
        val present = when (kind) {
            LibraryKind.WATCHLIST -> target.watchlist
            LibraryKind.FAVOURITE -> target.favourite
            LibraryKind.RATING -> target.rating != null
        }
        if (present) {
            val value = target.rating.takeIf { kind == LibraryKind.RATING }
            store.upsert(GuestLibraryRow(ref, kind, value, now(), Json.encodeToString(StoredTitle.of(title))))
        } else {
            store.delete(ref, kind)
        }
    }

    private fun GuestLibraryRow.title(): Title = Json.decodeFromString<StoredTitle>(titleJson).toTitle(ref)
}

/** `title_json`: the card fields of a [Title]; its identity lives in the row's key columns. */
@Serializable
private data class StoredTitle(
    val title: String,
    val overview: String = "",
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
    val voteAverage: Double = 0.0,
    val voteCount: Int = 0,
    val genreIds: List<Int> = emptyList(),
) {
    fun toTitle(ref: MediaRef) =
        Title(ref, title, overview, posterPath, backdropPath, releaseDate, voteAverage, voteCount, genreIds, ref.mediaType)

    companion object {
        fun of(t: Title) = StoredTitle(t.title, t.overview, t.posterPath, t.backdropPath, t.releaseDate, t.voteAverage, t.voteCount, t.genreIds)
    }
}
