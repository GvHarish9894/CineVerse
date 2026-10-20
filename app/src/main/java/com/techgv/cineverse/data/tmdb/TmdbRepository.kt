package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.util.GenerationCounter
import java.util.TimeZone

const val DEFAULT_IMAGE_BASE_URL = "https://image.tmdb.org/t/p/"

/**
 * Cached TMDB access (§3.4). Every function throws [TmdbException] on failure. Results are committed to
 * the cache only if the app generation is unchanged since the request started (§1.1).
 */
class TmdbRepository(
    private val api: TmdbApi,
    private val appGeneration: GenerationCounter,
    private val settings: suspend () -> Settings,
    private val timezone: () -> String = { TimeZone.getDefault().id },
    private val cache: TmdbCache = TmdbCache(appGeneration),
) {
    private suspend fun <T : Any> cached(
        key: CacheKey,
        ttlMillis: Long,
        operation: TmdbOperation = TmdbOperation.Catalog,
        fetch: suspend () -> T,
    ): T {
        cache.get<T>(key)?.let { return it }
        val generation = appGeneration.current
        val value = tmdbCall(operation, fetch)
        cache.put(key, value, ttlMillis, generation)
        return value
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

    /** With [account], `account_states` is appended and the entry becomes account data. */
    suspend fun movieDetail(id: Int, account: Credentials? = null): MovieDetailDto {
        val s = settings()
        val append = MOVIE_APPEND + if (account != null) ",account_states" else ""
        val key = titleKey("detail", MediaType.Movie, id, s.language, account)
        return cached(key, TmdbCache.DETAIL_TTL, account.operation()) {
            api.movieDetail(id, s.language, append, account?.sessionId?.value)
        }
    }

    suspend fun tvDetail(id: Int, account: Credentials? = null): TvDetailDto {
        val s = settings()
        val append = TV_APPEND + if (account != null) ",account_states" else ""
        val key = titleKey("detail", MediaType.Tv, id, s.language, account)
        return cached(key, TmdbCache.DETAIL_TTL, account.operation()) {
            api.tvDetail(id, s.language, append, account?.sessionId?.value)
        }
    }

    suspend fun videos(media: MediaType, id: Int, language: String? = null): VideosDto {
        val lang = language ?: settings().language
        return cached(titleKey("videos", media, id, lang, null), TmdbCache.DETAIL_TTL) { api.videos(media, id, lang) }
    }

    suspend fun season(seriesId: Int, seasonNumber: Int): SeasonDto {
        val s = settings()
        val key = CacheKey("tv/season", ids = "tv:$seriesId:$seasonNumber", language = s.language)
        return cached(key, TmdbCache.DETAIL_TTL) { api.season(seriesId, seasonNumber, s.language) }
    }

    suspend fun person(id: Int): PersonDetailDto {
        val s = settings()
        return cached(CacheKey("person", ids = "person:$id", language = s.language), TmdbCache.DETAIL_TTL) {
            api.person(id, s.language)
        }
    }

    // --- account ---

    /** Never cached: button states are always read fresh. */
    suspend fun accountStates(media: MediaType, id: Int, account: Credentials): AccountStatesDto =
        tmdbCall(TmdbOperation.Session) { api.accountStates(media, id, account.sessionId.value) }

    suspend fun accountList(account: Credentials, media: MediaType, list: AccountList, page: Int = 1): PageDto<MediaItemDto> {
        val s = settings()
        val key = CacheKey(
            ACCOUNT_LIST, ids = "$media:${list.path}", language = s.language, page = page,
            accountObjectId = account.accountObjectId.value,
        )
        return cached(key, TmdbCache.LIST_TTL, TmdbOperation.UserToken) {
            api.accountList(UserBearer(account.userAccessToken), account.accountObjectId.value, media, list, s.language, page)
        }
    }

    suspend fun setWatchlist(account: Credentials, media: MediaType, id: Int, onWatchlist: Boolean): StatusDto =
        write(media, id) { api.setWatchlist(account.accountId.value, account.sessionId.value, WatchlistBody(media.path, id, onWatchlist)) }

    suspend fun setFavorite(account: Credentials, media: MediaType, id: Int, favorite: Boolean): StatusDto =
        write(media, id) { api.setFavorite(account.accountId.value, account.sessionId.value, FavoriteBody(media.path, id, favorite)) }

    suspend fun rate(account: Credentials, media: MediaType, id: Int, value: Double): StatusDto =
        write(media, id) { api.rate(media, id, account.sessionId.value, RatingBody(value)) }

    suspend fun unrate(account: Credentials, media: MediaType, id: Int): StatusDto =
        write(media, id) { api.unrate(media, id, account.sessionId.value) }

    private suspend fun write(media: MediaType, id: Int, block: suspend () -> StatusDto): StatusDto {
        try {
            return tmdbCall(TmdbOperation.Session, block)
        } finally {
            // Also on failure: the write may have landed before the error surfaced.
            invalidateTitle(media, id)
        }
    }

    // --- invalidation (§3.4) ---

    /** Affected title + Library lists, after a write. */
    fun invalidateTitle(media: MediaType, id: Int) {
        val ids = "$media:$id"
        cache.invalidate(bumpGeneration = false) { it.ids == ids || it.endpoint == ACCOUNT_LIST }
    }

    /** Language / region / adult changed: everything but the image configuration; visible screens reload. */
    fun onContentSettingsChanged() {
        cache.invalidate(bumpGeneration = true) { it != CONFIGURATION_KEY }
    }

    /** Sign-in / sign-out: account entries cleared, in-flight account work dropped by generation. */
    fun onAccountChanged() {
        cache.invalidate(bumpGeneration = true) { it.accountObjectId != null }
    }

    private fun titleKey(endpoint: String, media: MediaType, id: Int, language: String, account: Credentials?) =
        CacheKey(endpoint, ids = "$media:$id", language = language, accountObjectId = account?.accountObjectId?.value)

    private fun Credentials?.operation() = if (this != null) TmdbOperation.Session else TmdbOperation.Catalog

    private companion object {
        val CONFIGURATION_KEY = CacheKey("configuration")
        const val ACCOUNT_LIST = "account/list"
        const val MOVIE_APPEND = "credits,videos,recommendations,reviews,release_dates"
        const val TV_APPEND = "aggregate_credits,videos,recommendations,reviews,content_ratings"
    }
}
