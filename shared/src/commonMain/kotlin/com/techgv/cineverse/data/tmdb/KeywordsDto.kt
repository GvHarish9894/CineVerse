package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.domain.MediaType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class KeywordDto(val id: Int, val name: String)

/** Null is an explicit upstream null; empty is a supplied empty array. Missing/malformed fields fail decoding. */
@Serializable
data class KeywordsDto(val id: Int, val keywords: List<KeywordDto>?)

@Serializable
private data class TvKeywordsDto(val id: Int, val results: List<KeywordDto>?)

// Unlike optional detail fields, a missing keyword list must not silently become null.
private val keywordsJson = Json(TmdbJson) { explicitNulls = true; coerceInputValues = false }

internal fun decodeKeywords(media: MediaType, body: String): KeywordsDto = when (media) {
    MediaType.Movie -> keywordsJson.decodeFromString<KeywordsDto>(body)
    MediaType.Tv -> keywordsJson.decodeFromString<TvKeywordsDto>(body).let { KeywordsDto(it.id, it.results) }
}
