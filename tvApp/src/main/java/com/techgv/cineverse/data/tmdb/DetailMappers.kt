package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.domain.CastCredit
import com.techgv.cineverse.domain.Detail
import com.techgv.cineverse.domain.Episode
import com.techgv.cineverse.domain.Fact
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.PersonCredit
import com.techgv.cineverse.domain.PersonDetail
import com.techgv.cineverse.domain.Review
import com.techgv.cineverse.domain.Season
import com.techgv.cineverse.domain.SeasonSummary
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.domain.Video
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// DTO → domain for Detail (§7.4), Person (§7.5) and Season (§7.6). Pure functions; region / language
// come from the caller's Settings.

private const val TOP_BILLED = 10
private const val KNOWN_FOR = 12

fun MovieDetailDto.toDetail(region: String): Detail {
    val certification = movieCertification(releaseDates, region)
    val runtimeMinutes = runtime?.takeIf { it > 0 }
    val genreNames = genres.map { it.name }.filter { it.isNotBlank() }
    return Detail(
        ref = MediaRef(MediaType.Movie, id),
        title = title,
        tagline = tagline.orEmpty().trim(),
        overview = overview.orEmpty().trim(),
        posterPath = posterPath.nonBlank(),
        backdropPath = backdropPath.nonBlank(),
        releaseDate = releaseDate.nonBlank(),
        year = yearOf(releaseDate),
        runtimeMinutes = runtimeMinutes,
        certification = certification,
        genreIds = genres.map { it.id },
        genres = genreNames,
        voteAverage = voteAverage,
        voteCount = voteCount,
        network = null,
        seasons = emptyList(),
        cast = credits?.cast.orEmpty().sortedBy { it.order ?: Int.MAX_VALUE }.distinctBy { it.id }.take(TOP_BILLED)
            .map { CastCredit(it.id, it.name, it.profilePath.nonBlank(), it.character.orEmpty().trim()) },
        leadCrew = credits?.crew.orEmpty().firstOrNull { it.job == "Director" }
            ?.let { CastCredit(it.id, it.name, it.profilePath.nonBlank(), "Director") },
        videos = orderedYouTubeVideos(videos?.results.orEmpty()),
        recommendations = recommendations?.results.orEmpty().mapNotNull { it.toRecommendedTitle(MediaType.Movie) },
        reviews = reviews?.results.orEmpty().map { it.toReview() },
        facts = listOfNotNull(
            fact("Status", status),
            fact("Release date", formatDate(releaseDate)),
            fact("Original language", languageName(originalLanguage)),
            fact("Runtime", runtimeMinutes?.let(::formatRuntime)),
            fact("Certification", certification),
            fact("Genres", genreNames.joinToString(", ")),
        ),
    )
}

fun TvDetailDto.toDetail(region: String): Detail {
    val genreNames = genres.map { it.name }.filter { it.isNotBlank() }
    val network = networks.firstOrNull { it.name.isNotBlank() }?.name
    return Detail(
        ref = MediaRef(MediaType.Tv, id),
        title = name,
        tagline = tagline.orEmpty().trim(),
        overview = overview.orEmpty().trim(),
        posterPath = posterPath.nonBlank(),
        backdropPath = backdropPath.nonBlank(),
        releaseDate = firstAirDate.nonBlank(),
        year = yearOf(firstAirDate),
        runtimeMinutes = tvRuntime(this),
        certification = tvCertification(contentRatings, region),
        genreIds = genres.map { it.id },
        genres = genreNames,
        voteAverage = voteAverage,
        voteCount = voteCount,
        network = network,
        seasons = seasonsSpecialsLast(seasons),
        cast = aggregateCredits?.cast.orEmpty().sortedBy { it.order ?: Int.MAX_VALUE }.distinctBy { it.id }.take(TOP_BILLED)
            .map { cast ->
                val role = cast.roles.mapNotNull { it.character.nonBlank()?.trim() }.joinToString(" / ")
                CastCredit(cast.id, cast.name, cast.profilePath.nonBlank(), role)
            },
        leadCrew = createdBy.firstOrNull()?.let { CastCredit(it.id, it.name, it.profilePath.nonBlank(), "Creator") },
        videos = orderedYouTubeVideos(videos?.results.orEmpty()),
        recommendations = recommendations?.results.orEmpty().mapNotNull { it.toRecommendedTitle(MediaType.Tv) },
        reviews = reviews?.results.orEmpty().map { it.toReview() },
        facts = listOfNotNull(
            fact("Status", status),
            fact("First air date", formatDate(firstAirDate)),
            fact("Network", network),
            fact("Episodes", "${plural(numberOfSeasons, "season")} · $numberOfEpisodes".takeIf { numberOfSeasons > 0 }),
            fact("Type", type),
            fact("Original language", languageName(originalLanguage)),
        ),
    )
}

