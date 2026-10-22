// Copy of shared/src/nonWebTest/.../data/library/LibraryFakes.kt for the TV VM tests (tvApp tests can't see shared test
// sources); keep both in step. A shared test-fixtures module is deferred (plan K9 / build-logic).
package com.techgv.cineverse.data.library

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.AccountId
import com.techgv.cineverse.data.tmdb.AccountList
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.AccountRatingDto
import com.techgv.cineverse.data.tmdb.AccountStatesDto
import com.techgv.cineverse.data.tmdb.FavoriteBody
import com.techgv.cineverse.data.tmdb.MediaItemDto
import com.techgv.cineverse.data.tmdb.PageDto
import com.techgv.cineverse.data.tmdb.RatingBody
import com.techgv.cineverse.data.tmdb.SessionId
import com.techgv.cineverse.data.tmdb.StatusDto
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TmdbJson
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.data.tmdb.UserAccessToken
import com.techgv.cineverse.data.tmdb.UserBearer
import com.techgv.cineverse.data.tmdb.WatchlistBody
import com.techgv.cineverse.data.tmdb.unusedTmdbApi
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

fun credentials(aoid: String = "aoid", session: String = "s") =
    Credentials(UserAccessToken("u"), AccountObjectId(aoid), SessionId(session), AccountId(1), "harish", "Harish")

fun title(id: Int, media: MediaType = MediaType.Movie) =
    Title(MediaRef(media, id), "Title $id", "", null, null, "2020-01-01", 7.0, 10, emptyList(), media)

/**
 * TMDB account state in memory. `account_states` answers with the state at request time (before its gate).
 * Writes record a call, wait on [writeGate], then fail ([failWrites] > 0) or apply.
 */
class FakeAccountApi : TmdbApi by unusedTmdbApi {
    val watchlist = mutableSetOf<MediaRef>()
    val favorites = mutableSetOf<MediaRef>()
    val ratings = mutableMapOf<MediaRef, Double>()
    val calls = mutableListOf<String>()
    var writeGate: CompletableDeferred<Unit>? = null
    /** Each `account_states` call takes the next gate, if any, and waits on it. */
    val statesGates = ArrayDeque<CompletableDeferred<Unit>>()
    var failWrites = 0
    /** A failing write still lands on TMDB (response lost). */
    var landThenFail = false
    var failStates = false
    /** TMDB account preference: rating removes the title from the watchlist (D6). */
    var rateRemovesFromWatchlist = false
    var failListPage: Int? = null
    var pageSize = 2

    val writes get() = calls.filter { !it.startsWith("states") && !it.startsWith("list") }

    override suspend fun accountStates(media: MediaType, id: Int, sessionId: String): AccountStatesDto {
        val ref = MediaRef(media, id)
        calls += "states/${ref.key}"
        val snapshot = AccountStatesDto(
            id, favorite = ref in favorites, watchlist = ref in watchlist,
            rated = ratings[ref]?.let { JsonObject(mapOf("value" to JsonPrimitive(it))) } ?: JsonPrimitive(false),
        )
        statesGates.removeFirstOrNull()?.await()
        if (failStates) throw IOException("offline")
        return snapshot
    }

    override suspend fun setWatchlist(accountId: Int, sessionId: String, body: WatchlistBody) =
        write("watchlist/${body.mediaType}:${body.mediaId}=${body.watchlist}") {
            val ref = MediaRef(MediaType.entries.first { it.path == body.mediaType }, body.mediaId)
            if (body.watchlist) watchlist += ref else watchlist -= ref
        }

    override suspend fun setFavorite(accountId: Int, sessionId: String, body: FavoriteBody) =
        write("favorite/${body.mediaType}:${body.mediaId}=${body.favorite}") {
            val ref = MediaRef(MediaType.entries.first { it.path == body.mediaType }, body.mediaId)
            if (body.favorite) favorites += ref else favorites -= ref
        }

    override suspend fun rate(media: MediaType, id: Int, sessionId: String, body: RatingBody) = write("rate/$media:$id=${body.value}") {
        ratings[MediaRef(media, id)] = body.value
        if (rateRemovesFromWatchlist) watchlist -= MediaRef(media, id)
    }

