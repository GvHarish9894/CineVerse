package com.techgv.cineverse.data.library

import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType

/** One import step of one title (§8.3 rule 3). */
data class ImportStep(val ref: MediaRef, val kind: LibraryKind)

sealed interface ImportOffer {
    /** Never asked for this account and the TV has [items] guest titles. */
    data class Ask(val items: Int) : ImportOffer
    /** "Import" was chosen but the import did not finish (process death, abort): run it again. */
    data object Resume : ImportOffer
}

sealed interface ImportResult {
    /**
     * [unconfirmed]: steps whose outcome is unknown and whose remote state is absent — "Couldn't confirm {n} items";
     * [unconfirmedTitles]: their titles, one per title, for that list.
     */
    data class Done(val written: Int, val unconfirmed: List<ImportStep>, val unconfirmedTitles: List<String> = emptyList()) : ImportResult
    /** Reading the account's lists failed: nothing was written. */
    data class Aborted(val error: TmdbError) : ImportResult
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
    suspend fun offer(c: Credentials): ImportOffer? {
        val offer = ledger.offer(c.accountObjectId.value)
        return when {
            offer == null -> guest.all().map { it.title.ref }.toSet().size.takeIf { it > 0 }?.let { ImportOffer.Ask(it) }
            offer.imported && !offer.complete -> ImportOffer.Resume
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
                for (kind in STEP_ORDER) {
                    val row = rows.firstOrNull { it.kind == kind } ?: continue
                    val step = ImportStep(ref, kind)
                    when (runStep(c, step, row.value, ref in remote.getValue(kind), step in retry)) {
                        Outcome.Written -> written++
                        Outcome.Satisfied -> Unit
                        Outcome.Unconfirmed -> {
                            unconfirmed += step
                            if (kind == LibraryKind.RATING) break // rule 3: the title waits for its rating
                        }
                    }
                }
            }
        } finally {
            if (written > 0 || retry.isNotEmpty()) repository.onExternalWrites()
        }
        if (unconfirmed.isEmpty()) finish(c)
        return ImportResult.Done(written, unconfirmed, unconfirmed.map { it.ref }.distinct().map { titles.getValue(it).first().title.title })
    }

    private enum class Outcome { Written, Satisfied, Unconfirmed }

    private suspend fun runStep(c: Credentials, step: ImportStep, value: Double?, inSnapshot: Boolean, forced: Boolean): Outcome {
        val previous = ledger.state(c.accountObjectId.value, step.ref.mediaType.path, step.ref.id, step.kind.name)
        if (previous == ImportLedgerEntity.DONE) return Outcome.Satisfied
        val satisfied = if (previous == ImportLedgerEntity.ATTEMPTING) {
            // A prior attempt has an unknown outcome: reconcile against TMDB's current state.
            when (remoteHas(c, step)) {
                true -> true
                false -> if (forced) false else return Outcome.Unconfirmed // ambiguous: maybe removed on TMDB
                null -> return Outcome.Unconfirmed
            }
        } else {
            inSnapshot
        }
        if (satisfied) {
            mark(c, step, ImportLedgerEntity.DONE)
            return Outcome.Satisfied
        }
        mark(c, step, ImportLedgerEntity.ATTEMPTING)
        return try {
            repository.serialized(step.ref, step.kind) { account.write(c, step.ref, step.kind, target(step.kind, value)) }
            mark(c, step, ImportLedgerEntity.DONE)
            Outcome.Written
        } catch (_: TmdbException) {
            // Unknown outcome: the POST may have landed. Present now → done; absent → ambiguous, not resumed.
            if (remoteHas(c, step) == true) {
                mark(c, step, ImportLedgerEntity.DONE)
                Outcome.Satisfied
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
