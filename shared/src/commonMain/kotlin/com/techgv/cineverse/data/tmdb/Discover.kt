package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.domain.MediaType

/** Generic discover ordering, not a replacement for curated NowPlaying / Upcoming / OnTheAir lists. */
enum class DiscoverSort {
    Popularity, Rating, ReleaseDate, Title;

    internal fun queryValue(media: MediaType): String = when (this) {
        Popularity -> "popularity.desc"
        Rating -> "vote_average.desc"
        ReleaseDate -> if (media == MediaType.Movie) "primary_release_date.desc" else "first_air_date.desc"
        Title -> if (media == MediaType.Movie) "title.asc" else "name.asc"
    }
}

/** Comma-separated genres are TMDB's AND filter; order/duplicates never change request identity. */
internal fun canonicalGenreIds(genreIds: List<Int>): List<Int> {
    require(genreIds.all { it in 1..999999 }) { "Genre IDs must be positive and at most six digits" }
    return genreIds.distinct().sorted().also { require(it.size <= 20) { "At most 20 unique genres" } }
}

internal fun discoverQuery(
    media: MediaType,
    genreIds: List<Int>,
    sort: DiscoverSort,
    language: String,
    region: String?,
    page: Int,
): List<Pair<String, Any?>> {
    require(page in 1..500) { "Page must be in 1..500" }
    require(Regex("[a-z]{2}(-[A-Z]{2})?").matches(language)) { "Invalid language" }
    if (media == MediaType.Movie && region != null) require(Regex("[A-Z]{2}").matches(region)) { "Invalid region" }
    val genres = canonicalGenreIds(genreIds)
    return listOf(
        "language" to language,
        "region" to region.takeIf { media == MediaType.Movie },
        "page" to page,
        "with_genres" to genres.takeIf { it.isNotEmpty() }?.joinToString(","),
        "sort_by" to sort.queryValue(media),
    )
}

internal fun requireCatalogId(id: Int) {
    require(id > 0) { "TMDB ID must be positive" }
}
