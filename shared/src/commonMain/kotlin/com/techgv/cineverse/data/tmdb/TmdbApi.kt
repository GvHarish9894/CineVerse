package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.domain.MediaType

enum class TrendingWindow(val path: String) { Day("day"), Week("week"); override fun toString() = path }

enum class MovieList(val path: String) {
    Popular("popular"), NowPlaying("now_playing"), Upcoming("upcoming"), TopRated("top_rated");
    override fun toString() = path
}

enum class TvList(val path: String, val usesTimezone: Boolean) {
    Popular("popular", false), AiringToday("airing_today", true), OnTheAir("on_the_air", true), TopRated("top_rated", false);
    override fun toString() = path
}

enum class AccountList(val path: String) {
    Watchlist("watchlist"), Favorites("favorites"), Rated("rated");
    override fun toString() = path
}

/** Marks a call that must use the user access token instead of the read token (§3.1). */
class UserBearer(val token: UserAccessToken)

/**
 * TMDB endpoints (§3.5) with per-endpoint parameters (§3.2). Nullable query values are omitted.
 * Token kind (§3.1): every call uses the read token, except calls taking a [UserBearer], which
 * use that user access token instead. Implemented by [KtorTmdbApi]; tests fake it.
 */
interface TmdbApi {
    suspend fun configuration(): ConfigurationDto

    suspend fun genres(media: MediaType, language: String): GenreListDto

    suspend fun trending(
        window: TrendingWindow,
        language: String,
        page: Int,
    ): PageDto<MediaItemDto>

    suspend fun movieList(
        list: MovieList,
        language: String,
        region: String,
        page: Int,
    ): PageDto<MediaItemDto>

    suspend fun tvList(
        list: TvList,
        language: String,
        timezone: String?,
        page: Int,
    ): PageDto<MediaItemDto>

    suspend fun popularPeople(language: String, page: Int): PageDto<MediaItemDto>

    suspend fun movieDetail(
        id: Int,
        language: String,
        append: String,
        sessionId: String?,
    ): MovieDetailDto

    suspend fun tvDetail(
        id: Int,
        language: String,
        append: String,
        sessionId: String?,
    ): TvDetailDto

    suspend fun videos(media: MediaType, id: Int, language: String): VideosDto

    suspend fun season(seriesId: Int, seasonNumber: Int, language: String): SeasonDto

    suspend fun person(
        id: Int,
        language: String,
        append: String = "combined_credits",
    ): PersonDetailDto

    suspend fun searchMulti(
        query: String,
        language: String,
        includeAdult: Boolean,
        page: Int,
    ): PageDto<MediaItemDto>

    suspend fun account(sessionId: String): AccountDto

    suspend fun accountStates(
        media: MediaType,
        id: Int,
        sessionId: String,
    ): AccountStatesDto

    suspend fun accountList(
        bearer: UserBearer,
        accountObjectId: String,
        media: MediaType,
        list: AccountList,
        language: String,
        page: Int,
        sortBy: String = "created_at.desc",
    ): PageDto<MediaItemDto>

    suspend fun setWatchlist(
        accountId: Int,
        sessionId: String,
        body: WatchlistBody,
    ): StatusDto

    suspend fun setFavorite(
        accountId: Int,
        sessionId: String,
        body: FavoriteBody,
    ): StatusDto

    suspend fun rate(
        media: MediaType,
        id: Int,
        sessionId: String,
        body: RatingBody,
    ): StatusDto

    suspend fun unrate(media: MediaType, id: Int, sessionId: String): StatusDto

    suspend fun createRequestToken(body: RequestTokenBody): RequestTokenDto

    suspend fun createAccessToken(body: AccessTokenBody): AccessTokenDto

    suspend fun convertToSession(body: ConvertSessionBody): SessionDto

    suspend fun deleteAccessToken(bearer: UserBearer, body: DeleteAccessTokenBody): StatusDto

    suspend fun deleteSession(body: DeleteSessionBody): StatusDto
}