    override suspend fun unrate(media: MediaType, id: Int, sessionId: String) = write("unrate/$media:$id") {
        ratings -= MediaRef(media, id)
    }

    override suspend fun accountList(
        bearer: UserBearer, accountObjectId: String, media: MediaType, list: AccountList, language: String, page: Int, sortBy: String,
    ): PageDto<MediaItemDto> {
        calls += "list/$media/$list/$page"
        if (page == failListPage) throw IOException("offline")
        val refs = when (list) {
            AccountList.Watchlist -> watchlist
            AccountList.Favorites -> favorites
            AccountList.Rated -> ratings.keys
        }.filter { it.mediaType == media }
        val items = refs.map { MediaItemDto(it.id, title = "Title ${it.id}", accountRating = ratings[it]?.let { v -> AccountRatingDto(v) }) }
        val totalPages = (items.size + pageSize - 1) / pageSize
        return PageDto(page, items.drop((page - 1) * pageSize).take(pageSize), totalPages, items.size)
    }

    private suspend fun write(call: String, apply: () -> Unit): StatusDto {
        calls += call
        writeGate?.await()
        if (failWrites > 0) {
            failWrites--
            if (landThenFail) apply()
            throw IOException("offline")
        }
        apply()
        return StatusDto(success = true)
    }
}

fun tmdbRepository(api: TmdbApi) = TmdbRepository(api, GenerationCounter(), settings = { Settings() })

/** Stand-in for NativeAuthRepository's sessions: one [AuthSession] per credentials tuple (= one sign-in). */
object TestSessions {
    private val sessions = mutableMapOf<Credentials, AuthSession>()

    fun of(c: Credentials): AuthSession = sessions.getOrPut(c) { AuthSession(c.accountObjectId, c.displayName, sessions.size + 1L) }

    fun credentialsFor(session: AuthSession): Credentials = sessions.entries.first { it.value == session }.key
}

fun Credentials.session(): AuthSession = TestSessions.of(this)

fun guestSource(dao: LibraryDao = FakeLibraryDao(), now: () -> Long = { 0L }) = GuestLibrarySource(RoomGuestLibraryStore(dao), now)

fun accountSource(api: TmdbApi) = TmdbAccountLibrarySource(tmdbRepository(api), TestSessions::credentialsFor)

class FakeLibraryDao : LibraryDao {
    val rows = MutableStateFlow(listOf<LibraryItemEntity>())

    override fun observeKind(kind: String, mediaType: String): Flow<List<LibraryItemEntity>> =
        rows.map { all -> all.filter { it.kind == kind && it.mediaType == mediaType }.sortedByDescending { it.addedAt } }

    override fun observeTitle(mediaType: String, mediaId: Int): Flow<List<LibraryItemEntity>> =
        rows.map { all -> all.filter { it.mediaType == mediaType && it.mediaId == mediaId } }

    override suspend fun all() = rows.value.sortedByDescending { it.addedAt }

    override suspend fun upsert(item: LibraryItemEntity) {
        rows.value = rows.value.filterNot { it.sameKey(item.mediaType, item.mediaId, item.kind) } + item
    }

    override suspend fun delete(mediaType: String, mediaId: Int, kind: String) {
        rows.value = rows.value.filterNot { it.sameKey(mediaType, mediaId, kind) }
    }

    private fun LibraryItemEntity.sameKey(type: String, id: Int, k: String) = mediaType == type && mediaId == id && kind == k
}

class FakeImportLedgerDao : ImportLedgerDao {
    val entries = mutableMapOf<List<Any>, String>()
    val offers = mutableMapOf<String, ImportOfferEntity>()

    override suspend fun state(accountObjectId: String, mediaType: String, mediaId: Int, step: String) =
        entries[listOf(accountObjectId, mediaType, mediaId, step)]

    override suspend fun put(entry: ImportLedgerEntity) {
        entries[listOf(entry.accountObjectId, entry.mediaType, entry.mediaId, entry.step)] = entry.state
    }

    override suspend fun offer(accountObjectId: String) = offers[accountObjectId]

    override suspend fun putOffer(offer: ImportOfferEntity) {
        offers[offer.accountObjectId] = offer
    }
}

/** Decodes like KtorTmdbApi does. */
inline fun <reified T> tmdbJson(json: String): T = TmdbJson.decodeFromString(json)
