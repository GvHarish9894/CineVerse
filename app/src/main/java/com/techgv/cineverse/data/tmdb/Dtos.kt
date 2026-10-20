package com.techgv.cineverse.data.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

// Property names are camelCase; TmdbJson maps them to TMDB's snake_case. Every *_path and date is
// nullable (TMDB returns null or ""), and vote_average is always Double.

@Serializable
data class StatusDto(
    val statusCode: Int? = null,
    val statusMessage: String? = null,
    val success: Boolean? = null,
)

@Serializable
data class PageDto<T>(
    val page: Int = 1,
    val results: List<T> = emptyList(),
    val totalPages: Int = 0,
    val totalResults: Int = 0,
)

@Serializable
data class ConfigurationDto(val images: ImagesConfigDto? = null)

@Serializable
data class ImagesConfigDto(
    val secureBaseUrl: String? = null,
    val posterSizes: List<String> = emptyList(),
    val backdropSizes: List<String> = emptyList(),
    val profileSizes: List<String> = emptyList(),
    val stillSizes: List<String> = emptyList(),
)

@Serializable
data class GenreDto(val id: Int, val name: String = "")

@Serializable
data class GenreListDto(val genres: List<GenreDto> = emptyList())

/** Item of trending / lists / search / recommendations / known_for / v4 account lists. */
@Serializable
data class MediaItemDto(
    val id: Int,
    val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    val originalTitle: String? = null,
    val originalName: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val profilePath: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val genreIds: List<Int> = emptyList(),
    val voteAverage: Double = 0.0,
    val voteCount: Int = 0,
    val popularity: Double = 0.0,
    val adult: Boolean = false,
    val knownForDepartment: String? = null,
    val knownFor: List<MediaItemDto> = emptyList(),
    val accountRating: AccountRatingDto? = null,
)

@Serializable
data class AccountRatingDto(val value: Double, val createdAt: String? = null)

@Serializable
data class CastDto(
    val id: Int,
    val name: String = "",
    val character: String? = null,
    val profilePath: String? = null,
    val order: Int? = null,
)

@Serializable
data class CrewDto(
    val id: Int,
    val name: String = "",
    val job: String? = null,
    val department: String? = null,
    val profilePath: String? = null,
)

@Serializable
data class CreditsDto(val cast: List<CastDto> = emptyList(), val crew: List<CrewDto> = emptyList())

@Serializable
data class VideoDto(
    val id: String = "",
    val key: String = "",
    val name: String = "",
    val site: String = "",
    val type: String = "",
    val official: Boolean = false,
    @SerialName("iso_639_1") val language: String? = null,
    val publishedAt: String? = null,
)

@Serializable
data class VideosDto(val results: List<VideoDto> = emptyList())

@Serializable
data class AuthorDetailsDto(
    val name: String? = null,
    val username: String? = null,
    val avatarPath: String? = null,
    val rating: Double? = null,
)

@Serializable
data class ReviewDto(
    val id: String = "",
    val author: String = "",
    val authorDetails: AuthorDetailsDto? = null,
    val content: String = "",
    val createdAt: String? = null,
    val url: String? = null,
)

@Serializable
data class ReleaseDateDto(val certification: String? = null, val type: Int? = null, val releaseDate: String? = null)

@Serializable
data class ReleaseDateCountryDto(
    @SerialName("iso_3166_1") val country: String = "",
    val releaseDates: List<ReleaseDateDto> = emptyList(),
)

@Serializable
data class ReleaseDatesDto(val results: List<ReleaseDateCountryDto> = emptyList())

@Serializable
data class ContentRatingDto(@SerialName("iso_3166_1") val country: String = "", val rating: String? = null)

@Serializable
data class ContentRatingsDto(val results: List<ContentRatingDto> = emptyList())

@Serializable
data class AccountStatesDto(
    val id: Int? = null,
    val favorite: Boolean = false,
    val watchlist: Boolean = false,
    /** TMDB sends `false` when unrated, `{"value": 7.5}` when rated. */
    val rated: JsonElement? = null,
) {
    val ratedValue: Double? get() = (rated as? JsonObject)?.get("value")?.jsonPrimitive?.doubleOrNull
}

@Serializable
data class MovieDetailDto(
    val id: Int,
    val title: String = "",
    val originalTitle: String? = null,
    val tagline: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
    val runtime: Int? = null,
    val status: String? = null,
    val originalLanguage: String? = null,
    val genres: List<GenreDto> = emptyList(),
    val voteAverage: Double = 0.0,
    val voteCount: Int = 0,
    val credits: CreditsDto? = null,
    val videos: VideosDto? = null,
    val recommendations: PageDto<MediaItemDto>? = null,
    val reviews: PageDto<ReviewDto>? = null,
    val releaseDates: ReleaseDatesDto? = null,
    val accountStates: AccountStatesDto? = null,
)

