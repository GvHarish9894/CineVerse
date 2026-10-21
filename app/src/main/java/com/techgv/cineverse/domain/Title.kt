package com.techgv.cineverse.domain

/** Identity of a movie or TV title. */
data class MediaRef(val mediaType: MediaType, val id: Int)

/**
 * A movie or TV title as lists, trending, search and recommendations return it. Lists carry genre ids
 * only (§13); `*_path` and dates are null when TMDB sends null or "".
 */
data class Title(
    val ref: MediaRef,
    val title: String,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
    val releaseDate: String?,
    val voteAverage: Double,
    val voteCount: Int,
    val genreIds: List<Int>,
    val mediaType: MediaType,
)
