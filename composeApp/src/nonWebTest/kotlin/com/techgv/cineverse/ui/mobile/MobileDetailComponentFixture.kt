package com.techgv.cineverse.ui.mobile

import com.techgv.cineverse.data.auth.*
import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.settings.*
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Detail-only synthetic data; never uses a network, account, or persisted store. */
internal class MobileDetailComponentFixture(scope: CoroutineScope) {
    val api = MobileDetailComponentApi()
    val generation = GenerationCounter()
    private val settingsStore = MobileSettingsStore()
    val settings = SettingsRepository(settingsStore)
    val catalog = CatalogRepository(api, generation, settings::current)
    val auth = MobileDetailComponentAuth()
    val guest = MobileGuestStore()
    val accountWrites = mutableListOf<AuthSession>()
    val library = LibraryRepository(auth.session, GuestLibrarySource(guest), object : AccountLibrarySource {
        override suspend fun page(session: AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage = error("No lists")
        override fun invalidateLists() = Unit
        override suspend fun states(session: AuthSession, ref: MediaRef) = LibraryState(true, false, false, null, emptySet())
        override suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState) {
            accountWrites += session
        }
    })
    val graph = MobileApplication(catalog, generation, settings, auth, library,
        com.techgv.cineverse.data.search.RecentQueryStore(settingsStore), scope, inertApproval(auth, scope), inertImporter(library))
    val raw = MutableStateFlow<Result<Settings>>(Result.success(Settings()))
    var settingsSubscriptions = 0
    val rawSettings: Flow<Settings> = flow {
        settingsSubscriptions++
        raw.collect { emit(it.getOrThrow()) }
    }
    val snapshots = MutableStateFlow(LibraryStateSnapshot(LibraryState(true, false, false, null, emptySet()), true, null))
    var accountSubscriptions = 0
    var activeAccountSubscriptions = 0
    var accountFailure = false
    val accountState: Flow<LibraryStateSnapshot> = flow {
        accountSubscriptions++
        activeAccountSubscriptions++
        try {
            snapshots.collect {
                if (accountFailure) throw StorageException("synthetic Detail library read")
                emit(it)
            }
        } finally { activeAccountSubscriptions-- }
    }
    fun guest() { auth.emissions.tryEmit(null) }
    fun failSettings() { raw.value = Result.failure(StorageException("synthetic Detail settings read")) }
}

internal class MobileDetailComponentAuth : AuthRepository {
    val emissions = MutableSharedFlow<AuthSession?>(replay = 1)
    var fail = false
    override val session = emissions.map {
        if (fail) throw StorageException("synthetic Detail identity read")
        it
    }
    override val sessionExpired = emptyFlow<Unit>()
    override suspend fun createRequestCode(): RequestCode = error("No sign-in")
    override suspend fun awaitApproval(code: RequestCode): SignInOutcome = error("No sign-in")
    override suspend fun signOut(onSignedOutLocally: () -> Unit): Unit = error("No sign-out")
    override suspend fun expireSession(failed: AuthSession): Unit = error("No account changes")
}

internal class MobileDetailComponentApi : MobileUnusedTmdbApi() {
    val calls = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var ignoreCancellation = false
    var failure = false
    var wrongId = false
    override suspend fun configuration() = ConfigurationDto()
    private suspend fun before(media: String, id: Int, language: String) {
        calls += "$media/$id/$language"
        if (ignoreCancellation) withContext(NonCancellable) { gate?.await() } else gate?.await()
        if (failure) throw TmdbException(TmdbError.Network)
    }
    override suspend fun movieDetail(id: Int, language: String, append: String, sessionId: String?): MovieDetailDto {
        before("movie", id, language)
        return MovieDetailDto(if (wrongId) id + 1 else id, title = "Detail movie $language", tagline = "Fixture tagline", overview = "Fixture overview. ".repeat(40),
            releaseDate = "2026-02-03", runtime = 128, voteAverage = 8.1, voteCount = 30,
            releaseDates = ReleaseDatesDto(listOf(ReleaseDateCountryDto("IN", listOf(ReleaseDateDto("PG-13", 3))))),
            credits = CreditsDto(listOf(CastDto(431, "Detail actor", "Actual role")), listOf(CrewDto(432, "Detail director", "Director"))),
            videos = VideosDto(listOf(VideoDto(key = "aB3_dE5-gH7", name = "Detail trailer", site = "YouTube", type = "Trailer"))),
            recommendations = PageDto(1, listOf(MediaItemDto(765, title = "Detail recommendation", mediaType = "movie")), 1),
            reviews = PageDto(1, listOf(ReviewDto("review-1", "Detail reviewer", content = "Actual review body")), 1))
    }
    override suspend fun tvDetail(id: Int, language: String, append: String, sessionId: String?): TvDetailDto {
        before("tv", id, language)
        return TvDetailDto(id, name = "Detail TV", tagline = "Fixture tagline", overview = "Actual TV overview", firstAirDate = "2025-01-02",
            seasons = listOf(SeasonSummaryDto(900, 3, "Third season", 8, "2026-01-01"), SeasonSummaryDto(901, 0, "Specials", 2)),
            aggregateCredits = AggregateCreditsDto(listOf(AggregateCastDto(531, "TV actor", roles = listOf(RoleDto("TV role"))))),
            videos = VideosDto(listOf(VideoDto(key = "bad-key", site = "YouTube", type = "Trailer"))))
    }
}
