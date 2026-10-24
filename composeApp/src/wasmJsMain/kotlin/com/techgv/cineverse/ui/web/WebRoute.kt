@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui.web

/** Routes carry identifiers only; unfinished destinations never perform account operations. */
sealed interface WebRoute {
    data object Home : WebRoute
    data class Browse(val media: String, val list: String = "popular", val sort: String = "popularity.desc", val genres: List<Int> = emptyList()) : WebRoute
    data object People : WebRoute
    data class Detail(val media: String, val id: Int) : WebRoute
    data class Person(val id: Int) : WebRoute
    data class Season(val id: Int, val number: Int) : WebRoute
    data class Search(val query: String = "", val facet: String = "all") : WebRoute
    data class Library(val tab: String = "watchlist") : WebRoute
    data object Settings : WebRoute
    data class Trailer(val key: String, val title: String) : WebRoute
    data object NotFound : WebRoute
}

private val movieLists = setOf("popular", "now_playing", "upcoming", "top_rated")
private val tvLists = setOf("popular", "airing_today", "on_the_air", "top_rated")
private val sorts = setOf("popularity.desc", "vote_average.desc", "primary_release_date.desc", "title.asc")

fun parseWebRoute(url: String): WebRoute = try {
    require(url.length <= 8192 && url.startsWith('/') && !url.startsWith("//") && '#' !in url)
    val path = url.substringBefore('?')
    val params = linkedMapOf<String, String>()
    url.substringAfter('?', "").takeIf { it.isNotEmpty() }?.split('&')?.forEach { pair ->
        val key = decodeUrl(pair.substringBefore('=').replace("+", " "))
        require(key !in params)
        params[key] = decodeUrl(pair.substringAfter('=', "").replace("+", " "))
    }
    fun only(vararg keys: String) { require(params.keys.all { it in keys }) }
    fun positive(value: String) = value.takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()?.takeIf { it > 0 } ?: error("id")
    val parts = path.split('/')
    when {
        path == "/" -> { only(); WebRoute.Home }
        path == "/movies" || path == "/tv" -> {
            only("list", "sort", "genres")
            val media = if (path == "/movies") "movie" else "tv"
            val list = params["list"] ?: "popular"
            val sort = params["sort"] ?: "popularity.desc"
            require(list in if (media == "movie") movieLists else tvLists)
            require(sort in sorts)
            val genres = params["genres"]?.split(',')?.map(::positive)?.distinct()?.sorted().orEmpty()
            WebRoute.Browse(media, list, sort, genres)
        }
        path == "/people" -> { only(); WebRoute.People }
        path == "/settings" -> { only(); WebRoute.Settings }
        path == "/library" -> {
            only("tab"); val tab = params["tab"] ?: "watchlist"
            require(tab in setOf("watchlist", "favourites", "ratings")); WebRoute.Library(tab)
        }
        path == "/search" -> {
            only("q", "facet"); val facet = params["facet"] ?: "all"
            val query = params["q"].orEmpty()
            require(facet in setOf("all", "movie", "tv", "person") && query.none { it.code < 32 })
            WebRoute.Search(query, facet)
        }
        parts.size == 3 && parts[1] in setOf("movie", "tv", "person") -> {
            only(); val id = positive(parts[2])
            if (parts[1] == "person") WebRoute.Person(id) else WebRoute.Detail(parts[1], id)
        }
        parts.size == 5 && parts[1] == "tv" && parts[3] == "season" -> {
            only(); val number = parts[4].takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()
            require(number != null && number >= 0); WebRoute.Season(positive(parts[2]), number)
        }
        parts.size == 3 && parts[1] == "trailer" -> {
            only("title"); require(parts[2].matches(Regex("[A-Za-z0-9_-]{11}")))
            WebRoute.Trailer(parts[2], params["title"].orEmpty())
        }
        else -> WebRoute.NotFound
    }
} catch (_: Throwable) { WebRoute.NotFound }

fun WebRoute.url(): String = when (this) {
    WebRoute.Home -> "/"
    is WebRoute.Browse -> "/${if (media == "movie") "movies" else "tv"}?list=${encodeUrl(list)}" +
        (if (sort != "popularity.desc") "&sort=${encodeUrl(sort)}" else "") +
        (if (genres.isNotEmpty()) "&genres=${encodeUrl(genres.distinct().sorted().joinToString(","))}" else "")
    WebRoute.People -> "/people"
    is WebRoute.Detail -> "/$media/$id"
    is WebRoute.Person -> "/person/$id"
    is WebRoute.Season -> "/tv/$id/season/$number"
    is WebRoute.Search -> "/search?q=${encodeUrl(query)}" + if (facet != "all") "&facet=${encodeUrl(facet)}" else ""
    is WebRoute.Library -> "/library?tab=${encodeUrl(tab)}"
    WebRoute.Settings -> "/settings"
    is WebRoute.Trailer -> "/trailer/$key?title=${encodeUrl(title)}"
    WebRoute.NotFound -> "/not-found"
}

@JsFun("value => encodeURIComponent(value)")
private external fun encodeUrl(value: String): String
@JsFun("value => decodeURIComponent(value)")
private external fun decodeUrl(value: String): String
