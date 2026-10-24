package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.domain.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

/**
 * [TmdbApi] over a [tmdbHttpClient]: paths are relative to the transport's base URL, null query values are
 * omitted (§3.2), bodies and responses use [TmdbJson]. Non-2xx responses throw [TmdbHttpException]; a body
 * that does not decode throws a `SerializationException` (→ [TmdbError.Malformed]).
 */
class KtorTmdbApi(private val client: HttpClient) : TmdbApi {
    private suspend inline fun <reified T> call(
        path: String,
        vararg query: Pair<String, Any?>,
        method: HttpMethod = HttpMethod.Get,
        body: String? = null,
        bearer: UserBearer? = null,
    ): T {
        val response = client.request(path) {
            this.method = method
            query.forEach { (name, value) -> if (value != null) parameter(name, value) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (bearer != null) attributes.put(UserBearerKey, bearer.token)
        }
        return TmdbJson.decodeFromString(response.bodyAsText())
    }

    private inline fun <reified B> json(body: B): String = TmdbJson.encodeToString(body)

    override suspend fun configuration(): ConfigurationDto = call("3/configuration")

    override suspend fun genres(media: MediaType, language: String): GenreListDto =
        call("3/genre/$media/list", "language" to language)

    override suspend fun trending(window: TrendingWindow, language: String, page: Int): PageDto<MediaItemDto> =
        call("3/trending/all/$window", "language" to language, "page" to page)

    override suspend fun movieList(list: MovieList, language: String, region: String, page: Int): PageDto<MediaItemDto> =
        call("3/movie/$list", "language" to language, "region" to region, "page" to page)

    override suspend fun tvList(list: TvList, language: String, timezone: String?, page: Int): PageDto<MediaItemDto> =
        call("3/tv/$list", "language" to language, "timezone" to timezone, "page" to page)

    override suspend fun discover(
        media: MediaType,
        genreIds: List<Int>,
        sort: DiscoverSort,
        language: String,
        region: String?,
        page: Int,
    ): PageDto<MediaItemDto> =
        call("3/discover/$media", *discoverQuery(media, genreIds, sort, language, region, page).toTypedArray())

    override suspend fun keywords(media: MediaType, id: Int): KeywordsDto {
        requireCatalogId(id)
        return decodeKeywords(media, client.request("3/$media/$id/keywords").bodyAsText())
    }

    override suspend fun popularPeople(language: String, page: Int): PageDto<MediaItemDto> =
        call("3/person/popular", "language" to language, "page" to page)

    override suspend fun movieDetail(id: Int, language: String, append: String, sessionId: String?): MovieDetailDto =
        call("3/movie/$id", "language" to language, "append_to_response" to append, "session_id" to sessionId)

    override suspend fun tvDetail(id: Int, language: String, append: String, sessionId: String?): TvDetailDto =
        call("3/tv/$id", "language" to language, "append_to_response" to append, "session_id" to sessionId)

    override suspend fun videos(media: MediaType, id: Int, language: String): VideosDto =
        call("3/$media/$id/videos", "language" to language)

    override suspend fun season(seriesId: Int, seasonNumber: Int, language: String): SeasonDto =
        call("3/tv/$seriesId/season/$seasonNumber", "language" to language)

    override suspend fun person(id: Int, language: String, append: String): PersonDetailDto =
        call("3/person/$id", "language" to language, "append_to_response" to append)

    override suspend fun searchMulti(query: String, language: String, includeAdult: Boolean, page: Int): PageDto<MediaItemDto> =
        call("3/search/multi", "query" to query, "language" to language, "include_adult" to includeAdult, "page" to page)

    override suspend fun account(sessionId: String): AccountDto = call("3/account", "session_id" to sessionId)

    override suspend fun accountStates(media: MediaType, id: Int, sessionId: String): AccountStatesDto =
        call("3/$media/$id/account_states", "session_id" to sessionId)

    override suspend fun accountList(
        bearer: UserBearer,
        accountObjectId: String,
        media: MediaType,
        list: AccountList,
        language: String,
        page: Int,
        sortBy: String,
    ): PageDto<MediaItemDto> =
        call("4/account/$accountObjectId/$media/$list", "language" to language, "page" to page, "sort_by" to sortBy, bearer = bearer)

    override suspend fun setWatchlist(accountId: Int, sessionId: String, body: WatchlistBody): StatusDto =
        call("3/account/$accountId/watchlist", "session_id" to sessionId, method = HttpMethod.Post, body = json(body))

    override suspend fun setFavorite(accountId: Int, sessionId: String, body: FavoriteBody): StatusDto =
        call("3/account/$accountId/favorite", "session_id" to sessionId, method = HttpMethod.Post, body = json(body))

    override suspend fun rate(media: MediaType, id: Int, sessionId: String, body: RatingBody): StatusDto =
        call("3/$media/$id/rating", "session_id" to sessionId, method = HttpMethod.Post, body = json(body))

    override suspend fun unrate(media: MediaType, id: Int, sessionId: String): StatusDto =
        call("3/$media/$id/rating", "session_id" to sessionId, method = HttpMethod.Delete)

    override suspend fun createRequestToken(body: RequestTokenBody): RequestTokenDto =
        call("4/auth/request_token", method = HttpMethod.Post, body = json(body))

    override suspend fun createAccessToken(body: AccessTokenBody): AccessTokenDto =
        call("4/auth/access_token", method = HttpMethod.Post, body = json(body))

    override suspend fun convertToSession(body: ConvertSessionBody): SessionDto =
        call("3/authentication/session/convert/4", method = HttpMethod.Post, body = json(body))

    override suspend fun deleteAccessToken(bearer: UserBearer, body: DeleteAccessTokenBody): StatusDto =
        call("4/auth/access_token", method = HttpMethod.Delete, body = json(body), bearer = bearer)

    override suspend fun deleteSession(body: DeleteSessionBody): StatusDto =
        call("3/authentication/session", method = HttpMethod.Delete, body = json(body))
}
