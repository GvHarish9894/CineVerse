package com.techgv.cineverse.data.tmdb

const val SEARCH_QUERY_MAX_LENGTH = 200

/** Real TMDB search endpoints, not filters over one mixed page. Not a persisted TV selection. */
enum class SearchFacet(val path: String) {
    Multi("multi"), Movie("movie"), Tv("tv"), Person("person");
}

internal fun searchQuery(query: String, language: String, includeAdult: Boolean, page: Int): List<Pair<String, Any?>> {
    require(query.isNotBlank() && query.length <= SEARCH_QUERY_MAX_LENGTH) { "Query must be non-blank and at most $SEARCH_QUERY_MAX_LENGTH characters" }
    require(Regex("[a-z]{2}(-[A-Z]{2})?").matches(language)) { "Invalid language" }
    require(page in 1..500) { "Page must be in 1..500" }
    return listOf("query" to query, "language" to language, "include_adult" to includeAdult, "page" to page)
}

/** Only missing media_type is supplied from the endpoint; explicit types and nested known_for stay intact. */
internal fun PageDto<MediaItemDto>.withSearchProvenance(facet: SearchFacet): PageDto<MediaItemDto> =
    if (facet == SearchFacet.Multi) this else copy(results = results.map { item ->
        if (item.mediaType == null) item.copy(mediaType = facet.path) else item
    })
