package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.datetime.TimeZone

/**
 * Native facade over [CatalogRepository] (common) and [AccountRepository] (Credentials-bound), so the TV's call
 * sites and fakes keep one entry point. Every function throws [TmdbException] on failure.
 */
class TmdbRepository(
    api: TmdbApi,
    appGeneration: GenerationCounter,
    settings: suspend () -> Settings,
    timezone: () -> String = { TimeZone.currentSystemDefault().id },
    cache: TmdbCache = TmdbCache(appGeneration),
    /** §3.3: a call made with these credentials found the session / user token invalid. */
    onSessionExpired: suspend (Credentials) -> Unit = {},
) {
    val catalog = CatalogRepository(api, appGeneration, settings, timezone, cache)
    val account = AccountRepository(api, catalog, settings, onSessionExpired)

    // --- catalog ---
    suspend fun configuration() = catalog.configuration()
    fun imageBaseUrl() = catalog.imageBaseUrl()
    suspend fun genres(media: MediaType) = catalog.genres(media)
    suspend fun trending(window: TrendingWindow, page: Int = 1) = catalog.trending(window, page)
    suspend fun movieList(list: MovieList, page: Int = 1) = catalog.movieList(list, page)
    suspend fun tvList(list: TvList, page: Int = 1) = catalog.tvList(list, page)
    suspend fun popularPeople(page: Int = 1) = catalog.popularPeople(page)
    suspend fun searchMulti(query: String, page: Int = 1) = catalog.searchMulti(query, page)
    suspend fun movieDetail(id: Int, language: String? = null) = catalog.movieDetail(id, language)
    suspend fun tvDetail(id: Int, language: String? = null) = catalog.tvDetail(id, language)
    suspend fun videos(media: MediaType, id: Int, language: String? = null) = catalog.videos(media, id, language)
    suspend fun season(seriesId: Int, seasonNumber: Int, language: String? = null) = catalog.season(seriesId, seasonNumber, language)
    suspend fun person(id: Int, language: String? = null) = catalog.person(id, language)

    // --- account ---
    suspend fun accountStates(media: MediaType, id: Int, account: Credentials) = this.account.accountStates(media, id, account)
    suspend fun accountList(account: Credentials, media: MediaType, list: AccountList, page: Int = 1) =
        this.account.accountList(account, media, list, page)
    suspend fun setWatchlist(account: Credentials, media: MediaType, id: Int, onWatchlist: Boolean) =
        this.account.setWatchlist(account, media, id, onWatchlist)
    suspend fun setFavorite(account: Credentials, media: MediaType, id: Int, favorite: Boolean) =
        this.account.setFavorite(account, media, id, favorite)
    suspend fun rate(account: Credentials, media: MediaType, id: Int, value: Double) = this.account.rate(account, media, id, value)
    suspend fun unrate(account: Credentials, media: MediaType, id: Int) = this.account.unrate(account, media, id)

    // --- invalidation (§3.4) ---
    fun invalidateTitle(media: MediaType, id: Int) = catalog.invalidateTitle(media, id)
    fun invalidateAccountLists() = catalog.invalidateAccountLists()
    fun onContentSettingsChanged() = catalog.onContentSettingsChanged()
    fun onAccountChanged() = catalog.onAccountChanged()
}

suspend fun TmdbRepository.genreNames(): Map<Int, String> = catalog.genreNames()
