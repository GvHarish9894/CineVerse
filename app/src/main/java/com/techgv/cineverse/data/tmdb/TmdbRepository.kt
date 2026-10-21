package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.util.GenerationCounter
import java.util.TimeZone

const val DEFAULT_IMAGE_BASE_URL = "https://image.tmdb.org/t/p/"

/**
 * Cached TMDB access (§3.4). Every function throws [TmdbException] on failure. Results are committed to
 * the cache only if the app generation and the cache revision (bumped by writes / invalidations) are
 * unchanged since the request started (§1.1).
 */
class TmdbRepository(
    private val api: TmdbApi,
    private val appGeneration: GenerationCounter,
    private val settings: suspend () -> Settings,
    private val timezone: () -> String = { TimeZone.getDefault().id },
    private val cache: TmdbCache = TmdbCache(appGeneration),
    /** §3.3: a call made with these credentials found the session / user token invalid. */
    private val onSessionExpired: suspend (Credentials) -> Unit = {},
) {
    private suspend fun <T : Any> cached(
        key: CacheKey,
        ttlMillis: Long,
        operation: TmdbOperation = TmdbOperation.Catalog,
        account: Credentials? = null,
        fetch: suspend () -> T,
    ): T {
        cache.get<T>(key)?.let { return it }
        val generation = appGeneration.current
        val revision = cache.revision
        val value = call(operation, account, fetch)
        cache.put(key, value, ttlMillis, generation, revision)
        return value
    }

    /** The shared account-call boundary: every call made with [account] reports its session expiry here. */
    private suspend fun <T> call(operation: TmdbOperation, account: Credentials?, block: suspend () -> T): T = try {
        tmdbCall(operation, block)
    } catch (e: TmdbException) {
        if (account != null && e.error == TmdbError.SessionExpired) onSessionExpired(account)
        throw e
    }

    // --- catalog ---

    suspend fun configuration(): ConfigurationDto =
        cached(CONFIGURATION_KEY, TmdbCache.PROCESS_LIFETIME) { api.configuration() }

    /** §1.5: base URL from a loaded `/3/configuration`, else the constant. */
    fun imageBaseUrl(): String =
        cache.get<ConfigurationDto>(CONFIGURATION_KEY)?.images?.secureBaseUrl?.takeIf { it.isNotBlank() }
            ?: DEFAULT_IMAGE_BASE_URL

    suspend fun genres(media: MediaType): GenreListDto {
        val s = settings()
        return cached(CacheKey("genre/list", ids = media.path, language = s.language), TmdbCache.PROCESS_LIFETIME) {
            api.genres(media, s.language)
        }
    }

    suspend fun trending(window: TrendingWindow, page: Int = 1): PageDto<MediaItemDto> {
        val s = settings()
        return cached(CacheKey("trending/all", ids = window.path, language = s.language, page = page), TmdbCache.LIST_TTL) {
            api.trending(window, s.language, page)
        }
    }

    suspend fun movieList(list: MovieList, page: Int = 1): PageDto<MediaItemDto> {
        val s = settings()
        val key = CacheKey("movie/list", ids = list.path, language = s.language, region = s.region, page = page)
        return cached(key, TmdbCache.LIST_TTL) { api.movieList(list, s.language, s.region, page) }
    }

    suspend fun tvList(list: TvList, page: Int = 1): PageDto<MediaItemDto> {
        val s = settings()
        val tz = if (list.usesTimezone) timezone() else null
        val key = CacheKey("tv/list", ids = list.path, language = s.language, timezone = tz, page = page)
        return cached(key, TmdbCache.LIST_TTL) { api.tvList(list, s.language, tz, page) }
    }

    suspend fun popularPeople(page: Int = 1): PageDto<MediaItemDto> {
        val s = settings()
        return cached(CacheKey("person/popular", language = s.language, page = page), TmdbCache.LIST_TTL) {
            api.popularPeople(s.language, page)
        }
    }

    suspend fun searchMulti(query: String, page: Int = 1): PageDto<MediaItemDto> {
        val s = settings()
        val key = CacheKey("search/multi", ids = query, language = s.language, includeAdult = s.includeAdult, page = page)
        return cached(key, TmdbCache.LIST_TTL) { api.searchMulti(query, s.language, s.includeAdult, page) }
    }

    /**
     * Catalog detail only (cached, identical for guest and account); button states come from a separate
     * [accountStates] call so their failure never hides the title. [language] overrides Settings for this
     * request only (D7 `en-US` fallback).
     */
    suspend fun movieDetail(id: Int, language: String? = null): MovieDetailDto {
        val lang = language ?: settings().language
        return cached(titleKey("detail", MediaType.Movie, id, lang), TmdbCache.DETAIL_TTL) {
            api.movieDetail(id, lang, MOVIE_APPEND, null)
        }
    }

    /** See [movieDetail]. */
    suspend fun tvDetail(id: Int, language: String? = null): TvDetailDto {
        val lang = language ?: settings().language
        return cached(titleKey("detail", MediaType.Tv, id, lang), TmdbCache.DETAIL_TTL) {
            api.tvDetail(id, lang, TV_APPEND, null)
        }
    }

    suspend fun videos(media: MediaType, id: Int, language: String? = null): VideosDto {
        val lang = language ?: settings().language
        return cached(titleKey("videos", media, id, lang), TmdbCache.DETAIL_TTL) { api.videos(media, id, lang) }
    }

    suspend fun season(seriesId: Int, seasonNumber: Int): SeasonDto {
        val s = settings()
        val key = CacheKey("tv/season", ids = "tv:$seriesId:$seasonNumber", language = s.language)
        return cached(key, TmdbCache.DETAIL_TTL) { api.season(seriesId, seasonNumber, s.language) }
    }

    /** [language] overrides Settings for this request only (D7 `en-US` fallback). */
    suspend fun person(id: Int, language: String? = null): PersonDetailDto {
        val lang = language ?: settings().language
        return cached(CacheKey("person", ids = "person:$id", language = lang), TmdbCache.DETAIL_TTL) {
            api.person(id, lang)
        }
    }

    // --- account ---

    /** Never cached: button states are always read fresh. */
    suspend fun accountStates(media: MediaType, id: Int, account: Credentials): AccountStatesDto =
        call(TmdbOperation.Session, account) { api.accountStates(media, id, account.sessionId.value) }

    suspend fun accountList(account: Credentials, media: MediaType, list: AccountList, page: Int = 1): PageDto<MediaItemDto> {
        val s = settings()
        val key = CacheKey(
            ACCOUNT_LIST, ids = "$media:${list.path}", language = s.language, page = page,
            accountObjectId = account.accountObjectId.value,
        )
        return cached(key, TmdbCache.LIST_TTL, TmdbOperation.UserToken, account) {
            api.accountList(UserBearer(account.userAccessToken), account.accountObjectId.value, media, list, s.language, page)
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
            invalidateTitle(media, id)
        }
    }

    // --- invalidation (§3.4) ---

    /** Affected title + Library lists, after a write; in-flight reads started before it are not cached. */
    fun invalidateTitle(media: MediaType, id: Int) {
        val ids = "$media:$id"
        cache.invalidate(bumpGeneration = false) { it.ids == ids || it.endpoint == ACCOUNT_LIST }
    }

    /** Account lists only (§7.8 revalidate on entry, §8.3 import preflight); in-flight list reads are not cached. */
    fun invalidateAccountLists() {
        cache.invalidate(bumpGeneration = false) { it.endpoint == ACCOUNT_LIST }
    }

    /** Language / region / adult changed: everything but the image configuration; visible screens reload. */
    fun onContentSettingsChanged() {
        cache.invalidate(bumpGeneration = true) { it != CONFIGURATION_KEY }
    }

    /** Sign-in / sign-out: account entries cleared, in-flight account work dropped by generation. */
    fun onAccountChanged() {
        cache.invalidate(bumpGeneration = true) { it.accountObjectId != null }
    }

    private fun titleKey(endpoint: String, media: MediaType, id: Int, language: String) =
        CacheKey(endpoint, ids = "$media:$id", language = language)

    private companion object {
        val CONFIGURATION_KEY = CacheKey("configuration")
        const val ACCOUNT_LIST = "account/list"
        const val MOVIE_APPEND = "credits,videos,recommendations,reviews,release_dates"
        const val TV_APPEND = "aggregate_credits,videos,recommendations,reviews,content_ratings"
    }
}
