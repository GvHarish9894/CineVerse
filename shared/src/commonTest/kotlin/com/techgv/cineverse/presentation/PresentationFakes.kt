package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.auth.RequestCode
import com.techgv.cineverse.data.auth.SignInOutcome
import com.techgv.cineverse.data.library.AccountLibrarySource
import com.techgv.cineverse.data.library.GuestLibraryRow
import com.techgv.cineverse.data.library.GuestLibraryStore
import com.techgv.cineverse.data.library.ImportLedgerStore
import com.techgv.cineverse.data.library.ImportOfferRecord
import com.techgv.cineverse.data.library.ImportOfferStore
import com.techgv.cineverse.data.library.ImportStepState
import com.techgv.cineverse.data.library.LibraryEntry
import com.techgv.cineverse.data.library.LibraryPage
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.data.tmdb.GenreDto
import com.techgv.cineverse.data.tmdb.GenreListDto
import com.techgv.cineverse.data.tmdb.MediaItemDto
import com.techgv.cineverse.data.tmdb.MovieList
import com.techgv.cineverse.data.tmdb.PageDto
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.data.tmdb.TvList
import com.techgv.cineverse.data.tmdb.UnusedTmdbApi
import com.techgv.cineverse.data.tmdb.VideoDto
import com.techgv.cineverse.data.tmdb.VideosDto
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.io.IOException

// Port of tvApp's VM-test fakes for the shared holders; storage and account fakes are in-memory (no Room / Credentials).

/** Catalog over [api] reading [settings] like the apps do; [appGeneration] is shared with the holders. */
fun catalog(api: TmdbApi, appGeneration: GenerationCounter, settings: MutableStateFlow<Settings>) =
    CatalogRepository(api, appGeneration, settings = { settings.value }, timezone = { "UTC" })

fun title(id: Int, media: MediaType = MediaType.Movie) =
    Title(MediaRef(media, id), "Title $id", "", null, null, "2020-01-01", 7.0, 10, emptyList(), media)

fun session(aoid: String = "aoid", name: String = "Harish", generation: Long = 1) = AuthSession(AccountObjectId(aoid), name, generation)

/**
 * Catalog endpoints of Home / Browse / Search. Each call is recorded by key (e.g. `movie/popular/en-US/1`);
 * a key with a [gates] entry suspends until that gate completes. Page items are titled
 * `"<list>@<language>-<page>-<n>"` so a test can tell which request a committed list came from.
 */
class FakeCatalogApi : UnusedTmdbApi() {
    val calls = mutableListOf<String>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    var totalPages = 1
    var videosFor: (language: String) -> List<VideoDto> = { emptyList() }
    var failVideos = false
    /** Search results: alternating movie and person items, in API order. */
    var mixedSearch = false

    fun gate(key: String) = CompletableDeferred<Unit>().also { gates[key] = it }

    private suspend fun <T> call(key: String, result: () -> T): T {
        calls += key
        gates[key]?.await()
        return result()
    }

    private fun page(label: String, page: Int, mediaType: String?, mixed: Boolean = false) = PageDto(
        page = page,
        results = (0 until 3).map {
            val type = if (mixed && it % 2 == 1) "person" else mediaType
            MediaItemDto(id = page * 100 + it, mediaType = type, title = "$label-$page-$it", name = "$label-$page-$it")
        },
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
        call("search/$query/$page") { page("search:$query", page, "movie", mixedSearch) }

    override suspend fun genres(media: MediaType, language: String) =
        call("genres/$media/$language") { GenreListDto(listOf(GenreDto(if (media == MediaType.Movie) 28 else 18, "$media@$language"))) }

    override suspend fun videos(media: MediaType, id: Int, language: String) = call("videos/$id/$language") {
        if (failVideos) throw TmdbException(TmdbError.Network)
        VideosDto(videosFor(language))
    }
}

/** §8.2 guest rows in memory, newest first. */
class InMemoryGuestLibraryStore : GuestLibraryStore {
    private val rows = MutableStateFlow(emptyList<GuestLibraryRow>())
    override fun observeKind(kind: LibraryKind, media: MediaType): Flow<List<GuestLibraryRow>> =
        rows.map { all -> all.filter { it.kind == kind && it.ref.mediaType == media }.sortedByDescending { it.addedAt } }
    override fun observeTitle(ref: MediaRef): Flow<List<GuestLibraryRow>> = rows.map { all -> all.filter { it.ref == ref } }
    override suspend fun all(): List<GuestLibraryRow> = rows.value
    override suspend fun upsert(row: GuestLibraryRow) {
        rows.value = rows.value.filterNot { it.ref == row.ref && it.kind == row.kind } + row
    }
    override suspend fun delete(ref: MediaRef, kind: LibraryKind) {
        rows.value = rows.value.filterNot { it.ref == ref && it.kind == kind }
    }
}

class InMemoryImportStores : ImportLedgerStore, ImportOfferStore {
    val ledger = mutableMapOf<Triple<String, MediaRef, LibraryKind>, ImportStepState>()
    private val offers = mutableMapOf<String, ImportOfferRecord>()
    override suspend fun state(accountObjectId: String, ref: MediaRef, kind: LibraryKind) = ledger[Triple(accountObjectId, ref, kind)]
    override suspend fun put(accountObjectId: String, ref: MediaRef, kind: LibraryKind, state: ImportStepState) {
        ledger[Triple(accountObjectId, ref, kind)] = state
    }
    override suspend fun offer(accountObjectId: String) = offers[accountObjectId]
    override suspend fun put(offer: ImportOfferRecord) {
        offers[offer.accountObjectId] = offer
    }
}

/**
 * A TMDB account in memory. List pages stay cached (like the 10-min list cache) until [invalidateLists]; page
 * [failListPage] fails. Writes are recorded as `kind/ref=value`, wait on [writeGate], then apply.
 */
class FakeAccountLibrary : AccountLibrarySource {
    val watchlist = mutableListOf<MediaRef>()
    val favourites = mutableListOf<MediaRef>()
    val ratings = mutableMapOf<MediaRef, Double>()
    val writes = mutableListOf<String>()
    var writeGate: CompletableDeferred<Unit>? = null
    var failListPage: Int? = null
    private val cache = mutableMapOf<Triple<LibraryKind, MediaType, Int>, LibraryPage>()

