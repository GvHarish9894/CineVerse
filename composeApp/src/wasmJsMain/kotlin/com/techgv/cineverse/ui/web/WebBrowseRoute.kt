package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.presentation.BrowseSelection

internal fun WebRoute.browseSelection(): BrowseSelection = when (this) {
    WebRoute.People -> BrowseSelection.People
    is WebRoute.Browse -> {
        require(media == "movie" || media == "tv")
        if (list == "discover" || genres.isNotEmpty() || sort != "popularity.desc") {
            BrowseSelection.Discover(if (media == "movie") MediaType.Movie else MediaType.Tv, genres, browseSort(sort))
        } else if (media == "movie") BrowseSelection.Movies(when (list) {
            "popular" -> MovieList.Popular
            "now_playing" -> MovieList.NowPlaying
            "upcoming" -> MovieList.Upcoming
            "top_rated" -> MovieList.TopRated
            else -> error("Invalid movie list")
        }) else BrowseSelection.Tv(when (list) {
            "popular" -> TvList.Popular
            "airing_today" -> TvList.AiringToday
            "on_the_air" -> TvList.OnTheAir
            "top_rated" -> TvList.TopRated
            else -> error("Invalid TV list")
        })
    }
    else -> error("Not a Browse route")
}

internal fun browseSort(value: String): DiscoverSort = when (value) {
    "popularity.desc" -> DiscoverSort.Popularity
    "vote_average.desc" -> DiscoverSort.Rating
    "primary_release_date.desc", "first_air_date.desc" -> DiscoverSort.ReleaseDate
    "title.asc", "name.asc" -> DiscoverSort.Title
    else -> error("Invalid sort")
}

internal fun DiscoverSort.webValue(media: String): String = when (this) {
    DiscoverSort.Popularity -> "popularity.desc"
    DiscoverSort.Rating -> "vote_average.desc"
    DiscoverSort.ReleaseDate -> if (media == "movie") "primary_release_date.desc" else "first_air_date.desc"
    DiscoverSort.Title -> if (media == "movie") "title.asc" else "name.asc"
}
