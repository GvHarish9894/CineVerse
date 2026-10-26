package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.tmdb.SearchFacet

internal fun WebRoute.Search.searchFacet(): SearchFacet = when (facet) {
    "all" -> SearchFacet.Multi
    "movie" -> SearchFacet.Movie
    "tv" -> SearchFacet.Tv
    "person" -> SearchFacet.Person
    else -> error("Invalid Search facet")
}

internal fun SearchFacet.webValue(): String = when (this) {
    SearchFacet.Multi -> "all"
    SearchFacet.Movie -> "movie"
    SearchFacet.Tv -> "tv"
    SearchFacet.Person -> "person"
}

internal fun WebRoute.Search.saveSearchInput(values: MutableMap<String, String>) {
    values["productSearch.query"] = query
    values["productSearch.facet"] = searchFacet().name
}

internal fun WebRoute.Search.representable(): Boolean = try { parseWebRoute(url()) == this } catch (_: Throwable) { false }
internal const val SEARCH_URL_ERROR = "This input cannot be represented in a Search URL. Edit or clear it to continue; previous results are hidden."
