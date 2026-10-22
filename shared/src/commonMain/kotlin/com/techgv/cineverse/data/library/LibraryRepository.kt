package com.techgv.cineverse.data.library

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface LibraryWriteResult {
    /** [synced]: written to TMDB (signed in) rather than this TV. [removedFromWatchlist]: observed after a rating (D6). */
    data class Done(val state: LibraryState, val synced: Boolean, val removedFromWatchlist: Boolean = false) : LibraryWriteResult
    data class Failed(val error: TmdbError?) : LibraryWriteResult
    /** The identity the action was made under is no longer signed in: not written (or its outcome is stale). No toast. */
    data object Superseded : LibraryWriteResult
}

/**
 * §8.1: the library of the current identity — guest (Room) or the signed-in account (TMDB). Writes are
 * serialized per (title, kind). Signed in, a write shows optimistically with its kind pending, then
 * either confirms or rolls back to the last *confirmed* value; `account_states` reads commit into that
 * confirmed value per kind only if no write or read committed that kind since the read started
 * (per-kind generation), so neither a stale read nor a rollback ever overrides a later success.
 * No offline queue: a failed signed-in write is reported, never retried.
 *
 * Every write is bound to the identity it was made under (an [AuthSession] is one sign-in; `null` is the guest) and
 * re-checks it holding the lock and again right before its POST: a superseded write is rejected, never redirected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepository(
    val session: Flow<AuthSession?>,
    private val guest: GuestLibrarySource,
    private val account: AccountLibrarySource,
) {
    private data class Slot(val confirmed: LibraryState, val shown: LibraryState, val generations: Map<LibraryKind, Long>)

    /**
     * Signed-in button states, valid for [identity] only (a new sign-in starts empty). [refetch] is bumped by
     * [onExternalWrites]: active [state] collectors of this identity re-read `account_states`.
     */
    private data class AccountStore(val identity: AuthSession?, val slots: Map<MediaRef, Slot> = emptyMap(), val refetch: Long = 0)

    private val store = MutableStateFlow(AccountStore(null))
    /** One write lock per (title, kind), created on first use under [locksLock]. */
    private val locks = HashMap<Pair<MediaRef, LibraryKind>, Mutex>()
    private val locksLock = SynchronizedObject()
    private val revision = MutableStateFlow(0L)

    /** Bumped after every mutation: Library lists revalidate (§3.4; the TMDB cache is already invalidated). */
    val listRevision: StateFlow<Long> = revision.asStateFlow()

    /** Per-title button state; signed in, `account_states` is re-read on every collection. */
    fun state(ref: MediaRef): Flow<LibraryState> = session.distinctUntilChanged().flatMapLatest { c ->
        if (c == null) {
            guest.observeState(ref)
        } else {
            channelFlow {
                enter(c)
                launch {
                    store.map { s -> s.refetch.takeIf { s.identity == c } }.filterNotNull().distinctUntilChanged().collectLatest {
                        try {
                            refresh(c, ref)
                        } catch (_: TmdbException) {
                            // stays Unknown: buttons disabled
                        }
                    }
                }
                store.map { if (it.identity == c) it.slots[ref]?.shown ?: LibraryState.Unknown else LibraryState.Unknown }
                    .distinctUntilChanged()
                    .collect { send(it) }
            }
        }
    }

    fun guestEntries(kind: LibraryKind, media: MediaType): Flow<List<LibraryEntry>> = guest.observeEntries(kind, media)

    /** One page of a signed-in list (`created_at.desc`); throws `TmdbException`. */
    suspend fun accountPage(account: AuthSession, kind: LibraryKind, media: MediaType, page: Int): LibraryPage =
        this.account.page(account, kind, media, page)

    /** [identity]'s rating of [ref] as shown now (the Rate dialog's default). */
    suspend fun currentRating(identity: AuthSession?, ref: MediaRef): Double? = when (identity) {
        null -> guest.state(ref).rating
        else -> slot(identity, ref)?.shown?.rating
    }

    /** [identity]: the one signed in when the user clicked (captured synchronously by the caller). */
    suspend fun toggleWatchlist(title: Title, identity: AuthSession?) =
        write(identity, title, LibraryKind.WATCHLIST) { it.copy(watchlist = !it.watchlist) }

    suspend fun toggleFavourite(title: Title, identity: AuthSession?) =
        write(identity, title, LibraryKind.FAVOURITE) { it.copy(favourite = !it.favourite) }

    /** [identity]: the one the Rate dialog was opened under. */
    suspend fun rate(title: Title, value: Double, identity: AuthSession?) = write(identity, title, LibraryKind.RATING) { it.copy(rating = value) }

    suspend fun unrate(title: Title, identity: AuthSession?) = write(identity, title, LibraryKind.RATING) { it.copy(rating = null) }

    /** Runs [block] holding the (title, kind) write lock: the importer's writes queue behind the user's. */
    internal suspend fun <T> serialized(ref: MediaRef, kind: LibraryKind, block: suspend () -> T): T =
        synchronized(locksLock) { locks.getOrPut(ref to kind) { Mutex() } }.withLock { block() }

    /** §3.4 "revalidate on entry" / import preflight: the next account list reads go to TMDB. */
    fun invalidateAccountLists() = account.invalidateLists()

    /**
     * Writes made outside [write] for [c] (import, also attempted / unknown ones): [c]'s cached button states are
     * dropped and its active collectors refetch; lists revalidate. Another identity's states are never touched.
     */
    internal fun onExternalWrites(c: AuthSession) {
        store.update { if (it.identity == c) it.copy(slots = emptyMap(), refetch = it.refetch + 1) else it }
        revision.update { it + 1 }
    }

    private suspend fun write(identity: AuthSession?, title: Title, kind: LibraryKind, change: (LibraryState) -> LibraryState): LibraryWriteResult {
        val result = serialized(title.ref, kind) {
            when {
                session.first() != identity -> LibraryWriteResult.Superseded
                identity == null -> guestWrite(title, kind, change)
                else -> accountWrite(identity, title.ref, kind, change)
            }
        }
        // Finished after a sign-in change: its toast would describe another identity's library.
        return if (session.first() != identity) LibraryWriteResult.Superseded else result
    }

    private suspend fun guestWrite(title: Title, kind: LibraryKind, change: (LibraryState) -> LibraryState): LibraryWriteResult {
        val target = change(guest.state(title.ref))
        guest.write(title, kind, target)
        revision.update { it + 1 }
        return LibraryWriteResult.Done(guest.state(title.ref), synced = false)
    }

    private suspend fun accountWrite(
        c: AuthSession,
        ref: MediaRef,
        kind: LibraryKind,
        change: (LibraryState) -> LibraryState,
    ): LibraryWriteResult {
        enter(c)
        try {
            if (slot(c, ref) == null) refresh(c, ref)
        } catch (e: TmdbException) {
            return LibraryWriteResult.Failed(e.error)
        }
        // The read above may have outlived the sign-in: re-checked right before the POST.
        if (session.first() != c) return LibraryWriteResult.Superseded
        val before = slot(c, ref) ?: return LibraryWriteResult.Superseded
        val target = change(before.shown)
        updateSlot(c, ref) { it.copy(shown = it.shown.copyKind(kind, target).copy(pending = it.shown.pending + kind)) }
        try {
            account.write(c, ref, kind, target)
        } catch (e: Throwable) {
            // Rollback to the last confirmed value: a later success (a read committed meanwhile) already moved it.
            updateSlot(c, ref) { it.copy(shown = it.shown.copyKind(kind, it.confirmed).copy(pending = it.shown.pending - kind)) }
            revision.update { it + 1 } // the write may have landed before the error surfaced
            if (e is TmdbException) {
                // ... so re-read: a landed write shows as soon as TMDB reports it.
                try {
                    refresh(c, ref)
                } catch (_: TmdbException) {
                    // stays at the confirmed value
                }
                return LibraryWriteResult.Failed(e.error)
            }
            throw e
        }
        updateSlot(c, ref) {
            it.copy(
                confirmed = it.confirmed.copyKind(kind, target),
                shown = it.shown.copyKind(kind, target).copy(pending = it.shown.pending - kind),
                generations = it.generations + (kind to it.generation(kind) + 1),
            )
        }
        revision.update { it + 1 }
        // §7.4 / §7.11: after any mutation re-read account_states (TMDB may drop a rated title from the watchlist, D6).
        val after = try {
            refresh(c, ref)
        } catch (_: TmdbException) {
            null
        }
        val removed = kind == LibraryKind.RATING && target.rating != null && before.confirmed.watchlist && after?.watchlist == false
        return LibraryWriteResult.Done(slot(c, ref)?.shown ?: target, synced = true, removedFromWatchlist = removed)
    }

    /**
     * Reads `account_states` and commits each kind into the confirmed value unless that kind committed since
     * the read started; a pending kind keeps showing its optimistic value. Returns the fresh remote state.
     */
    private suspend fun refresh(c: AuthSession, ref: MediaRef): LibraryState {
        val started = slot(c, ref)?.generations.orEmpty()
        val fresh = account.states(c, ref)
        updateSlot(c, ref, create = true) { slot ->
            var confirmed = slot.confirmed.copy(known = true)
            var shown = slot.shown.copy(known = true)
            var generations = slot.generations
            for (kind in LibraryKind.entries) {
                if (slot.generation(kind) != (started[kind] ?: 0L)) continue
                confirmed = confirmed.copyKind(kind, fresh)
                if (kind !in shown.pending) shown = shown.copyKind(kind, fresh)
                generations = generations + (kind to slot.generation(kind) + 1)
            }
            Slot(confirmed, shown, generations)
        }
        return fresh
    }

    /** A new identity starts with no cached button states. */
    private fun enter(c: AuthSession) = store.update { if (it.identity == c) it else AccountStore(c) }

    private fun slot(c: AuthSession, ref: MediaRef): Slot? = store.value.takeIf { it.identity == c }?.slots?.get(ref)

    private fun updateSlot(c: AuthSession, ref: MediaRef, create: Boolean = false, change: (Slot) -> Slot) = store.update { s ->
        if (s.identity != c) return@update s // signed out / switched account: drop the result
        val slot = s.slots[ref] ?: if (create) Slot(LibraryState.Unknown, LibraryState.Unknown, emptyMap()) else return@update s
        s.copy(slots = s.slots + (ref to change(slot)))
    }

    private fun Slot.generation(kind: LibraryKind) = generations[kind] ?: 0L
}

internal fun LibraryState.copyKind(kind: LibraryKind, from: LibraryState): LibraryState = when (kind) {
    LibraryKind.WATCHLIST -> copy(watchlist = from.watchlist)
    LibraryKind.FAVOURITE -> copy(favourite = from.favourite)
    LibraryKind.RATING -> copy(rating = from.rating)
}

internal fun LibraryState.has(kind: LibraryKind): Boolean = when (kind) {
    LibraryKind.WATCHLIST -> watchlist
    LibraryKind.FAVOURITE -> favourite
    LibraryKind.RATING -> rating != null
}