/** D7: fills only the blank tagline / overview from the `en-US` detail, marking them "(English)". */
fun Detail.withEnglishFallback(englishTagline: String?, englishOverview: String?): Detail {
    val tagline = englishTagline.orEmpty().trim()
    val overview = englishOverview.orEmpty().trim()
    return copy(
        tagline = this.tagline.ifBlank { tagline },
        taglineInEnglish = taglineInEnglish || (this.tagline.isBlank() && tagline.isNotBlank()),
        overview = this.overview.ifBlank { overview },
        overviewInEnglish = overviewInEnglish || (this.overview.isBlank() && overview.isNotBlank()),
    )
}

val Detail.needsEnglishFallback: Boolean get() = tagline.isBlank() || overview.isBlank()

/** §7.4: `episode_run_time.firstOrNull() ?: last_episode_to_air?.runtime`; null when unknown. */
internal fun tvRuntime(tv: TvDetailDto): Int? =
    (tv.episodeRunTime.firstOrNull() ?: tv.lastEpisodeToAir?.runtime)?.takeIf { it > 0 }

/**
 * §7.4 movie certification: `release_dates` of [region], else `US`: first non-blank certification
 * preferring type 3 (theatrical), then 2 (limited), then any; trimmed. Null when none.
 */
internal fun movieCertification(dates: ReleaseDatesDto?, region: String): String? {
    fun pick(country: String): String? {
        val certified = dates?.results?.firstOrNull { it.country == country }?.releaseDates.orEmpty()
            .filter { !it.certification.isNullOrBlank() }
        val best = certified.firstOrNull { it.type == 3 } ?: certified.firstOrNull { it.type == 2 } ?: certified.firstOrNull()
        return best?.certification?.trim()
    }
    return pick(region) ?: pick("US")
}

/** §7.4 TV certification: `content_ratings` of [region], else `US`; null when none. */
internal fun tvCertification(ratings: ContentRatingsDto?, region: String): String? {
    fun pick(country: String) = ratings?.results?.firstOrNull { it.country == country }?.rating.nonBlank()?.trim()
    return pick(region) ?: pick("US")
}

/** §7.4 Videos: YouTube only; official Trailer → Trailer → Teaser → other, API order within each group. */
fun orderedYouTubeVideos(videos: List<VideoDto>): List<Video> = videos
    .filter { it.site.equals("YouTube", ignoreCase = true) && it.key.isNotBlank() }
    .sortedBy {
        when {
            it.type == "Trailer" && it.official -> 0
            it.type == "Trailer" -> 1
            it.type == "Teaser" -> 2
            else -> 3
        }
    }
    .distinctBy { it.key }
    .map { Video(it.key, it.name, it.type, it.official) }

internal fun seasonsSpecialsLast(seasons: List<SeasonSummaryDto>): List<SeasonSummary> = seasons
    .sortedWith(compareBy({ it.seasonNumber == 0 }, { it.seasonNumber }))
    .distinctBy { it.seasonNumber }
    .map { SeasonSummary(it.seasonNumber, it.name, it.episodeCount, it.airDate.nonBlank(), it.posterPath.nonBlank()) }

internal fun ReviewDto.toReview(): Review {
    val avatar = authorDetails?.avatarPath.nonBlank()
    // TMDB sends gravatar avatars as "/https://secure.gravatar.com/…".
    val external = avatar?.trimStart('/')?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    return Review(
        id = id,
        author = authorDetails?.name.nonBlank() ?: author.nonBlank() ?: authorDetails?.username.nonBlank() ?: "Anonymous",
        rating = authorDetails?.rating,
        avatarPath = avatar.takeIf { external == null },
        avatarUrl = external,
        text = stripMarkup(content),
    )
}

private val ENTITIES = mapOf(
    "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ",
)

