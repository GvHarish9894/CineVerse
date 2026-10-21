package com.techgv.cineverse.data.library

import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.tmdb.AccountList
import com.techgv.cineverse.data.tmdb.MediaItemDto
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title

/** A library card: the title plus its rating (Rated badge), if any. */
data class LibraryEntry(val title: Title, val rating: Double?)

data class LibraryPage(val entries: List<LibraryEntry>, val page: Int, val lastPage: Int) {
    val hasMore: Boolean get() = page < lastPage
}

/** v4 account lists carry no `media_type`: the list's [media] decides it. Rated badge = `account_rating.value`. */
fun MediaItemDto.toLibraryEntry(media: MediaType): LibraryEntry {
    val ref = MediaRef(media, id)
    val date = (if (media == MediaType.Movie) releaseDate else firstAirDate)?.takeIf { it.isNotBlank() }
    val label = (if (media == MediaType.Movie) title else name) ?: title ?: name ?: ""
    return LibraryEntry(
        Title(ref, label, overview.orEmpty(), posterPath, backdropPath, date, voteAverage, voteCount, genreIds, media),
        accountRating?.value,
    )
}

val LibraryKind.accountList: AccountList
    get() = when (this) {
        LibraryKind.WATCHLIST -> AccountList.Watchlist
        LibraryKind.FAVOURITE -> AccountList.Favorites
        LibraryKind.RATING -> AccountList.Rated
    }

/** Signed-in library on TMDB (§7.8, §3.5). Every call throws `TmdbException`. */
class AccountLibrarySource(private val tmdb: TmdbRepository) {
    suspend fun page(account: Credentials, kind: LibraryKind, media: MediaType, page: Int): LibraryPage {
        val dto = tmdb.accountList(account, media, kind.accountList, page)
        return LibraryPage(dto.results.map { it.toLibraryEntry(media) }, page, minOf(dto.totalPages, MAX_PAGE))
    }

    /** Cached account lists are dropped: the next reads go to TMDB (Library entry, import preflight). */
    fun invalidateLists() = tmdb.invalidateAccountLists()

    /** §8.3 rule 1: every page of one list. */
    suspend fun all(account: Credentials, kind: LibraryKind, media: MediaType): List<LibraryEntry> {
        val entries = mutableListOf<LibraryEntry>()
        var page = 1
        do {
            val result = page(account, kind, media, page)
            entries += result.entries
            page++
        } while (result.hasMore)
        return entries
    }

    suspend fun states(account: Credentials, ref: MediaRef): LibraryState {
        val dto = tmdb.accountStates(ref.mediaType, ref.id, account)
        return LibraryState(known = true, watchlist = dto.watchlist, favourite = dto.favorite, rating = dto.ratedValue, pending = emptySet())
    }

    /** Applies [kind] of [target] on TMDB. */
    suspend fun write(account: Credentials, ref: MediaRef, kind: LibraryKind, target: LibraryState) {
        when (kind) {
            LibraryKind.WATCHLIST -> tmdb.setWatchlist(account, ref.mediaType, ref.id, target.watchlist)
            LibraryKind.FAVOURITE -> tmdb.setFavorite(account, ref.mediaType, ref.id, target.favourite)
            LibraryKind.RATING -> target.rating
                ?.let { tmdb.rate(account, ref.mediaType, ref.id, it) }
                ?: tmdb.unrate(account, ref.mediaType, ref.id)
        }
    }

    private companion object {
        /** §3.2: paged lists stop at `min(total_pages, 500)`. */
        const val MAX_PAGE = 500
    }
}
