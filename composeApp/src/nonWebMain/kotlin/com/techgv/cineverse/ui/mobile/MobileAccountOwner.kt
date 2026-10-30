package com.techgv.cineverse.ui.mobile

import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.auth.NativeApprovalCoordinator
import com.techgv.cineverse.data.auth.NativeApprovalStatus
import com.techgv.cineverse.data.auth.SignInOutcome
import com.techgv.cineverse.data.library.LibraryImporter
import com.techgv.cineverse.data.settings.AccountDefaults
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.storage.StorageException
import com.techgv.cineverse.presentation.ImportLibraryStateHolder
import com.techgv.cineverse.presentation.ImportPrompt
import com.techgv.cineverse.presentation.accountDefaultsOffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class MobileApprovalPhase {
    Starting, Waiting, Resume, Checking, StillWaiting, Cancelled, CheckFailed, StartFailed, Expired, Rejected, Failed, Unavailable,
}

enum class MobileSignOutPhase { Confirm, SigningOut, Failed }

/** The one account dialog the root shows, already arbitrated: sign-out > approval > defaults > import. */
sealed interface MobileAccountDialog {
    data class SignOut(val session: AuthSession, val phase: MobileSignOutPhase) : MobileAccountDialog
    data class Approval(val phase: MobileApprovalPhase) : MobileAccountDialog
    data class Defaults(val session: AuthSession, val offer: AccountDefaults) : MobileAccountDialog
    data class Import(val session: AuthSession, val prompt: ImportPrompt) : MobileAccountDialog
}

