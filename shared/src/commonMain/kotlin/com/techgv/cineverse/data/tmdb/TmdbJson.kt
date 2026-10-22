package com.techgv.cineverse.data.tmdb

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/** JSON of every TMDB DTO: camelCase properties ↔ snake_case keys, unknown keys ignored. */
@OptIn(ExperimentalSerializationApi::class)
val TmdbJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}
