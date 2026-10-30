package com.techgv.cineverse.ui.mobile

import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.settings.*
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Synthetic Season data only; uses the existing native in-memory stores, never network or accounts. */
internal class MobileSeasonComponentFixture(scope: CoroutineScope) {
    val api = MobileSeasonComponentApi()
    val generation = GenerationCounter()
    val store = MobileSettingsStore()
    val settings = SettingsRepository(store)
    val catalog = CatalogRepository(api, generation, settings::current)
    val auth = MobileAuth()
    private val library = LibraryRepository(auth.session, GuestLibrarySource(MobileGuestStore()), object : AccountLibrarySource {
        override suspend fun page(session: com.techgv.cineverse.data.auth.AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage = error("No lists")
        override fun invalidateLists() = Unit
        override suspend fun states(session: com.techgv.cineverse.data.auth.AuthSession, ref: MediaRef) = LibraryState.Unknown
        override suspend fun write(session: com.techgv.cineverse.data.auth.AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState): Unit = error("No writes")
    })
    val graph = MobileApplication(catalog, generation, settings, auth, library,
        com.techgv.cineverse.data.search.RecentQueryStore(store), scope, inertApproval(auth, scope), inertImporter(library))
    val raw = MutableStateFlow<Result<Settings>>(Result.success(Settings()))
    var subscriptions = 0
    var activeSubscriptions = 0
    val rawSettings: Flow<Settings> = flow {
        subscriptions++; activeSubscriptions++
        try { raw.collect { emit(it.getOrThrow()) } } finally { activeSubscriptions-- }
    }
    fun failSettings() { raw.value = Result.failure(StorageException("synthetic season settings")) }
}

internal class MobileSeasonComponentApi : MobileUnusedTmdbApi() {
    val calls = mutableListOf<String>()
    var showGate: CompletableDeferred<Unit>? = null
    var episodeGate: CompletableDeferred<Unit>? = null
    var stubborn = false
    var showFailure = false
    var episodeFailure = false
    var wrongShow = false
    var wrongSeason = false
    var wrongEpisodeSeason = false
    var duplicateEpisodes = false
    var seasons = listOf(SeasonSummaryDto(90, 0, "Specials"), SeasonSummaryDto(93, 3, "Third chapter"), SeasonSummaryDto(97, 7, "Seventh chapter"))
    var episodeCount = 16
    override suspend fun configuration() = ConfigurationDto()
    override suspend fun tvDetail(id: Int, language: String, append: String, sessionId: String?): TvDetailDto {
        calls += "tv/$id/$language"
        val gate = showGate; val fail = showFailure; val wrong = wrongShow
        if (stubborn) withContext(NonCancellable) { gate?.await() } else gate?.await()
        if (fail) throw TmdbException(TmdbError.Network)
        return TvDetailDto(if (wrong) id + 1 else id, name = "Actual show $language", networks = listOf(NetworkDto(81, "Actual network")), seasons = seasons)
    }
    override suspend fun season(seriesId: Int, seasonNumber: Int, language: String): SeasonDto {
        calls += "season/$seriesId/$seasonNumber/$language"
        val gate = episodeGate; val fail = episodeFailure; val wrong = wrongSeason; val wrongEpisode = wrongEpisodeSeason
        if (stubborn) withContext(NonCancellable) { gate?.await() } else gate?.await()
        if (fail) throw TmdbException(TmdbError.Network)
        return SeasonDto(100 + seasonNumber, if (wrong) seasonNumber + 1 else seasonNumber, name = "Actual chapter", episodes = (1..episodeCount).map { number ->
            EpisodeDto(1000 + number, episodeNumber = if (duplicateEpisodes) 1 else number,
                seasonNumber = if (wrongEpisode) seasonNumber + 1 else seasonNumber, name = "Actual episode $number",
                overview = "Actual overview $number. ".repeat(12), airDate = when (number) { 1 -> "2020-01-02"; 2 -> "2099-01-02"; else -> null },
                runtime = if (number == 1) 42 else null, voteAverage = 8.2, voteCount = if (number == 1) 10 else 0, stillPath = null)
        })
    }
}
