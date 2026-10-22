package com.techgv.cineverse.data.library

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType

/**
 * [AccountLibrarySource] on TMDB with the credentials of each [AuthSession] ([credentialsFor]: the native auth
 * repository). Every call throws `TmdbException`.
 */
class TmdbAccountLibrarySource(
    private val tmdb: TmdbRepository,
    private val credentialsFor: (AuthSession) -> Credentials,
) : AccountLibrarySource {
    override suspend fun page(session: AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage {
        val dto = tmdb.accountList(credentialsFor(session), media, kind.accountList, page)
        return LibraryPage(dto.results.map { it.toLibraryEntry(media) }, page, minOf(dto.totalPages, MAX_PAGE))
    }

    override fun invalidateLists() = tmdb.invalidateAccountLists()

    override suspend fun states(session: AuthSession, ref: MediaRef): LibraryState {
        val dto = tmdb.accountStates(ref.mediaType, ref.id, credentialsFor(session))
        return LibraryState(known = true, watchlist = dto.watchlist, favourite = dto.favorite, rating = dto.ratedValue, pending = emptySet())
    }

    override suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState) {
        val account = credentialsFor(session)
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
