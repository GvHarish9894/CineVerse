package com.techgv.cineverse.data.web

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.*
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerializationException

/** Cookie-backed account data, bound before dispatch AND checked again before returning a result. No write retries. */
class WebAccountLibrarySource(
    private val http: WebProxyHttp,
    private val auth: WebAuthRepository,
    private val settings: suspend () -> Settings,
) : AccountLibrarySource {
    private val invalidations = MutableStateFlow(0L)
    /** Non-secret local invalidation signal. Parent may connect it to C3 notifications and library/cache reloads. */
    val libraryRevision: StateFlow<Long> = invalidations.asStateFlow()
    override fun invalidateLists() { invalidations.update { it + 1 } }

    private suspend fun accountCall(
        session: AuthSession,
        path: String,
        query: List<Pair<String, Any?>> = emptyList(),
        method: HttpMethod = HttpMethod.Get,
        body: String? = null,
    ): String {
        val ticket = auth.capture(session)
        val mutation = method != HttpMethod.Get
        val libraryTicket = invalidations.value
        try {
            val result = http.call(path, query, method, body, session)
            try {
                auth.check(session, ticket)
                if (!mutation && libraryTicket != invalidations.value) throw staleWebSession()
            } catch (_: WebProxyException) {
                throw WebProxyException("stale_session", 409, null,
                    if (mutation) WebWriteOutcome.PossiblyLanded else WebWriteOutcome.NotApplicable, TmdbError.Http(409, null))
            }
            return result
        } catch (e: WebProxyException) {
            auth.recover(e)
            throw e
        } finally {
            // Includes cancellation/unknown outcomes: the next visible state must be reconciled, never replayed.
            if (mutation) invalidateLists()
        }
    }

    private inline fun <reified T> decode(text: String, mutation: Boolean = false): T = try { TmdbJson.decodeFromString(text) }
    catch (_: SerializationException) {
        throw WebProxyException("malformed_response", null, null,
            if (mutation) WebWriteOutcome.PossiblyLanded else WebWriteOutcome.NotApplicable, TmdbError.Malformed)
    }

    override suspend fun page(session: AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage {
        val language = settings().language
        val dto = decode<PageDto<MediaItemDto>>(accountCall(session, "/api/account/$media/${kind.accountList}",
            listOf("page" to page, "sort_by" to "created_at.desc", "language" to language)))
        return LibraryPage(dto.results.map { it.toLibraryEntry(media) }, dto.page, minOf(dto.totalPages, 500))
    }

    override suspend fun states(session: AuthSession, ref: MediaRef): LibraryState {
        val dto = decode<AccountStatesDto>(accountCall(session, "/api/account/states/${ref.mediaType}/${ref.id}"))
        return LibraryState(known = true, watchlist = dto.watchlist, favourite = dto.favorite, rating = dto.ratedValue, pending = emptySet())
    }

    override suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState) {
        val path: String
        val body: String?
        var method = HttpMethod.Post
        when (kind) {
            LibraryKind.WATCHLIST -> {
                path = "/api/account/watchlist"
                body = TmdbJson.encodeToString(WatchlistBody(ref.mediaType.path, ref.id, target.watchlist))
            }
            LibraryKind.FAVOURITE -> {
                path = "/api/account/favorite"
                body = TmdbJson.encodeToString(FavoriteBody(ref.mediaType.path, ref.id, target.favourite))
            }
            LibraryKind.RATING -> {
                path = "/api/account/rating/${ref.mediaType}/${ref.id}"
                body = target.rating?.let {
                    require(it.isFinite() && it in 0.5..10.0 && it * 2 == (it * 2).toInt().toDouble())
                    TmdbJson.encodeToString(RatingBody(it))
                }
                if (body == null) method = HttpMethod.Delete
            }
        }
        decode<StatusDto>(accountCall(session, path, method = method, body = body), mutation = true)
    }
}
