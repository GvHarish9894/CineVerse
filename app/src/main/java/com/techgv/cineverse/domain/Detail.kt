package com.techgv.cineverse.domain

/** One "Details" row card (§7.4). */
data class Fact(val label: String, val value: String)

/**
 * A movie or TV detail page (§7.4), display-ready. [taglineInEnglish] / [overviewInEnglish] mark text
 * filled from the `en-US` fallback (D7) and shown with "(English)".
 */
data class Detail(
    val ref: MediaRef,
    val title: String,
    val tagline: String,
    val taglineInEnglish: Boolean = false,
    val overview: String,
    val overviewInEnglish: Boolean = false,
    val posterPath: String?,
    val backdropPath: String?,
    val releaseDate: String?,
    val year: String?,
    val runtimeMinutes: Int?,
    val certification: String?,
    val genreIds: List<Int>,
    val genres: List<String>,
    val voteAverage: Double,
    val voteCount: Int,
    /** First network (TV), else null. */
    val network: String?,
    /** TV only, Specials last. */
    val seasons: List<SeasonSummary>,
    /** Top billed cast (10), billing order. */
    val cast: List<CastCredit>,
    /** Director (movie, `crew.job == "Director"`) or Creator (TV, `created_by[0]`), shown after the cast. */
    val leadCrew: CastCredit?,
    /** YouTube only, official Trailer → Trailer → Teaser → other. */
    val videos: List<Video>,
    val recommendations: List<Title>,
    val reviews: List<Review>,
    val facts: List<Fact>,
) {
    fun toTitle() = Title(
        ref = ref, title = title, overview = overview, posterPath = posterPath, backdropPath = backdropPath,
        releaseDate = releaseDate, voteAverage = voteAverage, voteCount = voteCount, genreIds = genreIds,
        mediaType = ref.mediaType,
    )
}

/** A person page (§7.5), display-ready. [biographyInEnglish]: filled from the `en-US` fallback (D7). */
data class PersonDetail(
    val id: Int,
    val name: String,
    val biography: String,
    val biographyInEnglish: Boolean = false,
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val profilePath: String?,
    val department: String?,
    /** Unique `(media_type, id)` across cast and crew. */
    val creditCount: Int,
    val knownFor: List<Title>,
    /** "Acting credits" / "{department} credits". */
    val creditsTitle: String,
    val credits: List<PersonCredit>,
)
