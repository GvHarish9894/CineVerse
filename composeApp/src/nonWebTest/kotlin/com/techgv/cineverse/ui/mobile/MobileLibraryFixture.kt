package com.techgv.cineverse.ui.mobile

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*

/** Separate authoritative identity, repository identity and page gates exercise source proof, not UI labels. */
internal class MobileLibraryFixture(scope: CoroutineScope) {
    val base = MobileFixture(scope)
    val source = MutableSharedFlow<AuthSession?>(replay = 1)
    var sessionFailure = false
    var sessionReads = 0
    val guest = LibraryGuestFixture()
    val calls = mutableListOf<Triple<AuthSession, MediaType, Int>>()
    var failPage: Pair<MediaType, Int>? = null
    var storagePage: Pair<MediaType, Int>? = null
    var gate: CompletableDeferred<Unit>? = null
    var nonCooperative = false
    var lastPage = 3
    var invalidations = 0
    var writes = 0
    val repository = LibraryRepository(flow {
        sessionReads++
        source.collect {
            if (sessionFailure) throw StorageException("owned library session read")
            emit(it)
        }
    }, GuestLibrarySource(guest), object : AccountLibrarySource {
        override suspend fun page(session: AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage {
            calls += Triple(session, media, page)
            val pending = gate
            if (nonCooperative) withContext(NonCancellable) { pending?.await() } else pending?.await()
            if (storagePage == media to page) throw StorageException("owned library page read")
            if (failPage == media to page) throw TmdbException(TmdbError.Network)
            return LibraryPage(listOf(libraryFixtureEntry(media, page, session.displayName)), page, lastPage)
        }
        override fun invalidateLists() { invalidations++ }
        override suspend fun states(session: AuthSession, ref: MediaRef) = LibraryState.Unknown
        override suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState) { writes++ }
    })

    fun identity(session: AuthSession?) { base.auth.emissions.tryEmit(session); source.tryEmit(session) }
}

internal class LibraryGuestFixture : GuestLibraryStore {
    val rows = MutableStateFlow<List<GuestLibraryRow>>(emptyList())
    var fail = false
    var reads = 0
    var writes = 0
    override fun observeKind(kind: LibraryKind, media: MediaType) = flow {
        reads++
        rows.collect {
            if (fail) throw StorageException("owned library guest read")
            emit(it.filter { row -> row.kind == kind && row.ref.mediaType == media })
        }
    }
    override fun observeTitle(ref: MediaRef) = rows.map { it.filter { row -> row.ref == ref } }
    override suspend fun all() = rows.value
    override suspend fun upsert(row: GuestLibraryRow) { writes++; rows.value += row }
    override suspend fun delete(ref: MediaRef, kind: LibraryKind) { writes++; rows.value = rows.value.filterNot { it.ref == ref && it.kind == kind } }
}

internal fun libraryFixtureEntry(media: MediaType, page: Int = 1, name: String = "Library fixture") = LibraryEntry(
    Title(MediaRef(media, page), "$name ${media.name} $page", "", null, null, null, 8.0, 10, emptyList(), media), 8.0,
)
