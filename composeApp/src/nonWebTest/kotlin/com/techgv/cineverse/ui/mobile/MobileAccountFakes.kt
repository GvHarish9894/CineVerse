package com.techgv.cineverse.ui.mobile

import com.techgv.cineverse.data.auth.*
import com.techgv.cineverse.data.library.*
import com.techgv.cineverse.data.search.RecentQueryStore
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.data.tmdb.CatalogRepository
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/** Fixtures that never sign in or import: no pending attempt, and every account already answered the import. */
internal fun inertApproval(auth: AuthRepository, scope: CoroutineScope) =
    NativeApprovalCoordinator(auth, InMemoryPendingAttemptStore(), MobileAccountOpener(), { error("No sign-in in this fixture") }, scope)

internal fun inertImporter(library: LibraryRepository): LibraryImporter {
    val store = MobileImportStore(answered = true)
    return LibraryImporter(store, store, GuestLibrarySource(MobileGuestStore()), MobileImportAccount(), library)
}

/** Real coordinator/importer/settings over fakes; identity changes only when [auth] emits. */
internal class MobileAccountFixture(scope: CoroutineScope, val pending: PendingAttemptStore = InMemoryPendingAttemptStore()) {
    val store = MobileSettingsStore()
    val settings = SettingsRepository(store)
    val generation = GenerationCounter()
    val auth = MobileAccountAuth()
    val guest = MobileGuestStore()
    val account = MobileImportAccount()
    val imports = MobileImportStore()
    val opener = MobileAccountOpener()
    var codes = 0
    var codeFailure: Exception? = null
    val library = LibraryRepository(auth.session, GuestLibrarySource(guest), account)
    // Own SupervisorJob as in production: a failed exchange surfaces through await, not backgroundScope's failure reporter.
    val approval = NativeApprovalCoordinator(auth, pending, opener, {
        codes++
        codeFailure?.let { throw it }
        RequestCode("token-$codes")
    }, CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])))
    val graph = MobileApplication(CatalogRepository(MobileCatalogApi(), generation, settings::current), generation, settings, auth,
        library, RecentQueryStore(store), scope, approval, LibraryImporter(imports, imports, GuestLibrarySource(guest), account, library))

    fun guestTitle(id: Int) {
        guest.rows.value += GuestLibraryRow(MediaRef(MediaType.Movie, id), LibraryKind.WATCHLIST, null, 0, """{"title":"Guest $id"}""")
    }
}

internal class MobileAccountAuth : AuthRepository {
    val emissions = MutableSharedFlow<AuthSession?>(replay = 1)
    override val session = emissions
    override val sessionExpired = emptyFlow<Unit>()
    var approval: suspend () -> SignInOutcome = { SignInOutcome.TimedOut }
    var waits = 0
    var signOuts = 0
    var signOutFailure: Exception? = null
    override suspend fun createRequestCode(): RequestCode = error("The coordinator owns request creation")
    override suspend fun awaitApproval(code: RequestCode): SignInOutcome {
        waits++
        return approval()
    }
    override suspend fun signOut(onSignedOutLocally: () -> Unit) {
        signOuts++
        signOutFailure?.let { throw it }
        emissions.emit(null)
        onSignedOutLocally()
    }
    override suspend fun expireSession(failed: AuthSession): Unit = error("Not used")
}

internal class MobileAccountOpener : NativeApprovalOpener {
    val urls = mutableListOf<String>()
    var callback: ((String) -> Unit)? = null
    var cancels = 0
    override fun open(url: String, onCallback: (String) -> Unit, onCancel: () -> Unit) {
        urls += url
        callback = onCallback
    }
    override fun cancel() { cancels++ }
}

internal class MobileImportStore(private val answered: Boolean = false) : ImportLedgerStore, ImportOfferStore {
    val offers = mutableMapOf<String, ImportOfferRecord>()
    val offerReads = mutableListOf<String>()
    var failOffers = false
    private val ledger = mutableMapOf<Triple<String, MediaRef, LibraryKind>, ImportStepState>()
    override suspend fun offer(accountObjectId: String): ImportOfferRecord? {
        offerReads += accountObjectId
        if (failOffers) throw StorageException("fake import offer read")
        return if (answered) ImportOfferRecord(accountObjectId, imported = false, complete = true) else offers[accountObjectId]
    }
    override suspend fun put(offer: ImportOfferRecord) { offers[offer.accountObjectId] = offer }
    override suspend fun state(accountObjectId: String, ref: MediaRef, kind: LibraryKind) = ledger[Triple(accountObjectId, ref, kind)]
    override suspend fun put(accountObjectId: String, ref: MediaRef, kind: LibraryKind, state: ImportStepState) {
        ledger[Triple(accountObjectId, ref, kind)] = state
    }
}

internal class MobileImportAccount : AccountLibrarySource {
    val writes = mutableListOf<Pair<AuthSession, MediaRef>>()
    override suspend fun page(session: AuthSession, kind: LibraryKind, media: MediaType, page: Int) = LibraryPage(emptyList(), 1, 1)
    override fun invalidateLists() = Unit
    override suspend fun states(session: AuthSession, ref: MediaRef) = LibraryState(true, false, false, null, emptySet())
    override suspend fun write(session: AuthSession, ref: MediaRef, kind: LibraryKind, target: LibraryState) { writes += session to ref }
}