    override suspend fun page(session: AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage {
        if (page == failListPage) throw TmdbException(TmdbError.Network)
        return cache.getOrPut(Triple(kind, media, page)) {
            val refs = when (kind) {
                LibraryKind.WATCHLIST -> watchlist
                LibraryKind.FAVOURITE -> favourites
                LibraryKind.RATING -> ratings.keys.toList()
            }.filter { it.mediaType == media }
            LibraryPage(refs.map { LibraryEntry(title(it.id, it.mediaType), ratings[it]) }, page, lastPage = 1)
        }
    }

    override fun invalidateLists() = cache.clear()

    override suspend fun states(session: AuthSession, ref: MediaRef) =
        LibraryState(known = true, watchlist = ref in watchlist, favourite = ref in favourites, rating = ratings[ref], pending = emptySet())

    override suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState) {
        val value: Any? = when (kind) {
            LibraryKind.WATCHLIST -> target.watchlist
            LibraryKind.FAVOURITE -> target.favourite
            LibraryKind.RATING -> target.rating
        }
        writes += "${kind.name.lowercase()}/${ref.key}=$value"
        writeGate?.await()
        cache.clear() // like the real source: a write invalidates the account lists
        when (kind) {
            LibraryKind.WATCHLIST -> if (target.watchlist) watchlist += ref else watchlist -= ref
            LibraryKind.FAVOURITE -> if (target.favourite) favourites += ref else favourites -= ref
            LibraryKind.RATING -> target.rating?.let { ratings[ref] = it } ?: ratings.remove(ref)
        }
    }
}

/**
 * [AuthRepository] with scripted answers: request codes `token-1`, `token-2`…; [approval] answers each wait (default:
 * never). [signOut] waits on [localClear], fails with [signOutError], else signs out locally, reports it, then waits
 * on [remoteRevocation].
 */
class FakeAuthRepository(initial: AuthSession? = null) : AuthRepository {
    val sessions = MutableStateFlow(initial)
    override val session: Flow<AuthSession?> = sessions
    override val sessionExpired: Flow<Unit> = emptyFlow()
    var codes = 0
    var createError: TmdbException? = null
    val waits = mutableListOf<String>()
    var approval: suspend (RequestCode) -> SignInOutcome = { awaitCancellation() }
    var localClear: CompletableDeferred<Unit>? = null
    var remoteRevocation: CompletableDeferred<Unit>? = null
    var signOutError: IOException? = null
    var signOutCalls = 0

    override suspend fun createRequestCode(): RequestCode {
        createError?.let { throw it }
        return RequestCode("token-${++codes}")
    }

    override suspend fun awaitApproval(code: RequestCode): SignInOutcome {
        waits += code.requestToken
        return approval(code)
    }

    override suspend fun signOut(onSignedOutLocally: () -> Unit) {
        signOutCalls++
        localClear?.await()
        signOutError?.let { throw it }
        sessions.value = null
        onSignedOutLocally()
        remoteRevocation?.await()
    }

    override suspend fun expireSession(failed: AuthSession) {
        if (sessions.value == failed) sessions.value = null
    }
}