/** Review text as plain text: HTML tags, markdown links / emphasis / headings removed, entities decoded. */
fun stripMarkup(text: String): String {
    var s = text.replace("\r\n", "\n")
        .replace(Regex("(?i)<br\\s*/?>|</p>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\*\\*|__|~~|`"), "")
        .replace(Regex("(?<![\\w*])\\*(\\S(?:[^*]*\\S)?)\\*(?!\\w)"), "$1")
        .replace(Regex("(?m)^ {0,3}(#{1,6}|>)\\s*"), "")
    ENTITIES.forEach { (entity, char) -> s = s.replace(entity, char) }
    return s.replace(Regex("[ \\t]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

private fun MediaItemDto.toRecommendedTitle(defaultType: MediaType): Title? {
    val type = when (mediaType) {
        null -> defaultType
        "movie" -> MediaType.Movie
        "tv" -> MediaType.Tv
        else -> return null
    }
    return Title(
        ref = MediaRef(type, id),
        title = title.nonBlank() ?: name.orEmpty(),
        overview = overview.orEmpty(),
        posterPath = posterPath.nonBlank(),
        backdropPath = backdropPath.nonBlank(),
        releaseDate = (releaseDate.nonBlank() ?: firstAirDate).nonBlank(),
        voteAverage = voteAverage,
        voteCount = voteCount,
        genreIds = genreIds,
        mediaType = type,
    )
}

// --- Person (§7.5) ---

fun PersonDetailDto.toPersonDetail(): PersonDetail {
    val cast = combinedCredits?.cast.orEmpty().filter { it.ref() != null }
    val crew = combinedCredits?.crew.orEmpty().filter { it.ref() != null }
    val unique = (cast + crew).distinctBy { it.ref() }
    val department = knownForDepartment.nonBlank()?.trim() ?: "Acting"
    val rowCredits = if (department == "Acting") cast else crew.filter { it.department == department }
    return PersonDetail(
        id = id,
        name = name,
        biography = biography.orEmpty().trim(),
        birthday = birthday.nonBlank(),
        deathday = deathday.nonBlank(),
        placeOfBirth = placeOfBirth.nonBlank(),
        profilePath = profilePath.nonBlank(),
        department = knownForDepartment.nonBlank(),
        creditCount = unique.size,
        knownFor = unique.sortedByDescending { it.voteCount }.take(KNOWN_FOR).map { it.toTitle() },
        creditsTitle = "$department credits",
        credits = personCredits(rowCredits),
    )
}

/** D7: fills a blank biography from the `en-US` person, marking it "(English)". */
fun PersonDetail.withEnglishBiography(englishBiography: String?): PersonDetail {
    val english = englishBiography.orEmpty().trim()
    if (biography.isNotBlank() || english.isBlank()) return this
    return copy(biography = english, biographyInEnglish = true)
}

/**
 * One card per `(media_type, id)` (several characters / jobs on one title are joined), sorted by date
 * desc with blank dates last.
 */
internal fun personCredits(credits: List<PersonCreditDto>): List<PersonCredit> = credits
    .groupBy { it.ref()!! }
    .map { (ref, entries) ->
        val first = entries.first()
        val characters = entries.mapNotNull { it.character.nonBlank()?.trim() }.distinct()
        val jobs = entries.mapNotNull { it.job.nonBlank()?.trim() }.distinct()
        val episodes = entries.maxOf { it.episodeCount ?: 0 }
        PersonCredit(
            ref = ref,
            title = first.title.nonBlank() ?: first.name.orEmpty(),
            date = first.date(),
            posterPath = first.posterPath.nonBlank(),
            role = when {
                characters.isNotEmpty() -> "as " + characters.joinToString(" / ")
                jobs.isNotEmpty() -> jobs.joinToString(" / ")
                episodes > 0 -> plural(episodes, "episode")
                else -> ""
            },
        )
    }
    .sortedWith(compareBy<PersonCredit> { it.date == null }.thenByDescending { it.date })

private fun PersonCreditDto.ref(): MediaRef? = when (mediaType) {
    "movie" -> MediaRef(MediaType.Movie, id)
    "tv" -> MediaRef(MediaType.Tv, id)
    else -> null
}

private fun PersonCreditDto.date(): String? = releaseDate.nonBlank() ?: firstAirDate.nonBlank()

private fun PersonCreditDto.toTitle(): Title {
    val ref = ref()!!
    return Title(
        ref = ref,
        title = title.nonBlank() ?: name.orEmpty(),
        overview = "",
        posterPath = posterPath.nonBlank(),
        backdropPath = backdropPath.nonBlank(),
        releaseDate = date(),
        voteAverage = voteAverage,
        voteCount = voteCount,
        genreIds = emptyList(),
        mediaType = ref.mediaType,
    )
}

// --- Season (§7.6) ---

fun SeasonDto.toSeason(seriesId: Int): Season =
    Season(seriesId, seasonNumber, name, episodes.sortedBy { it.episodeNumber }.map { it.toEpisode() })

internal fun EpisodeDto.toEpisode() = Episode(
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    name = name,
    overview = overview.orEmpty().trim(),
    airDate = parseDate(airDate),
    runtimeMinutes = runtime?.takeIf { it > 0 },
    stillPath = stillPath.nonBlank(),
    voteAverage = voteAverage,
    voteCount = voteCount,
    isFinale = episodeType == "finale",
    guestStars = guestStars.distinctBy { it.id }
        .map { CastCredit(it.id, it.name, it.profilePath.nonBlank(), it.character.orEmpty().trim()) },
)

// --- formatting ---

private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

fun parseDate(iso: String?): LocalDate? = iso.nonBlank()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** "Sep 25, 2026"; null for a blank or unparsable date. */
fun formatDate(iso: String?): String? = parseDate(iso)?.let(::formatDate)

fun formatDate(date: LocalDate): String = DATE_FORMAT.format(date)

fun yearOf(iso: String?): String? = parseDate(iso)?.year?.toString()

/** "2h 1m", "45m". */
fun formatRuntime(minutes: Int): String {
    val h = minutes / 60
    return if (h > 0) "${h}h ${minutes % 60}m" else "${minutes}m"
}

/** ISO 639-1 → English display name ("ta" → "Tamil"); the code itself when unknown. */
internal fun languageName(code: String?): String? {
    val c = code.nonBlank() ?: return null
    return Locale.forLanguageTag(c).getDisplayLanguage(Locale.ENGLISH).ifBlank { c }
}

private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

private fun fact(label: String, value: String?) = value.nonBlank()?.let { Fact(label, it) }

private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }
