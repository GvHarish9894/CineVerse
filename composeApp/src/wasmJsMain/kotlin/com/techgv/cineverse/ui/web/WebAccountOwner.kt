package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.di.WebRuntime
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.presentation.ImportPrompt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Closed = no sign-out dialog. SignedOut is shown only after an authoritative read of a guest session. */
enum class WebSignOutPhase { Closed, Confirm, SigningOut, Checking, SignedOut, StillSignedIn, Changed, Unknown }

/** Import feedback that has no ImportPrompt: no offer for this account, a completed import, or a stale request. */
enum class WebImportNotice { NothingToImport, Imported, Changed }

/** Display name only: no account id, generation, token or exception is exposed to rendering/saving. */
data class WebAccountState(
    val signOut: WebSignOutPhase = WebSignOutPhase.Closed,
    val accountName: String? = null,
    val canConfirmSignOut: Boolean = false,
    val importPrompt: ImportPrompt? = null,
    val importNotice: WebImportNotice? = null,
    val importBusy: Boolean = false,
)

/**
 * One owner per browser APP lifetime, above routes (like WebApprovalOwner). Root injects the runtime, hosts
 * WebSignOutDialog + WebImportDialog, forwards Settings' captured session (onSignOut = ::startSignOut,
 * onImport = ::startImport) and the player state, and closes it only at app teardown. It must be the only caller
 * of runtime.settingsHolder.signOut(), whose results it consumes. All methods run on the app's UI dispatcher.
 * Disposal revokes feedback; it never cancels a sent sign-out or a running import.
 */
