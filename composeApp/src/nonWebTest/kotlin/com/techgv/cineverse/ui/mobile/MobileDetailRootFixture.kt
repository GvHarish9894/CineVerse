package com.techgv.cineverse.ui.mobile

import com.techgv.cineverse.data.auth.*
import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.search.RecentQueryStore
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Root-only synthetic dependencies, including the real raw settings and origin-aware library readers. */
internal class MobileDetailRootFixture(scope: CoroutineScope) {
    val store = MobileSettingsStore()
    val settings = SettingsRepository(store)
    val api = MobileDetailRootApi()
    val generation = GenerationCounter()
    val catalog = CatalogRepository(api, generation, settings::current)
    val auth = MobileDetailRootAuth()
    val guest = MobileDetailRootGuest()
    val accountReads = mutableListOf<Pair<AuthSession, MediaRef>>()
    val accountWrites = mutableListOf<Pair<AuthSession, MediaRef>>()
    val library = LibraryRepository(auth.session, GuestLibrarySource(guest), object : AccountLibrarySource {
        override suspend fun page(session: AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage = error("No lists")
        override fun invalidateLists() = Unit
        override suspend fun states(session: AuthSession, ref: MediaRef): LibraryState {
            accountReads += session to ref
            return LibraryState(true, false, false, null, emptySet())
        }
        override suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState) {
            accountWrites += session to ref
        }
    })
    val graph = MobileApplication(catalog, generation, settings, auth, library, RecentQueryStore(store), scope)
}

internal class MobileDetailRootAuth : AuthRepository {
    val emissions = MutableSharedFlow<AuthSession?>(replay = 1)
    var fail = false
    override val session = emissions.map {
        if (fail) throw StorageException("synthetic root identity read")
        it
    }
    override val sessionExpired = emptyFlow<Unit>()
    override suspend fun createRequestCode(): RequestCode = error("No sign-in")
    override suspend fun awaitApproval(code: RequestCode): SignInOutcome = error("No sign-in")
    override suspend fun signOut(onSignedOutLocally: () -> Unit): Unit = error("No account changes")
    override suspend fun expireSession(failed: AuthSession): Unit = error("No account changes")
}

internal class MobileDetailRootGuest : GuestLibraryStore {
    val rows = MutableStateFlow<List<GuestLibraryRow>>(emptyList())
    var gate: CompletableDeferred<Unit>? = null
    var reads = 0
    val readStarts = mutableListOf<MediaRef>()
    var activeReads = 0
    var fail = false
    override fun observeTitle(ref: MediaRef) = flow {
        reads++
        readStarts += ref
        activeReads++
        try {
            gate?.await()
            emitAll(rows.map {
                if (fail) throw StorageException("synthetic root library read")
                it.filter { row -> row.ref == ref }
            })
        } finally { activeReads-- }
    }
    override fun observeKind(kind: LibraryKind, media: MediaType) = rows.map { it.filter { row -> row.kind == kind && row.ref.mediaType == media } }
    override suspend fun all() = rows.value
    override suspend fun upsert(row: GuestLibraryRow) { rows.value = rows.value.filterNot { it.ref == row.ref && it.kind == row.kind } + row }
    override suspend fun delete(ref: MediaRef, kind: LibraryKind) { rows.value = rows.value.filterNot { it.ref == ref && it.kind == kind } }
}

internal class MobileDetailRootApi(private val home: MobileCatalogApi = MobileCatalogApi()) : TmdbApi by home {
    val detailCalls = mutableListOf<String>()
    val catalogCalls = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun movieDetail(id: Int, language: String, append: String, sessionId: String?): MovieDetailDto {
        detailCalls += "movie/$id/$language"
        gate?.await()
        return MovieDetailDto(id, title = "Root title $id $language", overview = "Root overview", releaseDate = "2026-01-02",
            credits = CreditsDto(listOf(CastDto(431, "Root actor", "Root role"))),
            videos = VideosDto(listOf(VideoDto(key = "aB3_dE5-gH7", name = "Root trailer", site = "YouTube", type = "Trailer"))),
            recommendations = PageDto(1, listOf(MediaItemDto(765, title = "Root recommendation", mediaType = "movie")), 1))
    }

    override suspend fun person(id: Int, language: String, append: String): PersonDetailDto {
        catalogCalls += "person/$id/$language"
        return PersonDetailDto(id, name = "Root actor $id", biography = "Root biography $id",
            knownForDepartment = "Acting", combinedCredits = CombinedCreditsDto())
    }

    override suspend fun tvDetail(id: Int, language: String, append: String, sessionId: String?): TvDetailDto {
        catalogCalls += "tv/$id/$language"
        return TvDetailDto(id, name = "Actual show $id $language", seasons = listOf(
            SeasonSummaryDto(100, 3, "Third chapter"), SeasonSummaryDto(101, 7, "Seventh chapter")))
    }

    override suspend fun season(seriesId: Int, seasonNumber: Int, language: String): SeasonDto {
        catalogCalls += "season/$seriesId/$seasonNumber/$language"
        return SeasonDto(200 + seasonNumber, seasonNumber, name = "Chapter $seasonNumber", episodes = listOf(
            EpisodeDto(300 + seasonNumber, episodeNumber = 1, seasonNumber = seasonNumber,
                name = "Actual episode $seasonNumber-1", overview = "Root episode overview")))
    }
}
