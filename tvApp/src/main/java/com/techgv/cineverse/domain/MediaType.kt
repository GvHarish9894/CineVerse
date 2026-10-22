package com.techgv.cineverse.domain

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

/** `toString()` is the TMDB path segment / `media_type` value (Retrofit @Path uses it). */
@Keep // route argument: serializer must survive R8
@Serializable
enum class MediaType(val path: String) {
    Movie("movie"),
    Tv("tv");

    override fun toString() = path
}
