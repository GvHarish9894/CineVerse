package com.techgv.cineverse.data.library

import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import kotlinx.coroutines.flow.first

/** One import step of one title (§8.3 rule 3). */
data class ImportStep(val ref: MediaRef, val kind: LibraryKind)

sealed interface ImportOffer {
    /** Never asked for this account and the TV has [items] guest titles. */
    data class Ask(val items: Int) : ImportOffer
    /**
     * "Import" was chosen but the import did not finish (Later, process death, abort) and [items] guest titles are
     * not fully imported: ask whether to resume. Offered once per launch.
     */
    data class Resume(val items: Int) : ImportOffer
}

sealed interface ImportResult {
    /**
     * [unconfirmed]: steps whose outcome is unknown and whose remote state is absent — "Couldn't confirm {n} items";
     * [unconfirmedTitles]: their titles, one per title, for that list.
     */
    data class Done(val written: Int, val unconfirmed: List<ImportStep>, val unconfirmedTitles: List<String> = emptyList()) : ImportResult
    /** Reading the account's lists failed: nothing was written. */
    data class Aborted(val error: TmdbError) : ImportResult
    /** The account was signed out before a POST: stopped there, unfinished (offered for resuming). */
    data object Superseded : ImportResult
}

/**
 * §8.3 guest → account import: read all six lists first; union for watchlist / favourites, TMDB's rating wins;
 * per title rating → favourite → watchlist, watchlist only after the rating step is confirmed. The ledger
 * marks each step `attempting` before its POST and `done` after success or when already satisfied, so a step
 * whose outcome is unknown is reconciled through `account_states` instead of retried blindly.
 */
