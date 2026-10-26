package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.tmdb.*

import com.techgv.cineverse.domain.MediaType

/** Base for test fakes: every [TmdbApi] call the fake does not override fails loudly (the JVM tests use a Proxy). */
internal open class WebSeasonUnusedApi : TmdbApi {
    private fun unused(name: String): Nothing = throw UnsupportedOperationException("TmdbApi.$name not faked")

    override suspend fun configuration(): ConfigurationDto = unused("configuration")
    override suspend fun genres(media: MediaType, language: String): GenreListDto = unused("genres")
    override suspend fun trending(window: TrendingWindow, language: String, page: Int): PageDto<MediaItemDto> = unused("trending")
    override suspend fun movieList(list: MovieList, language: String, region: String, page: Int): PageDto<MediaItemDto> = unused("movieList")
    override suspend fun tvList(list: TvList, language: String, timezone: String?, page: Int): PageDto<MediaItemDto> = unused("tvList")
    override suspend fun discover(media: MediaType, genreIds: List<Int>, sort: DiscoverSort, language: String, region: String?, page: Int): PageDto<MediaItemDto> = unused("discover")
    override suspend fun keywords(media: MediaType, id: Int): KeywordsDto = unused("keywords")
    override suspend fun popularPeople(language: String, page: Int): PageDto<MediaItemDto> = unused("popularPeople")
    override suspend fun movieDetail(id: Int, language: String, append: String, sessionId: String?): MovieDetailDto = unused("movieDetail")
    override suspend fun tvDetail(id: Int, language: String, append: String, sessionId: String?): TvDetailDto = unused("tvDetail")
    override suspend fun videos(media: MediaType, id: Int, language: String): VideosDto = unused("videos")
    override suspend fun season(seriesId: Int, seasonNumber: Int, language: String): SeasonDto = unused("season")
    override suspend fun person(id: Int, language: String, append: String): PersonDetailDto = unused("person")
    override suspend fun searchMulti(query: String, language: String, includeAdult: Boolean, page: Int): PageDto<MediaItemDto> = unused("searchMulti")
    override suspend fun account(sessionId: String): AccountDto = unused("account")
    override suspend fun accountStates(media: MediaType, id: Int, sessionId: String): AccountStatesDto = unused("accountStates")
    override suspend fun accountList(bearer: UserBearer, accountObjectId: String, media: MediaType, list: AccountList, language: String, page: Int, sortBy: String): PageDto<MediaItemDto> = unused("accountList")
    override suspend fun setWatchlist(accountId: Int, sessionId: String, body: WatchlistBody): StatusDto = unused("setWatchlist")
    override suspend fun setFavorite(accountId: Int, sessionId: String, body: FavoriteBody): StatusDto = unused("setFavorite")
    override suspend fun rate(media: MediaType, id: Int, sessionId: String, body: RatingBody): StatusDto = unused("rate")
    override suspend fun unrate(media: MediaType, id: Int, sessionId: String): StatusDto = unused("unrate")
    override suspend fun createRequestToken(body: RequestTokenBody): RequestTokenDto = unused("createRequestToken")
    override suspend fun createAccessToken(body: AccessTokenBody): AccessTokenDto = unused("createAccessToken")
    override suspend fun convertToSession(body: ConvertSessionBody): SessionDto = unused("convertToSession")
    override suspend fun deleteAccessToken(bearer: UserBearer, body: DeleteAccessTokenBody): StatusDto = unused("deleteAccessToken")
    override suspend fun deleteSession(body: DeleteSessionBody): StatusDto = unused("deleteSession")
}

internal class WebSeasonFake {
    val generation = com.techgv.cineverse.util.GenerationCounter()
    val preferences = kotlinx.coroutines.flow.MutableStateFlow(com.techgv.cineverse.data.settings.Settings())
    val storage = kotlinx.coroutines.flow.MutableStateFlow<com.techgv.cineverse.data.storage.BrowserStorageFailure?>(null)
    val invalidations = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val calls = mutableListOf<String>()
    val destinations = mutableListOf<WebRoute>()
    val reports = mutableListOf<com.techgv.cineverse.data.storage.StorageException>()
    var upstreamRetries = 0
    var subscriptions = 0
    var readFailure = false
    var showGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var episodeGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var stubborn = false
    var showError: TmdbError? = null
    var episodeError: TmdbError? = null
    var show = TvDetailDto(7, name = "Real show", networks = listOf(NetworkDto(2, "Real network")),
        seasons = listOf(SeasonSummaryDto(10, 0, "Specials"), SeasonSummaryDto(17, 7, "Seventh")))
    var season = SeasonDto(17, 7, "Seventh", episodes = listOf(
        EpisodeDto(91, episodeNumber = 4, seasonNumber = 7, name = "Actual episode", overview = "Actual overview",
            airDate = "2020-01-02", runtime = 42, voteAverage = 8.5, voteCount = 0,
            guestStars = listOf(CastDto(33, "Actual guest", character = "Actual role")))))
    private val api = object : WebSeasonUnusedApi() {
        override suspend fun tvDetail(id: Int, language: String, append: String, sessionId: String?): TvDetailDto {
            calls += "show:$id:$language"
            val result = show
            val gate = showGate
            if (stubborn) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate?.await() } else gate?.await()
            showError?.let { throw TmdbException(it) }
            return result
        }
        override suspend fun season(seriesId: Int, seasonNumber: Int, language: String): SeasonDto {
            calls += "season:$seriesId:$seasonNumber:$language"
            val result = season
            val gate = episodeGate
            if (stubborn) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate?.await() } else gate?.await()
            episodeError?.let { throw TmdbException(it) }
            return result
        }
    }
    val catalog = CatalogRepository(api, generation, { preferences.value })
    val raw = kotlinx.coroutines.flow.flow {
        subscriptions++
        if (readFailure) throw com.techgv.cineverse.data.storage.BrowserStorageException(com.techgv.cineverse.data.storage.BrowserStorageFailure.Corrupt)
        emitAllPreferences()
    }
    private suspend fun kotlinx.coroutines.flow.FlowCollector<com.techgv.cineverse.data.settings.Settings>.emitAllPreferences() {
        preferences.collect { emit(it) }
    }
    fun entry(number: Int = 7, values: Map<String, String> = emptyMap()) = WebEntry(WebRoute.Season(7, number), values.toMutableMap())
    fun owner(scope: kotlinx.coroutines.CoroutineScope, entry: WebEntry = entry(),
              retryUpstream: () -> Unit = { upstreamRetries++; storage.value = null },
              report: (com.techgv.cineverse.data.storage.StorageException) -> Unit = { reports += it }) =
        WebSeasonOwner(entry, catalog, raw, storage, generation, invalidations, scope, retryUpstream, report,
            { destinations += WebRoute.Home }, { destinations += WebRoute.Detail("tv", it) },
            { id, number -> destinations += WebRoute.Season(id, number) })
}