class WebAccountOwner(private val runtime: WebRuntime, appScope: CoroutineScope) : AutoCloseable {
    private val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job]))
    private val imports = runtime.importController
    private var disposed = false
    private var captured: AuthSession? = null
    private var revision = 0L
    /** Ticket of the sign-out whose outcome is not known yet; non-null blocks another sign-out. */
    private var pending: Long? = null
    private var offering = false
    private var playerActive = false
    private var noticeFor: AuthSession? = null
    private val mutable = MutableStateFlow(WebAccountState())
    val state: StateFlow<WebAccountState> = mutable.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            imports.prompt.collect { prompt ->
                // Every holder transition ends the action that caused it; a prompt supersedes a notice.
                if (active()) mutable.update { it.copy(importPrompt = prompt, importBusy = false,
                    importNotice = if (prompt != null) null else it.importNotice) }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.importsCompleted.collect { notice(WebImportNotice.Imported) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.settingsHolder.signOutResults.collect { signedOut ->
                val ticket = pending
                // Late results of a resolved/revoked sign-out drop. true is only sent after the repository read a guest.
                if (!active() || ticket == null) return@collect
                if (signedOut) resolve(WebSignOutPhase.SignedOut) else readOutcome(ticket)
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.session.collect { if (active()) onSession(it) }
        }
    }

    /** Settings' onSignOut: [expected] is the rendered session, compared with full equality incl. generation. */
    fun startSignOut(expected: AuthSession) {
        if (!active() || pending != null) return
        captured = expected
        // Only a verified Ready session can prove a change; Checking/Failed keeps Confirm (disabled) until a read lands.
        val now = runtime.session.value
        publishSignOut(if (now is WebSessionState.Ready && now.session != expected) WebSignOutPhase.Changed else WebSignOutPhase.Confirm)
    }

    fun confirmSignOut() {
        val expected = captured ?: return
        if (!active() || pending != null || mutable.value.signOut != WebSignOutPhase.Confirm) return
        if (authoritative() != expected) return publishSignOut()
        val ticket = ++revision
        pending = ticket
        publishSignOut(WebSignOutPhase.SigningOut)
        // Observers may close/dismiss or a session change may land reentrantly: re-check before the write.
        if (!current(ticket)) return
        if (authoritative() != expected) {
            pending = null
            return publishSignOut(WebSignOutPhase.Confirm)
        }
        runtime.settingsHolder.signOut()
    }

    /** Unknown outcome: read the session again. Never sends sign-out again. */
    fun checkSignOut() {
        if (!active() || pending != null || mutable.value.signOut != WebSignOutPhase.Unknown) return
        val ticket = ++revision
        pending = ticket
        readOutcome(ticket)
    }

    /** Settings' onImport: re-offers the existing holder flow for [expected] only. */
    fun startImport(expected: AuthSession) {
        if (!active()) return
        if (authoritative() != expected) {
            if (runtime.session.value is WebSessionState.Ready) notice(WebImportNotice.Changed) // unknown session: no claim
            return
        }
        // An open prompt, an offer in flight or a running Player keeps the existing flow; it is never restarted.
        if (playerActive || offering || mutable.value.importPrompt != null) return
        offering = true
        mutable.update { it.copy(importNotice = null) }
        scope.launch {
            try {
                if (!importCurrent(expected)) return@launch
                imports.onAccount(expected)
                if (importCurrent(expected) && !playerActive && imports.prompt.first() == null) {
                    notice(WebImportNotice.NothingToImport)
                }
            } finally {
                offering = false
            }
        }
    }

    fun importLibrary() = importAction({ it is ImportPrompt.Ask }) { imports.import() }

    fun keepSeparate() = importAction({ it is ImportPrompt.Ask && !it.resume }) { imports.keepSeparate() }

    /** Unconfirmed → the durable "Done": this account is not offered again. */
    fun finishImport() = importAction({ it is ImportPrompt.Unconfirmed }) { imports.close() }

    /** Unconfirmed: the warned, explicit re-write. Failed: retries exactly the failed operation. */
    fun retryImport() {
        // A Failed retry may end in the same Failed value (no new emission), so it cannot hold importBusy.
        if (mutable.value.importPrompt == ImportPrompt.Failed) { if (active()) imports.retry() }
        else importAction({ it is ImportPrompt.Unconfirmed }) { imports.retry() }
    }

    /** Stops a running import and closes any prompt without a durable answer; the ledger reconciles later. */
    fun later() {
        if (active() && mutable.value.importPrompt != null) imports.later()
    }

    /** Root forwards the Player state here (not to the holder directly) so startImport also defers. */
    fun setPlayerActive(playing: Boolean) {
        if (!active()) return
        playerActive = playing
        imports.setPlayerActive(playing)
    }

    /** Escape/scrim/Close of the visible dialog only. A pending sign-out can be neither dismissed nor cancelled. */
    fun dismiss() {
        if (!active()) return
        when (mutable.value.signOut) {
            WebSignOutPhase.SigningOut, WebSignOutPhase.Checking -> Unit
            WebSignOutPhase.Closed -> if (mutable.value.importPrompt != null) later()
                else mutable.update { it.copy(importNotice = null) }
            else -> {
                captured = null
                mutable.update { it.copy(signOut = WebSignOutPhase.Closed, accountName = null, canConfirmSignOut = false) }
            }
        }
    }

    private fun onSession(session: WebSessionState) {
        val ready = session as? WebSessionState.Ready
        if (ready != null && mutable.value.importNotice != null && ready.session != noticeFor) {
            mutable.update { it.copy(importNotice = null) }
        }
        when (mutable.value.signOut) {
            // Only another verified value resolves early; the captured one keeps waiting for the holder result.
            WebSignOutPhase.SigningOut -> if (ready != null && ready.session != captured) {
                resolve(if (ready.session == null) WebSignOutPhase.SignedOut else WebSignOutPhase.Changed)
            }
            WebSignOutPhase.Confirm ->
                publishSignOut(if (ready != null && ready.session != captured) WebSignOutPhase.Changed else WebSignOutPhase.Confirm)
            else -> Unit
        }
    }

    private fun readOutcome(ticket: Long) {
        publishSignOut(WebSignOutPhase.Checking)
        scope.launch {
            if (!current(ticket)) return@launch
            runtime.revalidateSession() // Read only.
            val session = runtime.session.first { it !is WebSessionState.Checking }
            if (!current(ticket)) return@launch
            resolve(when (session) {
                is WebSessionState.Ready -> when (session.session) {
                    null -> WebSignOutPhase.SignedOut
                    captured -> WebSignOutPhase.StillSignedIn
                    else -> WebSignOutPhase.Changed
                }
                else -> WebSignOutPhase.Unknown
            })
        }
    }

    private inline fun importAction(allowed: (ImportPrompt) -> Boolean, action: () -> Unit) {
        val prompt = mutable.value.importPrompt
        if (!active() || prompt == null || mutable.value.importBusy || !allowed(prompt)) return
        mutable.update { it.copy(importBusy = true) }
        // Observers of importBusy may close/dismiss reentrantly: re-check before the holder starts IO.
        if (active() && mutable.value.importPrompt == prompt) action()
    }

    private fun notice(notice: WebImportNotice) {
        if (!active()) return
        noticeFor = authoritative()
        mutable.update { it.copy(importNotice = notice) }
    }

    private fun resolve(phase: WebSignOutPhase) {
        pending = null
        publishSignOut(phase)
    }

    private fun publishSignOut(phase: WebSignOutPhase = mutable.value.signOut) {
        if (!active()) return
        mutable.update { it.copy(signOut = phase, accountName = captured?.displayName,
            canConfirmSignOut = phase == WebSignOutPhase.Confirm && pending == null && authoritative() == captured) }
    }

    /** Null for a verified guest AND for unknown; only ever compared with a non-null captured session. */
    private fun authoritative(): AuthSession? = (runtime.session.value as? WebSessionState.Ready)?.session
    private fun importCurrent(expected: AuthSession): Boolean = active() && authoritative() == expected
    private fun active(): Boolean = !disposed && scope.isActive
    private fun current(ticket: Long): Boolean = active() && pending == ticket

    override fun close() {
        if (disposed) return
        disposed = true
        pending = null
        captured = null
        mutable.value = WebAccountState()
        scope.cancel()
    }
}
