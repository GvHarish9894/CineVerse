package com.techgv.cineverse.domain

import kotlinx.datetime.LocalDate

data class Episode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String,
    val overview: String,
    val airDate: LocalDate?,
    val runtimeMinutes: Int?,
    val stillPath: String?,
    val voteAverage: Double,
    val voteCount: Int,
    val isFinale: Boolean,
    val guestStars: List<CastCredit>,
) {
    /** §7.6: shows "Airs {date}" and no rating. An unknown air date counts as not aired. */
    fun isUnaired(today: LocalDate): Boolean = airDate == null || airDate > today
}
