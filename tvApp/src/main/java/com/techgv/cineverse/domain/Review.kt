package com.techgv.cineverse.domain

/**
 * A TMDB review with plain-text [text] (HTML / markdown stripped). The avatar is either a TMDB image
 * [avatarPath] or an absolute [avatarUrl] (gravatar), never both.
 */
data class Review(
    val id: String,
    val author: String,
    val rating: Double?,
    val avatarPath: String?,
    val avatarUrl: String?,
    val text: String,
)
