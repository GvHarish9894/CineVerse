package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.storage.*
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/** Person-only fake: all other API calls fail, and every image path is absent. */
internal class WebPersonFakeApi : TmdbApi {
    data class Request(val id: Int, val language: String, val append: String)
    val requests = mutableListOf<Request>()
    var respond: suspend (Int, String) -> PersonDetailDto = { id, _ -> webPersonDto(id) }
    override suspend fun person(id: Int, language: String, append: String): PersonDetailDto {
        requests += Request(id, language, append)
        return respond(id, language)
    }
    private fun unused(): Nothing = error("Only person reads are allowed in WebPerson tests")
    override suspend fun configuration(): ConfigurationDto = unused()
    override suspend fun genres(media: MediaType, language: String): GenreListDto = unused()
    override suspend fun trending(window: TrendingWindow, language: String, page: Int): PageDto<MediaItemDto> = unused()
    override suspend fun movieList(list: MovieList, language: String, region: String, page: Int): PageDto<MediaItemDto> = unused()
    override suspend fun tvList(list: TvList, language: String, timezone: String?, page: Int): PageDto<MediaItemDto> = unused()
    override suspend fun discover(media: MediaType, genreIds: List<Int>, sort: DiscoverSort, language: String, region: String?, page: Int): PageDto<MediaItemDto> = unused()
    override suspend fun keywords(media: MediaType, id: Int): KeywordsDto = unused()
    override suspend fun popularPeople(language: String, page: Int): PageDto<MediaItemDto> = unused()
    override suspend fun movieDetail(id: Int, language: String, append: String, sessionId: String?): MovieDetailDto = unused()
    override suspend fun tvDetail(id: Int, language: String, append: String, sessionId: String?): TvDetailDto = unused()
    override suspend fun videos(media: MediaType, id: Int, language: String): VideosDto = unused()
    override suspend fun season(seriesId: Int, seasonNumber: Int, language: String): SeasonDto = unused()
    override suspend fun searchMulti(query: String, language: String, includeAdult: Boolean, page: Int): PageDto<MediaItemDto> = unused()
    override suspend fun account(sessionId: String): AccountDto = unused()
    override suspend fun accountStates(media: MediaType, id: Int, sessionId: String): AccountStatesDto = unused()
    override suspend fun accountList(bearer: UserBearer, accountObjectId: String, media: MediaType, list: AccountList, language: String, page: Int, sortBy: String): PageDto<MediaItemDto> = unused()
    override suspend fun setWatchlist(accountId: Int, sessionId: String, body: WatchlistBody): StatusDto = unused()
    override suspend fun setFavorite(accountId: Int, sessionId: String, body: FavoriteBody): StatusDto = unused()
    override suspend fun rate(media: MediaType, id: Int, sessionId: String, body: RatingBody): StatusDto = unused()
    override suspend fun unrate(media: MediaType, id: Int, sessionId: String): StatusDto = unused()
    override suspend fun createRequestToken(body: RequestTokenBody): RequestTokenDto = unused()
    override suspend fun createAccessToken(body: AccessTokenBody): AccessTokenDto = unused()
    override suspend fun convertToSession(body: ConvertSessionBody): SessionDto = unused()
    override suspend fun deleteAccessToken(bearer: UserBearer, body: DeleteAccessTokenBody): StatusDto = unused()
    override suspend fun deleteSession(body: DeleteSessionBody): StatusDto = unused()
}

internal fun webPersonDto(id: Int = 42, biography: String = "Actual fixture biography.") = PersonDetailDto(
    id, name = "Fixture person", biography = biography, birthday = "1970-01-02", deathday = "2020-03-04",
    placeOfBirth = "Fixture town", knownForDepartment = "Acting",
    combinedCredits = CombinedCreditsDto(cast = listOf(
        PersonCreditDto(7, mediaType = "movie", title = "Fixture film", character = "Lead", releaseDate = "2024-01-01", voteAverage = 8.1, voteCount = 20),
        PersonCreditDto(7, mediaType = "movie", title = "Fixture film", character = "Other role", releaseDate = "2024-01-01", voteAverage = 8.1, voteCount = 20),
        PersonCreditDto(9, mediaType = "tv", name = "Fixture series", character = "Guest", firstAirDate = "2023-02-01", voteAverage = 9.9, voteCount = 0),
    )),
)

internal class WebPersonFixture {
    val generation = GenerationCounter()
    val api = WebPersonFakeApi()
    val cache = TmdbCache(generation)
    val catalog = CatalogRepository(api, generation, { error("Person must use its immutable language, not repository settings") }, cache = cache)
    val entry = WebEntry(WebRoute.Person(42), mutableMapOf())
    val storage = MutableStateFlow<BrowserStorageFailure?>(null)
    var captured: Settings? = Settings()
    var failure: StorageException? = null
    private val revision = MutableStateFlow(0)
    var subscriptions = 0
    val settings: Flow<Settings> = flow {
        subscriptions++
        revision.collect {
            failure?.let { throw it }
            captured?.let { emit(it) }
        }
    }
    fun emit(value: Settings) { captured = value; failure = null; revision.value++ }
    fun fail(value: StorageException = BrowserStorageException(BrowserStorageFailure.Corrupt)) { failure = value; revision.value++ }
    fun evict() = cache.invalidate(bumpGeneration = false) { true }
    var retries = 0
    var retryHook: () -> Unit = {}
    val reports = mutableListOf<StorageException>()
    val destinations = mutableListOf<MediaRef>()
    var backs = 0
    fun owner(scope: CoroutineScope, saved: WebEntry = entry) = WebPersonOwner(
        saved, catalog, generation, settings, storage,
        { retries++; retryHook() }, { reports += it }, { destinations += it }, { backs++ }, scope,
    )
}