/**
 * App-lifetime native account owner, created by [MobileApplication]. [MobileApplication.identity] is the only proof of
 * sign-in: a browser callback or a coordinator outcome never manufactures one. Failed writes are never replayed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MobileAccountOwner internal constructor(
    private val graph: MobileApplication,
    private val approval: NativeApprovalCoordinator,
    importer: LibraryImporter,
    private val settings: SettingsRepository,
    private val auth: AuthRepository,
    private val scope: CoroutineScope,
) {
    // Identity-compared so a late completion never rewrites a newer request.
    private class ApprovalRequest(val starting: Boolean = false, val startFailed: Boolean = false, val resumed: Boolean = false)

    private val request = MutableStateFlow<ApprovalRequest?>(null)
    private val signOut = MutableStateFlow<MobileAccountDialog.SignOut?>(null)
    private val deferredDefaults = MutableStateFlow<AccountDefaults?>(null)
    /** The session whose import prompt may show: fed to the holder and past its sign-in's defaults offer. */
    private val importAccount = MutableStateFlow<AuthSession?>(null)
    private val settled = MutableStateFlow<SignInOutcome?>(null)
    private var acted: ImportPrompt? = null
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-shot toasts for the root. */
    val messages = mutableMessages.asSharedFlow()
    private val imports = ImportLibraryStateHolder(importer, scope, onImported = { mutableMessages.tryEmit("Guest library imported") })
    private val importPrompt = imports.prompt.stateIn(scope, SharingStarted.Eagerly, null)

    val dialog: Flow<MobileAccountDialog?> = combine(
        signOut,
        combine(request, approval.status, approval.outcome, ::phaseOf),
        graph.identity.flatMapLatest<MobileIdentity?, MobileAccountDialog?> { identity ->
            val session = identity?.session ?: return@flatMapLatest flowOf(null)
            val pending = settings.pendingAccountDefaults.catch { if (it is StorageException) emit(emptyMap()) else throw it }
            combine(pending, deferredDefaults, importAccount, importPrompt) { offers, deferred, importFor, prompt ->
                offers[session.accountObjectId.value]?.takeIf { it != deferred }?.let { MobileAccountDialog.Defaults(session, it) }
                    ?: prompt?.takeIf { importFor == session }?.let { MobileAccountDialog.Import(session, it) }
            }
        },
    ) { out, phase, account -> out ?: phase?.let(MobileAccountDialog::Approval) ?: account }

    init {
        scope.launch {
            graph.identity.collect { identity ->
                if (identity?.session != null) request.value = null
                signOut.update { if (it != null && it.phase != MobileSignOutPhase.SigningOut && identity != MobileIdentity(it.session)) null else it }
            }
        }
        scope.launch {
            var offered: SignInOutcome? = null
            combine(approval.outcome, graph.identity, ::Pair).collect { (outcome, identity) ->
                if (outcome is SignInOutcome.SignedIn && outcome !== offered && identity == MobileIdentity(outcome.session)) {
                    offered = outcome
                    offerDefaults(outcome)
                }
            }
        }
        scope.launch {
            graph.identity.map { it?.session }.distinctUntilChanged().collectLatest { session ->
                importAccount.value = null
                imports.onAccount(session)
                if (session == null) return@collectLatest
                // Defaults precede import: wait for the exchange that may have committed this session to settle its offer.
                combine(approval.status, approval.outcome, settled) { status, outcome, done ->
                    status != NativeApprovalStatus.Checking &&
                        (outcome !is SignInOutcome.SignedIn || outcome.session != session || done === outcome)
                }.first { it }
                importAccount.value = session
            }
        }
        scope.launch {
            if (graph.identity.filterNotNull().first() != MobileIdentity(null)) return@launch
            val pending = try { approval.pendingAttempt() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (pending != null && guest()) request.compareAndSet(null, ApprovalRequest(resumed = true))
        }
    }

    /** Library/Profile guest Sign in. Single-flight while an approval dialog is showing. */
    fun onSignIn() {
        if (approvalPhase() != null) return
        // A returning callback may be exchanging with no dialog showing: reattach to it instead of restarting.
        if (approval.status.value == NativeApprovalStatus.Checking) request.compareAndSet(null, ApprovalRequest()) else begin()
    }

    fun startAgain() {
        if (approvalPhase() in RESTARTABLE) begin()
    }

    fun checkAgain() {
        if (approvalPhase() !in CHECKABLE) return
        val current = request.value
        scope.launch {
            val result = try { approval.checkAgain() } catch (e: CancellationException) { throw e } catch (_: Exception) {
                return@launch // The coordinator publishes Failed.
            }
            // No attempt is waiting any more (another path consumed it).
            if (result == null && approval.status.value != NativeApprovalStatus.Failed) request.compareAndSet(current, null)
        }
    }

    fun dismissApproval() {
        val phase = approvalPhase() ?: return
        request.value = null
        // Stops the browser and any exchange; the pending token stays for a manual return.
        if (phase in OPEN) scope.launch { try { approval.cancel() } catch (e: CancellationException) { throw e } catch (_: Exception) {} }
    }

    /** Profile's sign-out with the exact rendered session. */
    fun onSignOut(session: AuthSession) {
        if (signedIn(session)) signOut.compareAndSet(null, MobileAccountDialog.SignOut(session, MobileSignOutPhase.Confirm))
    }

    fun confirmSignOut(rendered: MobileAccountDialog.SignOut) {
        if (rendered.phase != MobileSignOutPhase.Confirm || signOut.value !== rendered) return
        if (!signedIn(rendered.session)) { signOut.compareAndSet(rendered, null); return }
        val running = rendered.copy(phase = MobileSignOutPhase.SigningOut)
        if (!signOut.compareAndSet(rendered, running)) return
        scope.launch {
            var local = false
            try {
                if (signedIn(rendered.session)) auth.signOut { local = true }
                signOut.compareAndSet(running, null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                signOut.compareAndSet(running, if (local) null else running.copy(phase = MobileSignOutPhase.Failed))
            }
        }
    }

    fun dismissSignOut(rendered: MobileAccountDialog.SignOut) {
        if (rendered.phase != MobileSignOutPhase.SigningOut) signOut.compareAndSet(rendered, null)
    }

    /** Apply or Dismiss (durable). Both recheck the exact session and the persisted offer that was shown. */
    fun resolveDefaults(rendered: MobileAccountDialog.Defaults, apply: Boolean) {
        if (!signedIn(rendered.session)) return
        scope.launch {
            try {
                if (settings.pendingAccountDefaults.first()[rendered.offer.accountObjectId] == rendered.offer && signedIn(rendered.session)) {
                    settings.resolveAccountDefaults(rendered.offer.accountObjectId, apply)
                }
            } catch (_: StorageException) {
                if (signedIn(rendered.session)) mutableMessages.tryEmit("Couldn't save content preferences. Nothing was changed.")
            }
        }
    }

    /** Close/back: hidden for this app run; the offer stays pending. */
    fun laterDefaults(rendered: MobileAccountDialog.Defaults) {
        if (signedIn(rendered.session)) deferredDefaults.value = rendered.offer
    }

    fun importLibrary(rendered: MobileAccountDialog.Import) = importAction(rendered, { it is ImportPrompt.Ask }) { imports.import() }

    fun keepSeparate(rendered: MobileAccountDialog.Import) =
        importAction(rendered, { it is ImportPrompt.Ask && !it.resume }) { imports.keepSeparate() }

    fun finishImport(rendered: MobileAccountDialog.Import) = importAction(rendered, { it is ImportPrompt.Unconfirmed }) { imports.close() }

    /** Unconfirmed: the warned re-write. Failed: exactly the failed operation, only on this press. */
    fun retryImport(rendered: MobileAccountDialog.Import) =
        importAction(rendered, { it is ImportPrompt.Unconfirmed || it == ImportPrompt.Failed }, once = rendered.prompt != ImportPrompt.Failed) {
            imports.retry()
        }

    /** Close/back/Later: stops work without a durable answer. */
    fun laterImport(rendered: MobileAccountDialog.Import) = importAction(rendered, { true }, once = false) { imports.later() }

    internal fun setPlayerActive(active: Boolean) = imports.setPlayerActive(active)

    private inline fun importAction(
        rendered: MobileAccountDialog.Import,
        allowed: (ImportPrompt) -> Boolean,
        once: Boolean = true,
        action: () -> Unit,
    ) {
        val prompt = rendered.prompt
        if (!signedIn(rendered.session) || importAccount.value != rendered.session || importPrompt.value !== prompt || !allowed(prompt)) return
        // Ask/Unconfirmed instances accept one durable action; singleton Failed/Running prompts can recur.
        if (once && prompt === acted) return
        if (once) acted = prompt
        action()
    }

    private fun begin() {
        if (!guest()) return
        val next = ApprovalRequest(starting = true)
        request.value = next
        scope.launch {
            // Identity may have changed, or the dialog been dismissed, before this dispatch.
            if (request.value !== next) return@launch
            if (!guest()) { request.compareAndSet(next, null); return@launch }
            try {
                approval.begin()
                request.compareAndSet(next, ApprovalRequest())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                request.compareAndSet(next, ApprovalRequest(startFailed = true))
            }
        }
    }

    private suspend fun offerDefaults(outcome: SignInOutcome.SignedIn) {
        try {
            val offer = accountDefaultsOffer(outcome.session, outcome.account, settings.current())
            // Persisted first; the dialog only ever shows the stored offer of the current session.
            if (offer != null && signedIn(outcome.session)) settings.offerAccountDefaults(offer)
        } catch (_: StorageException) {
            // Not replayed; the sign-in stands without an offer.
        } finally {
            settled.value = outcome
        }
    }

    private fun approvalPhase() = phaseOf(request.value, approval.status.value, approval.outcome.value)

    private fun phaseOf(r: ApprovalRequest?, status: NativeApprovalStatus, outcome: SignInOutcome?): MobileApprovalPhase? = when {
        r == null -> null
        r.starting -> MobileApprovalPhase.Starting
        r.startFailed -> MobileApprovalPhase.StartFailed
        status == NativeApprovalStatus.Checking -> MobileApprovalPhase.Checking
        status == NativeApprovalStatus.Failed -> MobileApprovalPhase.CheckFailed
        status == NativeApprovalStatus.Cancelled -> MobileApprovalPhase.Cancelled
        else -> when (outcome) {
            is SignInOutcome.SignedIn -> null
            SignInOutcome.CodeExpired -> MobileApprovalPhase.Expired
            is SignInOutcome.Rejected -> MobileApprovalPhase.Rejected
            is SignInOutcome.Failed -> MobileApprovalPhase.Failed
            SignInOutcome.AppCredentialError -> MobileApprovalPhase.Unavailable
            SignInOutcome.TimedOut -> MobileApprovalPhase.StillWaiting
            else -> if (r.resumed) MobileApprovalPhase.Resume else MobileApprovalPhase.Waiting
        }
    }

    private fun guest() = graph.identity.value == MobileIdentity(null)
    private fun signedIn(session: AuthSession) = graph.identity.value == MobileIdentity(session) && !graph.identityFailed.value

    private companion object {
        val CHECKABLE = setOf(MobileApprovalPhase.Waiting, MobileApprovalPhase.Resume, MobileApprovalPhase.StillWaiting,
            MobileApprovalPhase.Cancelled, MobileApprovalPhase.CheckFailed)
        val RESTARTABLE = setOf(MobileApprovalPhase.StartFailed, MobileApprovalPhase.Expired, MobileApprovalPhase.Rejected,
            MobileApprovalPhase.Failed)
        val OPEN = setOf(MobileApprovalPhase.Starting, MobileApprovalPhase.Waiting, MobileApprovalPhase.Checking,
            MobileApprovalPhase.StillWaiting)
    }
}
