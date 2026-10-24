package com.techgv.cineverse.ui.gallery

import androidx.compose.ui.graphics.Color
import com.techgv.cineverse.ui.components.ArtPalette
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Preview-only models for the gallery, read from design/sample-tmdb.json (bundled as files/sample-tmdb.json).
 * Not domain types: L1 owns the domain; P8/P9 screens bind primitives to those instead.
 */
data class PreviewTitle(
    val id: Int,
    val name: String,
    val date: String?,
    val voteAverage: Double?,
    val overview: String,
    val genres: List<String>,
    val isTv: Boolean,
    val palette: ArtPalette,
    val certification: String?,
    val status: String?,
) {
    val year: String? get() = date?.take(4)

    /** The sample has no vote_count; a missing vote_average stands for "no votes yet" (upcoming titles). */
    val voteCount: Int get() = if (voteAverage == null) 0 else 1
}

data class PreviewPerson(
    val id: Int,
    val name: String,
    val department: String,
    val palette: ArtPalette,
    val knownFor: List<String>,
    val birthday: String?,
    val placeOfBirth: String?,
)

data class GallerySamples(val movies: List<PreviewTitle>, val tv: List<PreviewTitle>, val people: List<PreviewPerson>)

@Serializable
private data class SampleFile(val movies: List<SampleTitle>, val tv: List<SampleTitle>, val people: List<SamplePerson>)

@Serializable
private data class SampleGenre(val name: String)

@Serializable
private data class SampleUi(val c1: String, val c2: String, @SerialName("certification_IN") val certification: String? = null)

@Serializable
private data class SampleTitle(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val overview: String = "",
    val genres: List<SampleGenre> = emptyList(),
    val status: String? = null,
    @SerialName("_ui") val ui: SampleUi,
)

@Serializable
private data class SampleKnownFor(val title: String)

@Serializable
private data class SamplePerson(
    val id: Int,
    val name: String,
    @SerialName("known_for_department") val department: String,
    val birthday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("known_for") val knownFor: List<SampleKnownFor> = emptyList(),
    @SerialName("_ui") val ui: SampleUi,
)

private val sampleJson = Json { ignoreUnknownKeys = true }

/** "#RRGGBB" → opaque colour; anything else is a malformed sample (thrown, shown as the gallery's error state). */
internal fun hexColor(hex: String): Color {
    require(hex.length == 7 && hex[0] == '#') { "expected #RRGGBB, got '$hex'" }
    return Color(0xFF000000 or hex.substring(1).toLong(16))
}

fun parseGallerySamples(json: String): GallerySamples {
    val file = sampleJson.decodeFromString<SampleFile>(json)
    fun SampleUi.palette() = ArtPalette(hexColor(c1), hexColor(c2))
    fun SampleTitle.preview(isTv: Boolean) = PreviewTitle(
        id = id,
        name = title ?: name ?: error("sample title $id has no title/name"),
        date = releaseDate ?: firstAirDate,
        voteAverage = voteAverage,
        overview = overview,
        genres = genres.map { it.name },
        isTv = isTv,
        palette = ui.palette(),
        certification = ui.certification,
        status = status,
    )
    return GallerySamples(
        movies = file.movies.map { it.preview(isTv = false) },
        tv = file.tv.map { it.preview(isTv = true) },
        people = file.people.map { PreviewPerson(it.id, it.name, it.department, it.ui.palette(), it.knownFor.map(SampleKnownFor::title), it.birthday, it.placeOfBirth) },
    )
}
