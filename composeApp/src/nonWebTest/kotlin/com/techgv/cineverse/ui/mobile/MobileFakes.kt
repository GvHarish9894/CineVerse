package com.techgv.cineverse.ui.mobile

import com.techgv.cineverse.data.auth.*
import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.settings.*
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

internal class MobileFixture(scope: CoroutineScope) {
    val store = MobileSettingsStore()
    val settings = SettingsRepository(store)
    val api = MobileCatalogApi()
    val generation = GenerationCounter()
    val catalog = CatalogRepository(api, generation, settings::current)
    val auth = MobileAuth()
    val guest = MobileGuestStore()
    val library = LibraryRepository(auth.session, GuestLibrarySource(guest), object : AccountLibrarySource {
        override suspend fun page(session: AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage = error("Not used")
        override fun invalidateLists() = Unit
        override suspend fun states(session: AuthSession, ref: MediaRef) = LibraryState(true, false, false, null, emptySet())
        override suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState) = error("No account writes in mobile fixtures")
    })
    val recent = com.techgv.cineverse.data.search.RecentQueryStore(store)
    val graph = MobileApplication(catalog, generation, settings, auth, library, recent, scope,
        inertApproval(auth, scope), inertImporter(library))
}

internal class MobileSettingsStore : SettingsStore {
    val values = MutableStateFlow<Map<String, Any>>(emptyMap())
    var fail = false
    var activeSubscriptions = 0
    var subscriptions = 0
    var edits = 0
    var failWrites = false
    override val data: Flow<SettingsValues> = flow {
        subscriptions++
        activeSubscriptions++
        try {
            values.collect {
                if (fail) throw StorageException("fake settings read")
                emit(Values(it.toMutableMap()))
            }
        } finally { activeSubscriptions-- }
    }
    override suspend fun edit(transform: (MutableSettingsValues) -> Unit) {
        edits++
        if (failWrites) throw StorageException("fake settings write")
        val next = Values(values.value.toMutableMap())
        transform(next)
        values.value = next.map.toMap()
    }
    private class Values(val map: MutableMap<String, Any>) : MutableSettingsValues {
        override fun string(key: String) = map[key] as? String
        override fun boolean(key: String) = map[key] as? Boolean
        @Suppress("UNCHECKED_CAST") override fun stringSet(key: String) = map[key] as? Set<String>
        override fun setString(key: String, value: String) { map[key] = value }
        override fun setBoolean(key: String, value: Boolean) { map[key] = value }
        override fun setStringSet(key: String, value: Set<String>) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }
}

internal class MobileAuth : AuthRepository {
    val emissions = MutableSharedFlow<AuthSession?>(replay = 1)
    override val session: Flow<AuthSession?> = emissions
    override val sessionExpired = emptyFlow<Unit>()
    override suspend fun createRequestCode(): RequestCode = error("No sign-in")
    override suspend fun awaitApproval(code: RequestCode): SignInOutcome = error("No sign-in")
    override suspend fun signOut(onSignedOutLocally: () -> Unit): Unit = error("No account changes")
    override suspend fun expireSession(failed: AuthSession): Unit = error("Not used")
}

internal class MobileGuestStore : GuestLibraryStore {
    val rows = MutableStateFlow<List<GuestLibraryRow>>(emptyList())
    var fail = false
    override fun observeKind(kind: LibraryKind, media: MediaType) = rows.map { it.filter { row -> row.kind == kind && row.ref.mediaType == media } }
    override fun observeTitle(ref: MediaRef) = rows.map {
        if (fail) throw StorageException("fake library read")
        it.filter { row -> row.ref == ref }
    }
    override suspend fun all() = rows.value
    override suspend fun upsert(row: GuestLibraryRow) { rows.value = rows.value.filterNot { it.ref == row.ref && it.kind == row.kind } + row }
    override suspend fun delete(ref: MediaRef, kind: LibraryKind) { rows.value = rows.value.filterNot { it.ref == ref && it.kind == kind } }
}

internal class MobileCatalogApi : MobileUnusedTmdbApi() {
    val calls = mutableListOf<String>()
    var failure: TmdbError? = null
    var pageFailure: Int? = null
    var trailerGate: CompletableDeferred<Unit>? = null
    var genreGate: CompletableDeferred<Unit>? = null
    var failGenresStorage = false
    var genreCalls = 0
    private fun page(label: String, page: Int, type: String) : PageDto<MediaItemDto> {
        calls += "$label/$page"
        failure?.let { throw TmdbException(it) }
        if (page == pageFailure) throw TmdbException(TmdbError.Network)
        return PageDto(page, (1..12).map { MediaItemDto(id = page * 100 + it, title = "$label $it", name = "$label $it", mediaType = type) }, 2)
    }
    override suspend fun searchMulti(query: String, language: String, includeAdult: Boolean, page: Int) =
        search(SearchFacet.Multi, query, language, includeAdult, page)
    override suspend fun search(facet: SearchFacet, query: String, language: String, includeAdult: Boolean, page: Int) =
        page("search-$facet-$query-$language-$includeAdult", page, when (facet) {
            SearchFacet.Tv -> "tv"
            SearchFacet.Person -> "person"
            else -> "movie"
        })
    override suspend fun configuration() = ConfigurationDto()
    /** A long Cast segment (20 people), so Detail is scrollable well past the first screen. */
    var richDetail = false
    /** When set, Detail reads wait for it, e.g. the re-verification on return to a retained Detail. */
    var detailGate: CompletableDeferred<Unit>? = null
    override suspend fun movieDetail(id: Int, language: String, append: String, sessionId: String?) =
        detailGate.let { it?.await() }.let { MovieDetailDto(id, title = "Detail title $id", overview = "Navigation fixture overview", releaseDate = "2026-01-02",
            credits = if (richDetail) CreditsDto((1..20).map { CastDto(1000 + it, "Cast member $it", "Role $it") }, emptyList()) else null) }
    override suspend fun tvDetail(id: Int, language: String, append: String, sessionId: String?) =
        TvDetailDto(id, name = "TV detail title $id", overview = "Navigation fixture overview", firstAirDate = "2026-01-02")
    override suspend fun genres(media: MediaType, language: String): GenreListDto {
        genreCalls++
        genreGate?.await()
        if (failGenresStorage) throw StorageException("fake genre storage read")
        return GenreListDto(listOf(GenreDto(if (media == MediaType.Movie) 28 else 18, "Fixture genre")))
    }
    override suspend fun trending(window: TrendingWindow, language: String, page: Int) = page("$window-$language", page, "movie")
    override suspend fun movieList(list: MovieList, language: String, region: String, page: Int) = page("$list-$language-$region", page, "movie")
    override suspend fun tvList(list: TvList, language: String, timezone: String?, page: Int) = page("tv-$language", page, "tv")
    override suspend fun popularPeople(language: String, page: Int) = page("people-$language", page, "person")
    override suspend fun person(id: Int, language: String, append: String) =
        PersonDetailDto(id, name = "Person $id $language", biography = "Fixture biography $id", combinedCredits = CombinedCreditsDto())
    override suspend fun videos(media: MediaType, id: Int, language: String): VideosDto {
        calls += "videos/$id/$language"
        trailerGate?.await()
        return VideosDto(listOf(VideoDto(key = "fake-video", name = "Fixture trailer", site = "YouTube", type = "Trailer")))
    }
}
