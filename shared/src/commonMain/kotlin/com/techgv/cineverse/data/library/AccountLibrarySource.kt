package com.techgv.cineverse.data.library

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.tmdb.AccountList
import com.techgv.cineverse.data.tmdb.MediaItemDto
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

/**
 * Signed-in library of [AuthSession]'s account (§7.8, §3.5). Native: TMDB with that sign-in's credentials
 * (`TmdbAccountLibrarySource`); web: the server proxy. Every call throws `TmdbException`.
 */
interface AccountLibrarySource {
    suspend fun page(session: AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage

    /** Cached account lists are dropped: the next reads go to TMDB (Library entry, import preflight). */
    fun invalidateLists()

    suspend fun states(session: AuthSession, ref: MediaRef): LibraryState

    /** Applies [kind] of [target] on TMDB. */
    suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState)
}

/** §8.3 rule 1: every page of one list. */
suspend fun AccountLibrarySource.all(session: AuthSession, kind: LibraryKind, media: MediaType): List<LibraryEntry> {
    val entries = mutableListOf<LibraryEntry>()
    var page = 1
    do {
        val result = page(session, kind, media, page)
        entries += result.entries
        page++
    } while (result.hasMore)
    return entries
}
