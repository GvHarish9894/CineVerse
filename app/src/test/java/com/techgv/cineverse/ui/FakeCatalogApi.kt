package com.techgv.cineverse.ui

import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.GenreDto
import com.techgv.cineverse.data.tmdb.GenreListDto
import com.techgv.cineverse.data.tmdb.MediaItemDto
import com.techgv.cineverse.data.tmdb.MovieList
import com.techgv.cineverse.data.tmdb.PageDto
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.data.tmdb.TvList
import com.techgv.cineverse.data.tmdb.VideoDto
import com.techgv.cineverse.data.tmdb.VideosDto
import com.techgv.cineverse.data.tmdb.unusedTmdbApi
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Catalog endpoints of Home / Browse / Search. Each call is recorded by key (e.g. `movie/popular/en-US/1`);
 * a key with a [gates] entry suspends until that gate completes. Page items are titled
 * `"<list>@<language>-<page>-<n>"` so a test can tell which request a committed list came from.
 */
class FakeCatalogApi : TmdbApi by unusedTmdbApi {
    val calls = mutableListOf<String>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    var totalPages = 1
    var videos: List<VideoDto> = emptyList()

    fun gate(key: String) = CompletableDeferred<Unit>().also { gates[key] = it }

    private suspend fun <T> call(key: String, result: () -> T): T {
        calls += key
        gates[key]?.await()
        return result()
    }

    private fun page(label: String, page: Int, mediaType: String?) = PageDto(
        page = page,
        results = (0 until 3).map { MediaItemDto(id = page * 100 + it, mediaType = mediaType, title = "$label-$page-$it", name = "$label-$page-$it") },
        totalPages = totalPages,
    )

    override suspend fun trending(window: TrendingWindow, language: String, page: Int) =
        call("trending/$window/$language/$page") { page("trending@$language", page, "movie") }

    override suspend fun movieList(list: MovieList, language: String, region: String, page: Int) =
        call("movie/$list/$language/$page") { page("$list@$language", page, null) }

    override suspend fun tvList(list: TvList, language: String, timezone: String?, page: Int) =
        call("tv/$list/$language/$page") { page("$list@$language", page, null) }

    override suspend fun popularPeople(language: String, page: Int) =
        call("people/$language/$page") { page("people@$language", page, "person") }

    override suspend fun searchMulti(query: String, language: String, includeAdult: Boolean, page: Int) =
        call("search/$query/$page") { page("search:$query", page, "movie") }

    override suspend fun genres(media: MediaType, language: String) =
        call("genres/$media/$language") { GenreListDto(listOf(GenreDto(if (media == MediaType.Movie) 28 else 18, "$media@$language"))) }

    override suspend fun videos(media: MediaType, id: Int, language: String) =
        call("videos/$id/$language") { VideosDto(videos) }
}

/** Repository over [api] reading [settings] like the app does; [appGeneration] is shared with the ViewModels. */
fun fakeRepository(api: TmdbApi, appGeneration: GenerationCounter, settings: MutableStateFlow<Settings>) =
    TmdbRepository(api, appGeneration, settings = { settings.value }, timezone = { "UTC" })
