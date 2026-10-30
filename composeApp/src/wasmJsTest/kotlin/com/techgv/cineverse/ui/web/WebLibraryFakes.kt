package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun webLibraryTitle(media: MediaType, id: Int) = Title(MediaRef(media, id), "Fixture ${media.name} $id", "", null, null, "2025-01-01", 7.2, 15, emptyList(), media)
internal fun webLibraryEntry(tab: LibraryTab = LibraryTab.Watchlist, values: MutableMap<String, String> = mutableMapOf()) = WebEntry(tab.libraryRoute(), values)
internal fun webLibrarySession(account: String = "fake-account", generation: Long = Long.MIN_VALUE) = AuthSession(AccountObjectId(account), "Same fixture name", generation)

/** Isolated fake-only sources. No browser database, HTTP, real account, imports or mutation execution. */
internal class WebLibraryFixture : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val session = MutableStateFlow<AuthSession?>(null)
    var authoritative: WebSessionState = WebSessionState.Ready(null)
    var producedIdentity: WebLibraryResultIdentity? = WebLibraryResultIdentity.Guest
    var failGuest = false
    var guestSubscriptions = 0
    var activeGuest = 0
    val guestRevision = MutableStateFlow(0)
    var factoryCalls = 0
    var upstreamRetries = 0
    var storageFailures = 0
    var failPage: Pair<MediaType, Int>? = null
    var waitPage: Pair<MediaType, Int>? = null
    var gate = CompletableDeferred<Unit>()
    var canceled = 0
    val reads = mutableListOf<Triple<LibraryKind, MediaType, Int>>()
    private val correlatedIdentity = mutableMapOf<LibraryUiState, WebLibraryResultIdentity?>()
    private val correlatedPages = mutableMapOf<Pair<LibraryUiState, MediaType>, Int?>()
    private val loaded = MediaType.entries.associateWith { 0 }.toMutableMap()
    val store = object : GuestLibraryStore {
        override fun observeKind(kind: LibraryKind, media: MediaType): Flow<List<GuestLibraryRow>> = flow {
            guestSubscriptions++; activeGuest++
            try {
                guestRevision.collect {
                    if (failGuest) throw StorageException("isolated fixture")
                    emit(listOf(GuestLibraryRow(MediaRef(media, if (media == MediaType.Movie) 71 else 82), kind, if (kind == LibraryKind.RATING) 8.5 else null, 1, """{"title":"Durable fixture ${media.name}"}""")))
                }
            } finally { activeGuest-- }
        }
        override fun observeTitle(ref: MediaRef): Flow<List<GuestLibraryRow>> = error("No action-state reads")
        override suspend fun all(): List<GuestLibraryRow> = error("No imports")
        override suspend fun upsert(row: GuestLibraryRow): Unit = error("No writes")
        override suspend fun delete(ref: MediaRef, kind: LibraryKind): Unit = error("No writes")
    }
    val account = object : AccountLibrarySource {
        override suspend fun page(session: AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage {
            reads += Triple(kind, media, page)
            if (waitPage == media to page) try { gate.await() } catch (e: CancellationException) { canceled++; throw e }
            if (failPage == media to page) throw TmdbException(TmdbError.Network)
            loaded[media] = maxOf(loaded.getValue(media), page)
            return LibraryPage(listOf(LibraryEntry(webLibraryTitle(media, page * 100 + if (media == MediaType.Movie) 1 else 2), if (kind == LibraryKind.RATING) 9.5 else null)), page, 3)
        }
        override fun invalidateLists() {}
        override suspend fun states(session: AuthSession, ref: MediaRef): LibraryState = error("No action-state reads")
        override suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState): Unit = error("No writes")
    }
    val repository = LibraryRepository(session, GuestLibrarySource(store), account)
    val identityExtractor: (LibraryUiState) -> WebLibraryResultIdentity? = { correlatedIdentity[it] }
    val pageExtractor: (LibraryUiState, MediaType) -> Int? = { state, media -> correlatedPages[state to media] }

    fun signIn(identity: AuthSession = webLibrarySession()) {
        authoritative = WebSessionState.Ready(identity)
        producedIdentity = WebLibraryResultIdentity.Account(identity)
        session.value = identity
    }

    fun owner(entry: WebEntry = webLibraryEntry()): WebLibraryOwner = WebLibraryOwner(entry, scope,
        authoritative as WebSessionState.Ready, { authoritative }, identityExtractor, pageExtractor, { upstreamRetries++ }) { saver, child ->
        factoryCalls++
        val holder = LibraryStateHolder(repository, saver, child) { storageFailures++ }
        // Controlled fixture annotation of immutable snapshots, NOT runtime producer provenance evidence.
        child.launch(start = CoroutineStart.UNDISPATCHED) {
            holder.state.collect { result ->
                correlatedIdentity[result] = producedIdentity
                MediaType.entries.forEach { media -> correlatedPages[result to media] = loaded.getValue(media).takeIf { it > 0 } }
            }
        }
        holder
    }
    override fun close() { scope.cancel() }
}