@Serializable
data class NetworkDto(val id: Int, val name: String = "")

@Serializable
data class CreatorDto(val id: Int, val name: String = "", val profilePath: String? = null)

@Serializable
data class SeasonSummaryDto(
    val id: Int,
    val seasonNumber: Int,
    val name: String = "",
    val episodeCount: Int = 0,
    val airDate: String? = null,
    val posterPath: String? = null,
    val overview: String? = null,
)

@Serializable
data class EpisodeDto(
    val id: Int,
    val episodeNumber: Int = 0,
    val seasonNumber: Int = 0,
    val name: String = "",
    val overview: String? = null,
    val airDate: String? = null,
    val runtime: Int? = null,
    val stillPath: String? = null,
    val voteAverage: Double = 0.0,
    val voteCount: Int = 0,
    val episodeType: String? = null,
    val guestStars: List<CastDto> = emptyList(),
)

@Serializable
data class RoleDto(val character: String? = null, val episodeCount: Int = 0)

@Serializable
data class AggregateCastDto(
    val id: Int,
    val name: String = "",
    val profilePath: String? = null,
    val roles: List<RoleDto> = emptyList(),
    val totalEpisodeCount: Int = 0,
)

@Serializable
data class JobDto(val job: String? = null, val episodeCount: Int = 0)

@Serializable
data class AggregateCrewDto(
    val id: Int,
    val name: String = "",
    val department: String? = null,
    val jobs: List<JobDto> = emptyList(),
)

@Serializable
data class AggregateCreditsDto(
    val cast: List<AggregateCastDto> = emptyList(),
    val crew: List<AggregateCrewDto> = emptyList(),
)

@Serializable
data class TvDetailDto(
    val id: Int,
    val name: String = "",
    val originalName: String? = null,
    val tagline: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val firstAirDate: String? = null,
    val lastAirDate: String? = null,
    val episodeRunTime: List<Int> = emptyList(),
    val lastEpisodeToAir: EpisodeDto? = null,
    val status: String? = null,
    val type: String? = null,
    val originalLanguage: String? = null,
    val genres: List<GenreDto> = emptyList(),
    val networks: List<NetworkDto> = emptyList(),
    val numberOfSeasons: Int = 0,
    val numberOfEpisodes: Int = 0,
    val seasons: List<SeasonSummaryDto> = emptyList(),
    val createdBy: List<CreatorDto> = emptyList(),
    val voteAverage: Double = 0.0,
    val voteCount: Int = 0,
    val aggregateCredits: AggregateCreditsDto? = null,
    val videos: VideosDto? = null,
    val recommendations: PageDto<MediaItemDto>? = null,
    val reviews: PageDto<ReviewDto>? = null,
    val contentRatings: ContentRatingsDto? = null,
    val accountStates: AccountStatesDto? = null,
)

@Serializable
data class SeasonDto(
    val id: Int,
    val seasonNumber: Int,
    val name: String = "",
    val overview: String? = null,
    val airDate: String? = null,
    val posterPath: String? = null,
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
data class PersonCreditDto(
    val id: Int,
    val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    val character: String? = null,
    val job: String? = null,
    val department: String? = null,
    val episodeCount: Int? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val voteAverage: Double = 0.0,
    val voteCount: Int = 0,
)

@Serializable
data class CombinedCreditsDto(
    val cast: List<PersonCreditDto> = emptyList(),
    val crew: List<PersonCreditDto> = emptyList(),
)

@Serializable
data class PersonDetailDto(
    val id: Int,
    val name: String = "",
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    val placeOfBirth: String? = null,
    val profilePath: String? = null,
    val knownForDepartment: String? = null,
    val combinedCredits: CombinedCreditsDto? = null,
)

@Serializable
data class AccountDto(
    val id: Int,
    val username: String = "",
    val name: String? = null,
    @SerialName("iso_639_1") val language: String? = null,
    @SerialName("iso_3166_1") val region: String? = null,
    val includeAdult: Boolean = false,
)

// --- auth & write bodies ---

@Serializable
data class RequestTokenBody(val redirectTo: String)

@Serializable
data class RequestTokenDto(val requestToken: String)

@Serializable
data class AccessTokenBody(val requestToken: String)

@Serializable
data class AccessTokenDto(val accessToken: String, val accountId: String)

@Serializable
data class ConvertSessionBody(val accessToken: String)

@Serializable
data class SessionDto(val sessionId: String)

@Serializable
data class DeleteAccessTokenBody(val accessToken: String)

@Serializable
data class DeleteSessionBody(val sessionId: String)

@Serializable
data class WatchlistBody(val mediaType: String, val mediaId: Int, val watchlist: Boolean)

@Serializable
data class FavoriteBody(val mediaType: String, val mediaId: Int, val favorite: Boolean)

@Serializable
data class RatingBody(val value: Double)
