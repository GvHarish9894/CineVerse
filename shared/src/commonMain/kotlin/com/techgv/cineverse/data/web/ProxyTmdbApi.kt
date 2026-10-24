package com.techgv.cineverse.data.web

import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.MediaType

/** Catalog-only proxy adapter for CatalogRepository. Native credentials/auth/account APIs are deliberately unsupported. */
class ProxyTmdbApi(private val http: WebProxyHttp) : TmdbApi {
    private suspend inline fun <reified T> call(path: String, vararg query: Pair<String, Any?>): T =
        TmdbJson.decodeFromString(http.call("/api/tmdb/$path", query.toList()))

    private fun nativeOnly(): Nothing = throw UnsupportedOperationException("Use WebAuthRepository / WebAccountLibrarySource; native credentials are not accepted by the proxy")

    override suspend fun configuration(): ConfigurationDto = call("configuration")

    override suspend fun genres(media: MediaType, language: String): GenreListDto =
        call("genre/$media/list", "language" to language)

    override suspend fun trending(window: TrendingWindow, language: String, page: Int): PageDto<MediaItemDto> =
        call("trending/all/$window", "language" to language, "page" to page)

    override suspend fun movieList(list: MovieList, language: String, region: String, page: Int): PageDto<MediaItemDto> =
        call("movie/$list", "language" to language, "region" to region, "page" to page)

    override suspend fun tvList(list: TvList, language: String, timezone: String?, page: Int): PageDto<MediaItemDto> =
        call("tv/$list", "language" to language, "timezone" to timezone.takeIf { list.usesTimezone }, "page" to page)

    override suspend fun discover(
        media: MediaType,
        genreIds: List<Int>,
        sort: DiscoverSort,
        language: String,
        region: String?,
        page: Int,
    ): PageDto<MediaItemDto> =
        call("discover/$media", *discoverQuery(media, genreIds, sort, language, region, page).toTypedArray())

    override suspend fun keywords(media: MediaType, id: Int): KeywordsDto {
        requireCatalogId(id)
        return decodeKeywords(media, http.call("/api/tmdb/$media/$id/keywords"))
    }

    override suspend fun popularPeople(language: String, page: Int): PageDto<MediaItemDto> =
        call("person/popular", "language" to language, "page" to page)

    override suspend fun movieDetail(id: Int, language: String, append: String, sessionId: String?): MovieDetailDto =
        catalogDetail(sessionId) { call("movie/$id", "language" to language, "append_to_response" to append) }

    override suspend fun tvDetail(id: Int, language: String, append: String, sessionId: String?): TvDetailDto =
        catalogDetail(sessionId) { call("tv/$id", "language" to language, "append_to_response" to append) }

    override suspend fun videos(media: MediaType, id: Int, language: String): VideosDto =
        call("$media/$id/videos", "language" to language)

    override suspend fun season(seriesId: Int, seasonNumber: Int, language: String): SeasonDto =
        call("tv/$seriesId/season/$seasonNumber", "language" to language)

    override suspend fun person(id: Int, language: String, append: String): PersonDetailDto =
        call("person/$id", "language" to language, "append_to_response" to append)

    override suspend fun searchMulti(query: String, language: String, includeAdult: Boolean, page: Int): PageDto<MediaItemDto> =
        call("search/multi", "query" to query, "language" to language, "include_adult" to includeAdult, "page" to page)

    private suspend inline fun <T> catalogDetail(sessionId: String?, load: () -> T): T {
        if (sessionId != null) nativeOnly()
        return load()
    }

    override suspend fun account(sessionId: String): AccountDto = nativeOnly()

    override suspend fun accountStates(media: MediaType, id: Int, sessionId: String): AccountStatesDto = nativeOnly()

    override suspend fun accountList(
        bearer: UserBearer,
        accountObjectId: String,
        media: MediaType,
        list: AccountList,
        language: String,
        page: Int,
        sortBy: String,
    ): PageDto<MediaItemDto> = nativeOnly()

    override suspend fun setWatchlist(accountId: Int, sessionId: String, body: WatchlistBody): StatusDto = nativeOnly()

    override suspend fun setFavorite(accountId: Int, sessionId: String, body: FavoriteBody): StatusDto = nativeOnly()

    override suspend fun rate(media: MediaType, id: Int, sessionId: String, body: RatingBody): StatusDto = nativeOnly()

    override suspend fun unrate(media: MediaType, id: Int, sessionId: String): StatusDto = nativeOnly()

    override suspend fun createRequestToken(body: RequestTokenBody): RequestTokenDto = nativeOnly()

    override suspend fun createAccessToken(body: AccessTokenBody): AccessTokenDto = nativeOnly()

    override suspend fun convertToSession(body: ConvertSessionBody): SessionDto = nativeOnly()

    override suspend fun deleteAccessToken(bearer: UserBearer, body: DeleteAccessTokenBody): StatusDto = nativeOnly()

    override suspend fun deleteSession(body: DeleteSessionBody): StatusDto = nativeOnly()
}
