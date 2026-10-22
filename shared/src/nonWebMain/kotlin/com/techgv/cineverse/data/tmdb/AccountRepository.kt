package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.domain.MediaType

/**
 * Account calls of the native transport (§3.1: `session_id` / user token from [Credentials]). Every function throws
 * [TmdbException]; a session / user-token failure is also reported to [onSessionExpired] with the credentials the
 * call was made with. Lists share [catalog]'s cache and generation rules; writes invalidate through it.
 */
class AccountRepository(
    private val api: TmdbApi,
    private val catalog: CatalogRepository,
    private val settings: suspend () -> Settings,
    /** §3.3: a call made with these credentials found the session / user token invalid. */
    private val onSessionExpired: suspend (Credentials) -> Unit = {},
) {
    /** The shared account-call boundary: every call made with [account] reports its session expiry here. */
    private suspend fun <T> call(operation: TmdbOperation, account: Credentials, block: suspend () -> T): T = try {
        tmdbCall(operation, block)
    } catch (e: TmdbException) {
        if (e.error == TmdbError.SessionExpired) onSessionExpired(account)
        throw e
    }

    /** Never cached: button states are always read fresh. */
    suspend fun accountStates(media: MediaType, id: Int, account: Credentials): AccountStatesDto =
        call(TmdbOperation.Session, account) { api.accountStates(media, id, account.sessionId.value) }

    suspend fun accountList(account: Credentials, media: MediaType, list: AccountList, page: Int = 1): PageDto<MediaItemDto> {
        val s = settings()
        val key = CacheKey(
            CatalogRepository.ACCOUNT_LIST, ids = "$media:${list.path}", language = s.language, page = page,
            accountObjectId = account.accountObjectId.value,
        )
        return catalog.cached(key, TmdbCache.LIST_TTL) {
            call(TmdbOperation.UserToken, account) {
                api.accountList(UserBearer(account.userAccessToken), account.accountObjectId.value, media, list, s.language, page)
            }
        }
    }

    suspend fun setWatchlist(account: Credentials, media: MediaType, id: Int, onWatchlist: Boolean): StatusDto =
        write(account, media, id) { api.setWatchlist(account.accountId.value, account.sessionId.value, WatchlistBody(media.path, id, onWatchlist)) }

    suspend fun setFavorite(account: Credentials, media: MediaType, id: Int, favorite: Boolean): StatusDto =
        write(account, media, id) { api.setFavorite(account.accountId.value, account.sessionId.value, FavoriteBody(media.path, id, favorite)) }

    suspend fun rate(account: Credentials, media: MediaType, id: Int, value: Double): StatusDto =
        write(account, media, id) { api.rate(media, id, account.sessionId.value, RatingBody(value)) }

    suspend fun unrate(account: Credentials, media: MediaType, id: Int): StatusDto =
        write(account, media, id) { api.unrate(media, id, account.sessionId.value) }

    private suspend fun write(account: Credentials, media: MediaType, id: Int, block: suspend () -> StatusDto): StatusDto {
        try {
            return call(TmdbOperation.Session, account, block)
        } finally {
            // Also on failure: the write may have landed before the error surfaced.
            catalog.invalidateTitle(media, id)
        }
    }
}
