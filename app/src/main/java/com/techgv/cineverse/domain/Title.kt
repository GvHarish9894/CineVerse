package com.techgv.cineverse.domain

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
