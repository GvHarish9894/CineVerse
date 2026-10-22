package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Person
import com.techgv.cineverse.domain.Title
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** §3.2: paged lists stop at `min(total_pages, 500)`. */
const val MAX_PAGES = 500

val PageDto<*>.hasNextPage: Boolean get() = page < minOf(totalPages, MAX_PAGES)

private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }

/**
 * A movie / TV item, or null for a person or an unknown `media_type`. Endpoint lists (`movie/popular`, …)
 * carry no `media_type`; their caller passes it as [listType].
 */
fun MediaItemDto.toTitle(listType: MediaType? = null): Title? {
    val type = when (mediaType) {
        null -> listType
        MediaType.Movie.path -> MediaType.Movie
        MediaType.Tv.path -> MediaType.Tv
        else -> null
    } ?: return null
    val movie = type == MediaType.Movie
    return Title(
        ref = MediaRef(type, id),
        title = (if (movie) title ?: name else name ?: title).orEmpty(),
        overview = overview.orEmpty(),
        posterPath = posterPath.blankToNull(),
        backdropPath = backdropPath.blankToNull(),
        releaseDate = (if (movie) releaseDate else firstAirDate).blankToNull(),
        voteAverage = voteAverage,
        voteCount = voteCount,
        genreIds = genreIds,
        mediaType = type,
    )
}

fun MediaItemDto.toPerson(): Person = Person(
    id = id,
    name = name ?: title.orEmpty(),
    profilePath = profilePath.blankToNull(),
    knownForDepartment = knownForDepartment.blankToNull(),
    knownFor = knownFor.mapNotNull { it.toTitle() },
)

/** Mixed lists (trending, search): `media_type` decides; [listType] covers single-type endpoint lists. */
fun MediaItemDto.toHero(listType: MediaType? = null): Hero? =
    if (mediaType == "person") Hero.OfPerson(toPerson()) else toTitle(listType)?.let { Hero.OfTitle(it) }

/**
 * YouTube videos in trailer preference order (§7.4): official Trailer → Trailer → Teaser → other;
 * the sort is stable, so TMDB's order decides within a group.
 */
fun List<VideoDto>.youTubeInTrailerOrder(): List<VideoDto> =
    filter { it.site.equals("YouTube", ignoreCase = true) && it.key.isNotBlank() }
        .sortedBy {
            when {
                it.type == "Trailer" && it.official -> 0
                it.type == "Trailer" -> 1
                it.type == "Teaser" -> 2
                else -> 3
            }
        }

/**
 * Genre id → name from the cached movie and TV genre lists (§13: lists carry ids only). A failed list
 * contributes nothing: callers omit genre chips until it loads (§7.1).
 */
suspend fun TmdbRepository.genreNames(): Map<Int, String> = coroutineScope {
    val lists = MediaType.entries.map { media ->
        async {
            try {
                genres(media).genres
            } catch (_: TmdbException) {
                emptyList()
            }
        }
    }
    // TMDB returns blank names for untranslated genres (e.g. hi-IN); drop them so the hero shows no empty chips.
    lists.flatMap { it.await() }.filter { it.name.isNotBlank() }.associate { it.id to it.name }
}
