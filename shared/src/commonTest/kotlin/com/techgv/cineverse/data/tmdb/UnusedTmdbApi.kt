package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.domain.MediaType

/** Base for test fakes: every [TmdbApi] call the fake does not override fails loudly (the JVM tests use a Proxy). */
open class UnusedTmdbApi : TmdbApi {
    private fun unused(name: String): Nothing = throw UnsupportedOperationException("TmdbApi.$name not faked")

    override suspend fun configuration(): ConfigurationDto = unused("configuration")
    override suspend fun genres(media: MediaType, language: String): GenreListDto = unused("genres")
    override suspend fun trending(window: TrendingWindow, language: String, page: Int): PageDto<MediaItemDto> = unused("trending")
    override suspend fun movieList(list: MovieList, language: String, region: String, page: Int): PageDto<MediaItemDto> = unused("movieList")
    override suspend fun tvList(list: TvList, language: String, timezone: String?, page: Int): PageDto<MediaItemDto> = unused("tvList")
    override suspend fun popularPeople(language: String, page: Int): PageDto<MediaItemDto> = unused("popularPeople")
    override suspend fun movieDetail(id: Int, language: String, append: String, sessionId: String?): MovieDetailDto = unused("movieDetail")
    override suspend fun tvDetail(id: Int, language: String, append: String, sessionId: String?): TvDetailDto = unused("tvDetail")
    override suspend fun videos(media: MediaType, id: Int, language: String): VideosDto = unused("videos")
    override suspend fun season(seriesId: Int, seasonNumber: Int, language: String): SeasonDto = unused("season")
    override suspend fun person(id: Int, language: String, append: String): PersonDetailDto = unused("person")
    override suspend fun searchMulti(query: String, language: String, includeAdult: Boolean, page: Int): PageDto<MediaItemDto> = unused("searchMulti")
    override suspend fun account(sessionId: String): AccountDto = unused("account")
    override suspend fun accountStates(media: MediaType, id: Int, sessionId: String): AccountStatesDto = unused("accountStates")
    override suspend fun accountList(bearer: UserBearer, accountObjectId: String, media: MediaType, list: AccountList, language: String, page: Int, sortBy: String): PageDto<MediaItemDto> = unused("accountList")
    override suspend fun setWatchlist(accountId: Int, sessionId: String, body: WatchlistBody): StatusDto = unused("setWatchlist")
    override suspend fun setFavorite(accountId: Int, sessionId: String, body: FavoriteBody): StatusDto = unused("setFavorite")
    override suspend fun rate(media: MediaType, id: Int, sessionId: String, body: RatingBody): StatusDto = unused("rate")
    override suspend fun unrate(media: MediaType, id: Int, sessionId: String): StatusDto = unused("unrate")
    override suspend fun createRequestToken(body: RequestTokenBody): RequestTokenDto = unused("createRequestToken")
    override suspend fun createAccessToken(body: AccessTokenBody): AccessTokenDto = unused("createAccessToken")
    override suspend fun convertToSession(body: ConvertSessionBody): SessionDto = unused("convertToSession")
    override suspend fun deleteAccessToken(bearer: UserBearer, body: DeleteAccessTokenBody): StatusDto = unused("deleteAccessToken")
    override suspend fun deleteSession(body: DeleteSessionBody): StatusDto = unused("deleteSession")
}
