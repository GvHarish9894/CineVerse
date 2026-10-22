package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.datetime.TimeZone

const val DEFAULT_IMAGE_BASE_URL = "https://image.tmdb.org/t/p/"

/**
 * Cached read-token TMDB catalog (§3.4), the same on every transport. Every function throws [TmdbException] on
 * failure. Results are committed to the cache only if the app generation and the cache revision (bumped by
 * writes / invalidations) are unchanged since the request started (§1.1). Owns the cache's invalidation rules;
 * account reads (nonWeb `AccountRepository`) share the cache through [cached].
 */
class CatalogRepository(
    private val api: TmdbApi,
    private val appGeneration: GenerationCounter,
    private val settings: suspend () -> Settings,
    private val timezone: () -> String = { TimeZone.currentSystemDefault().id },
    private val cache: TmdbCache = TmdbCache(appGeneration),
) {
    /** Serves [key] from the cache, else runs [load] (which classifies its own errors) and commits the result. */
    internal suspend fun <T : Any> cached(key: CacheKey, ttlMillis: Long, load: suspend () -> T): T {
        cache.get<T>(key)?.let { return it }
        val generation = appGeneration.current
        val revision = cache.revision
        val value = load()
        cache.put(key, value, ttlMillis, generation, revision)
        return value
    }

    private suspend fun <T : Any> catalog(key: CacheKey, ttlMillis: Long, fetch: suspend () -> T): T =
        cached(key, ttlMillis) { tmdbCall(TmdbOperation.Catalog, fetch) }

    suspend fun configuration(): ConfigurationDto =
        catalog(CONFIGURATION_KEY, TmdbCache.PROCESS_LIFETIME) { api.configuration() }

    /** §1.5: base URL from a loaded `/3/configuration`, else the constant. Synchronous cache snapshot. */
    fun imageBaseUrl(): String =
        cache.get<ConfigurationDto>(CONFIGURATION_KEY)?.images?.secureBaseUrl?.takeIf { it.isNotBlank() }
            ?: DEFAULT_IMAGE_BASE_URL

    suspend fun genres(media: MediaType): GenreListDto {
        val s = settings()
        return catalog(CacheKey("genre/list", ids = media.path, language = s.language), TmdbCache.PROCESS_LIFETIME) {
            api.genres(media, s.language)
        }
    }

    suspend fun trending(window: TrendingWindow, page: Int = 1): PageDto<MediaItemDto> {
        val s = settings()
        return catalog(CacheKey("trending/all", ids = window.path, language = s.language, page = page), TmdbCache.LIST_TTL) {
            api.trending(window, s.language, page)
        }
    }

    suspend fun movieList(list: MovieList, page: Int = 1): PageDto<MediaItemDto> {
        val s = settings()
        val key = CacheKey("movie/list", ids = list.path, language = s.language, region = s.region, page = page)
        return catalog(key, TmdbCache.LIST_TTL) { api.movieList(list, s.language, s.region, page) }
    }

    suspend fun tvList(list: TvList, page: Int = 1): PageDto<MediaItemDto> {
        val s = settings()
        val tz = if (list.usesTimezone) timezone() else null
        val key = CacheKey("tv/list", ids = list.path, language = s.language, timezone = tz, page = page)
        return catalog(key, TmdbCache.LIST_TTL) { api.tvList(list, s.language, tz, page) }
    }

    suspend fun popularPeople(page: Int = 1): PageDto<MediaItemDto> {
        val s = settings()
        return catalog(CacheKey("person/popular", language = s.language, page = page), TmdbCache.LIST_TTL) {
            api.popularPeople(s.language, page)
        }
    }

    suspend fun searchMulti(query: String, page: Int = 1): PageDto<MediaItemDto> {
        val s = settings()
        val key = CacheKey("search/multi", ids = query, language = s.language, includeAdult = s.includeAdult, page = page)
        return catalog(key, TmdbCache.LIST_TTL) { api.searchMulti(query, s.language, s.includeAdult, page) }
    }

    /**
     * Catalog detail only (cached, identical for guest and account); button states come from a separate
     * `account_states` call so their failure never hides the title. [language] overrides Settings for this
     * request only (D7 `en-US` fallback).
     */
    suspend fun movieDetail(id: Int, language: String? = null): MovieDetailDto {
        val lang = language ?: settings().language
        return catalog(titleKey("detail", MediaType.Movie, id, lang), TmdbCache.DETAIL_TTL) {
            api.movieDetail(id, lang, MOVIE_APPEND, null)
        }
    }

    /** See [movieDetail]. */
    suspend fun tvDetail(id: Int, language: String? = null): TvDetailDto {
        val lang = language ?: settings().language
        return catalog(titleKey("detail", MediaType.Tv, id, lang), TmdbCache.DETAIL_TTL) {
            api.tvDetail(id, lang, TV_APPEND, null)
        }
    }

    suspend fun videos(media: MediaType, id: Int, language: String? = null): VideosDto {
        val lang = language ?: settings().language
        return catalog(titleKey("videos", media, id, lang), TmdbCache.DETAIL_TTL) { api.videos(media, id, lang) }
    }

    /** [language] overrides Settings for this request only (the caller's load language). */
    suspend fun season(seriesId: Int, seasonNumber: Int, language: String? = null): SeasonDto {
        val lang = language ?: settings().language
        val key = CacheKey("tv/season", ids = "tv:$seriesId:$seasonNumber", language = lang)
        return catalog(key, TmdbCache.DETAIL_TTL) { api.season(seriesId, seasonNumber, lang) }
    }

    /** [language] overrides Settings for this request only (D7 `en-US` fallback). */
    suspend fun person(id: Int, language: String? = null): PersonDetailDto {
        val lang = language ?: settings().language
        return catalog(CacheKey("person", ids = "person:$id", language = lang), TmdbCache.DETAIL_TTL) {
            api.person(id, lang)
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

    internal companion object {
        val CONFIGURATION_KEY = CacheKey("configuration")
        /** Cache endpoint of the account lists ([invalidateTitle] drops them after any write). */
        const val ACCOUNT_LIST = "account/list"
        const val MOVIE_APPEND = "credits,videos,recommendations,reviews,release_dates"
        const val TV_APPEND = "aggregate_credits,videos,recommendations,reviews,content_ratings"
    }
}
