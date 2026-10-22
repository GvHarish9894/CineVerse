package com.techgv.cineverse.domain

/** One entry of a TV show's `seasons[]`; season 0 is Specials. */
data class SeasonSummary(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int,
    val airDate: String?,
    val posterPath: String?,
) {
    val isSpecials: Boolean get() = seasonNumber == 0
    val label: String get() = if (isSpecials) "Specials" else "Season $seasonNumber"
}

data class Season(val seriesId: Int, val seasonNumber: Int, val name: String, val episodes: List<Episode>)