class LibraryImporter(
    private val ledger: ImportLedgerDao,
    private val guest: GuestLibrarySource,
    private val account: AccountLibrarySource,
    private val repository: LibraryRepository,
) {
    /** Accounts whose resume prompt this process already offered ("once per launch"). */
    private val resumeOffered = mutableSetOf<String>()

    suspend fun offer(c: Credentials): ImportOffer? {
        val aoid = c.accountObjectId.value
        val offer = ledger.offer(aoid)
        return when {
            offer == null -> guest.all().map { it.title.ref }.toSet().size.takeIf { it > 0 }?.let { ImportOffer.Ask(it) }
            offer.imported && !offer.complete && resumeOffered.add(aoid) -> {
                val left = guest.all().filter { ledger.state(aoid, it.title.ref.mediaType.path, it.title.ref.id, it.kind.name) != ImportLedgerEntity.DONE }
                ImportOffer.Resume(left.map { it.title.ref }.toSet().size)
            }
            else -> null
        }
    }

    suspend fun keepSeparate(c: Credentials) = ledger.putOffer(ImportOfferEntity(c.accountObjectId.value, imported = false, complete = true))

    /** The user closed the result (incl. with unconfirmed items left): never offered again. */
    suspend fun finish(c: Credentials) = ledger.putOffer(ImportOfferEntity(c.accountObjectId.value, imported = true, complete = true))

    /**
     * Runs (or resumes) the import. [retry]: unconfirmed steps the user chose to write again after the
     * warning that items removed on TMDB may be re-added.
     */
    suspend fun import(c: Credentials, retry: Set<ImportStep> = emptySet()): ImportResult {
        val aoid = c.accountObjectId.value
        ledger.putOffer(ImportOfferEntity(aoid, imported = true, complete = false))
        account.invalidateLists() // rule 1 reads TMDB, not the 10-min list cache
        val remote = try {
            LibraryKind.entries.associateWith { kind ->
                MediaType.entries.flatMap { media -> account.all(c, kind, media) }.map { it.title.ref }.toSet()
            }
        } catch (e: TmdbException) {
            return ImportResult.Aborted(e.error)
        }
        var written = 0
        val unconfirmed = mutableListOf<ImportStep>()
        val titles = guest.all().groupBy { it.title.ref }
        try {
            for ((ref, rows) in titles) {
                var ratingPosted = false
                for (kind in STEP_ORDER) {
                    val row = rows.firstOrNull { it.kind == kind } ?: continue
                    val step = ImportStep(ref, kind)
                    // D6: our rating POST may have taken the title off the watchlist the snapshot saw.
                    val inSnapshot = ref in remote.getValue(kind) && !(kind == LibraryKind.WATCHLIST && ratingPosted)
                    val outcome = runStep(c, step, row.value, inSnapshot, step in retry)
                    if (kind == LibraryKind.RATING && (outcome == Outcome.Written || outcome == Outcome.Landed)) ratingPosted = true
                    when (outcome) {
                        Outcome.Written -> written++
                        Outcome.Satisfied, Outcome.Landed -> Unit
                        Outcome.Superseded -> return ImportResult.Superseded
                        Outcome.Unconfirmed -> {
                            unconfirmed += step
                            if (kind == LibraryKind.RATING) break // rule 3: the title waits for its rating
                        }
                    }
                }
            }
        } finally {
            // Also after attempted / unknown writes and on cancellation; bound to c, so never another account's states.
            repository.onExternalWrites(c)
        }
        if (unconfirmed.isEmpty()) finish(c)
        return ImportResult.Done(written, unconfirmed, unconfirmed.map { it.ref }.distinct().map { titles.getValue(it).first().title.title })
    }

    /** [Landed]: the POST errored but TMDB has the value. [Superseded]: [c] was signed out before the POST. */
    private enum class Outcome { Written, Satisfied, Landed, Unconfirmed, Superseded }

    /**
     * Decided holding the (title, kind) write lock, against TMDB's state at execution time unless the snapshot
     * already has it: a user write that committed while the import waited (e.g. a rating) wins.
     */
    private suspend fun runStep(c: Credentials, step: ImportStep, value: Double?, inSnapshot: Boolean, forced: Boolean): Outcome {
        val previous = ledger.state(c.accountObjectId.value, step.ref.mediaType.path, step.ref.id, step.kind.name)
        if (previous == ImportLedgerEntity.DONE) return Outcome.Satisfied
        return repository.serialized(step.ref, step.kind) {
            val present = if (previous == ImportLedgerEntity.ATTEMPTING || !inSnapshot) remoteHas(c, step) else true
            when {
                present == true -> {
                    mark(c, step, ImportLedgerEntity.DONE)
                    Outcome.Satisfied
                }
                present == null -> Outcome.Unconfirmed
                // A prior attempt has an unknown outcome and TMDB lacks it: ambiguous (maybe removed on TMDB).
                previous == ImportLedgerEntity.ATTEMPTING && !forced -> Outcome.Unconfirmed
                // The account_states read above may have outlived the sign-in: re-checked right before the POST.
                repository.credentials.first() != c -> Outcome.Superseded
                else -> post(c, step, value)
            }
        }
    }

    private suspend fun post(c: Credentials, step: ImportStep, value: Double?): Outcome {
        mark(c, step, ImportLedgerEntity.ATTEMPTING)
        return try {
            account.write(c, step.ref, step.kind, target(step.kind, value))
            mark(c, step, ImportLedgerEntity.DONE)
            Outcome.Written
        } catch (_: TmdbException) {
            // Unknown outcome: the POST may have landed. Present now → done; absent → ambiguous, not resumed.
            if (remoteHas(c, step) == true) {
                mark(c, step, ImportLedgerEntity.DONE)
                Outcome.Landed
            } else {
                Outcome.Unconfirmed
            }
        }
    }

    /** TMDB's current state for the step's kind; null when it cannot be read. */
    private suspend fun remoteHas(c: Credentials, step: ImportStep): Boolean? = try {
        account.states(c, step.ref).has(step.kind)
    } catch (_: TmdbException) {
        null
    }

    private suspend fun mark(c: Credentials, step: ImportStep, state: String) = ledger.put(
        ImportLedgerEntity(c.accountObjectId.value, step.ref.mediaType.path, step.ref.id, step.kind.name, state),
    )

    private fun target(kind: LibraryKind, value: Double?) =
        LibraryState(known = true, watchlist = kind == LibraryKind.WATCHLIST, favourite = kind == LibraryKind.FAVOURITE, rating = value, pending = emptySet())

    private companion object {
        val STEP_ORDER = listOf(LibraryKind.RATING, LibraryKind.FAVOURITE, LibraryKind.WATCHLIST)
    }
}
