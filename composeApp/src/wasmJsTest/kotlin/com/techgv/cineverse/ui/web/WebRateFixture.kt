package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.LibraryActionsStateHolder
import com.techgv.cineverse.presentation.LibraryWriteOutcome
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The real [LibraryActionsStateHolder] over the real [LibraryRepository], with in-memory guest rows and a fake TMDB
 * account. Every rating write (guest or account) is recorded in [writes] as `owner:rating/ref=value`.
 */
internal class WebRateFixture(signedIn: AuthSession? = A) {
    companion object {
        val A = AuthSession(AccountObjectId("rate-account-a"), "Viewer A", 1)
        val B = AuthSession(AccountObjectId("rate-account-b"), "Viewer B", 2)
        val MOVIE = title(7, "Neon Tide")
        val OTHER = title(8, "Quiet Harbour")
        private fun title(id: Int, name: String) =
            Title(MediaRef(MediaType.Movie, id), name, "", null, null, "2024-01-01", 7.0, 10, emptyList(), MediaType.Movie)
    }

    val writes = mutableListOf<String>()
    val outcomes = mutableListOf<LibraryWriteOutcome>()
    private val identity = MutableStateFlow(signedIn)
    private val guestRows = MutableStateFlow<List<GuestLibraryRow>>(emptyList())
    private val ratings = mutableMapOf<MediaRef, Double>()

    private val guest = object : GuestLibraryStore {
        override fun observeKind(kind: LibraryKind, media: MediaType) =
            guestRows.map { rows -> rows.filter { it.kind == kind && it.ref.mediaType == media } }
        override fun observeTitle(ref: MediaRef) = guestRows.map { rows -> rows.filter { it.ref == ref } }
        override suspend fun all() = guestRows.value
        override suspend fun upsert(row: GuestLibraryRow) {
            writes += "guest:${row.kind.name.lowercase()}/${row.ref.key}=${row.value}"
            guestRows.value = guestRows.value.filterNot { it.ref == row.ref && it.kind == row.kind } + row
        }
        override suspend fun delete(ref: MediaRef, kind: LibraryKind) {
            writes += "guest:${kind.name.lowercase()}/${ref.key}=null"
            guestRows.value = guestRows.value.filterNot { it.ref == ref && it.kind == kind }
        }
    }

    private val account = object : AccountLibrarySource {
        override suspend fun page(session: AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage =
            error("No list reads in rate tests")
        override fun invalidateLists() = Unit
        override suspend fun states(session: AuthSession, ref: MediaRef) =
            LibraryState(known = true, watchlist = false, favourite = false, rating = ratings[ref], pending = emptySet())
        override suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState) {
            writes += "${session.accountObjectId.value}:${kind.name.lowercase()}/${ref.key}=${target.rating}"
            target.rating?.let { ratings[ref] = it } ?: ratings.remove(ref)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val repository = LibraryRepository(identity, GuestLibrarySource(guest), account)
    val actions = LibraryActionsStateHolder(repository, scope, scope) { outcomes += it }.also { it.onIdentity(signedIn) }

    /** A guest rating already stored in this browser (the guest prompt's initial value). */
    fun seedGuestRating(ref: MediaRef, value: Double) {
        guestRows.value += GuestLibraryRow(ref, LibraryKind.RATING, value, 0, """{"title":"seed"}""")
    }

    /** What the root does on a sign-in change: the repository and the holder both see the new identity. */
    fun switchTo(c: AuthSession?) {
        identity.value = c
        actions.onIdentity(c)
    }

    fun dispose() = scope.cancel()
}
