package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.domain.MediaType
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

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

/**
 * TMDB endpoints (§3.5) with per-endpoint parameters (§3.2). Nullable @Query values are omitted.
 * Token kind (§3.1): every call uses the read token, except calls taking a `@Tag UserBearer`, which
 * use that user access token instead.
 */
interface TmdbApi {
    @GET("3/configuration")
    suspend fun configuration(): ConfigurationDto

    @GET("3/genre/{media}/list")
    suspend fun genres(@Path("media") media: MediaType, @Query("language") language: String): GenreListDto

    @GET("3/trending/all/{window}")
    suspend fun trending(
        @Path("window") window: TrendingWindow,
        @Query("language") language: String,
        @Query("page") page: Int,
    ): PageDto<MediaItemDto>

    @GET("3/movie/{list}")
    suspend fun movieList(
        @Path("list") list: MovieList,
        @Query("language") language: String,
        @Query("region") region: String,
        @Query("page") page: Int,
    ): PageDto<MediaItemDto>

    @GET("3/tv/{list}")
    suspend fun tvList(
        @Path("list") list: TvList,
        @Query("language") language: String,
        @Query("timezone") timezone: String?,
        @Query("page") page: Int,
    ): PageDto<MediaItemDto>

    @GET("3/person/popular")
    suspend fun popularPeople(@Query("language") language: String, @Query("page") page: Int): PageDto<MediaItemDto>

    @GET("3/movie/{id}")
    suspend fun movieDetail(
        @Path("id") id: Int,
        @Query("language") language: String,
        @Query("append_to_response") append: String,
        @Query("session_id") sessionId: String?,
    ): MovieDetailDto

    @GET("3/tv/{id}")
    suspend fun tvDetail(
        @Path("id") id: Int,
        @Query("language") language: String,
        @Query("append_to_response") append: String,
        @Query("session_id") sessionId: String?,
    ): TvDetailDto

    @GET("3/{media}/{id}/videos")
    suspend fun videos(@Path("media") media: MediaType, @Path("id") id: Int, @Query("language") language: String): VideosDto

    @GET("3/tv/{id}/season/{season}")
    suspend fun season(@Path("id") seriesId: Int, @Path("season") seasonNumber: Int, @Query("language") language: String): SeasonDto

    @GET("3/person/{id}")
    suspend fun person(
        @Path("id") id: Int,
        @Query("language") language: String,
        @Query("append_to_response") append: String = "combined_credits",
    ): PersonDetailDto

    @GET("3/search/multi")
    suspend fun searchMulti(
        @Query("query") query: String,
        @Query("language") language: String,
        @Query("include_adult") includeAdult: Boolean,
        @Query("page") page: Int,
    ): PageDto<MediaItemDto>

    @GET("3/account")
    suspend fun account(@Query("session_id") sessionId: String): AccountDto

    @GET("3/{media}/{id}/account_states")
    suspend fun accountStates(
        @Path("media") media: MediaType,
        @Path("id") id: Int,
        @Query("session_id") sessionId: String,
    ): AccountStatesDto

    @GET("4/account/{accountObjectId}/{media}/{list}")
    suspend fun accountList(
        @Tag bearer: UserBearer,
        @Path("accountObjectId") accountObjectId: String,
        @Path("media") media: MediaType,
        @Path("list") list: AccountList,
        @Query("language") language: String,
        @Query("page") page: Int,
        @Query("sort_by") sortBy: String = "created_at.desc",
    ): PageDto<MediaItemDto>

    @POST("3/account/{accountId}/watchlist")
    suspend fun setWatchlist(
        @Path("accountId") accountId: Int,
        @Query("session_id") sessionId: String,
        @Body body: WatchlistBody,
    ): StatusDto

    @POST("3/account/{accountId}/favorite")
    suspend fun setFavorite(
        @Path("accountId") accountId: Int,
        @Query("session_id") sessionId: String,
        @Body body: FavoriteBody,
    ): StatusDto

    @POST("3/{media}/{id}/rating")
    suspend fun rate(
        @Path("media") media: MediaType,
        @Path("id") id: Int,
        @Query("session_id") sessionId: String,
        @Body body: RatingBody,
    ): StatusDto

    @HTTP(method = "DELETE", path = "3/{media}/{id}/rating")
    suspend fun unrate(@Path("media") media: MediaType, @Path("id") id: Int, @Query("session_id") sessionId: String): StatusDto

    @POST("4/auth/request_token")
    suspend fun createRequestToken(@Body body: RequestTokenBody): RequestTokenDto

    @POST("4/auth/access_token")
    suspend fun createAccessToken(@Body body: AccessTokenBody): AccessTokenDto

    @POST("3/authentication/session/convert/4")
    suspend fun convertToSession(@Body body: ConvertSessionBody): SessionDto

    @HTTP(method = "DELETE", path = "4/auth/access_token", hasBody = true)
    suspend fun deleteAccessToken(@Tag bearer: UserBearer, @Body body: DeleteAccessTokenBody): StatusDto

    @HTTP(method = "DELETE", path = "3/authentication/session", hasBody = true)
    suspend fun deleteSession(@Body body: DeleteSessionBody): StatusDto
}
