package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.data.web.WebApprovalAttempt
import com.techgv.cineverse.data.web.WebProxyException
import com.techgv.cineverse.data.web.WebWriteOutcome
import com.techgv.cineverse.di.WebRuntime
import com.techgv.cineverse.di.WebSessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Synchronous user-gesture callback, fed only runtime-validated descriptors. Unit does NOT prove a tab opened. */
fun interface WebApprovalOpener {
    fun open(approval: WebApprovalAttempt)
}

enum class WebApprovalPhase { Closed, Ready, Starting, Waiting, Checking, Cancelling, Error, Uncertain }
enum class WebApprovalIssue { StartUnsent, StartUnknown, Check, CancelUnknown, Open }

/** No descriptor, URL, attempt id, token or exception is exposed to rendering/saving. */
data class WebApprovalState(
    val phase: WebApprovalPhase,
    val session: WebSessionState,
    val issue: WebApprovalIssue? = null,
    val openRequested: Boolean = false,
    val canStart: Boolean = false,
    val canOpen: Boolean = false,
    val canCheck: Boolean = false,
)

/**
 * One owner per browser APP lifetime, above routes; route changes must not close/recreate it.
 * Root injects the existing runtime and opener, hosts WebApprovalDialog, forwards return hints to
 * approvalReturned(), and closes this owner only at app teardown. No listeners, polling or persistence.
 * All methods run on the app's UI dispatcher. Disposal revokes feedback, never cancels server attempts.
 */
class WebApprovalOwner(
    private val runtime: WebRuntime,
    private val opener: WebApprovalOpener,
    appScope: CoroutineScope,
) : AutoCloseable {
    private val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job]))
    private var disposed = false
    private var visible = false
    private var busy = false
    private var revision = 0L
    private var cancelRequested = false
    private var mayHaveAttempt = false
    private var approval: WebApprovalAttempt? = null
    private val mutable = MutableStateFlow(WebApprovalState(WebApprovalPhase.Closed, runtime.session.value))
    val state: StateFlow<WebApprovalState> = mutable.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.session.collect { session ->
                if (!active()) return@collect
                if (session is WebSessionState.Ready && session.session != null) hide()
                else {
                    mutable.value = mutable.value.copy(session = session)
                    publish()
                }
            }
        }
    }

    fun show() {
        if (!active() || busy || visible || signedIn()) return
        visible = true
        publish(WebApprovalPhase.Ready, null)
    }

    fun startApproval() {
        if (!active() || !visible || busy || mayHaveAttempt || !guest()) return
        busy = true
        val ticket = ++revision
        publish(WebApprovalPhase.Starting, null)
        scope.launch {
            try {
                if (!current(ticket)) return@launch
                if (!guest()) {
                    publish(WebApprovalPhase.Ready, null)
                    return@launch
                }
                mayHaveAttempt = true // Even a lost POST response can have created a browser attempt.
                val result = runtime.startApproval() // Repository bootstraps CSRF and validates the exact approve URL.
                if (current(ticket)) {
                    approval = result
                    publish(WebApprovalPhase.Waiting, null)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: TmdbException) {
                if (current(ticket)) {
                    val unsent = (failure as? WebProxyException)?.writeOutcome == WebWriteOutcome.DefinitelyUnsent
                    mayHaveAttempt = !unsent
                    publish(if (unsent) WebApprovalPhase.Error else WebApprovalPhase.Uncertain,
                        if (unsent) WebApprovalIssue.StartUnsent else WebApprovalIssue.StartUnknown)
                }
            } finally {
                finishOperation()
            }
        }
    }

    /** Invoke directly from Continue's click handler, never after a suspend or a focus/visibility hint. */
    fun continueToTmdb() {
        if (!active() || !visible || busy || !guest()) return
        val descriptor = approval ?: return
        val ticket = revision
        mutable.value = mutable.value.copy(openRequested = true)
        if (!current(ticket) || busy || cancelRequested || !guest() || approval !== descriptor) return
        try {
            opener.open(descriptor)
            if (current(ticket)) publish(WebApprovalPhase.Waiting, null)
        } catch (_: Exception) {
            // Browser exceptions may contain the full URL. Retain only a fixed, non-sensitive issue.
            if (current(ticket)) publish(WebApprovalPhase.Error, WebApprovalIssue.Open)
        }
    }

    /** Root's existing focus/visibility/callback hook may call this. Query flags are not identity proof. */
    fun approvalReturned() {
        if (!active() || !visible || busy || signedIn()) return
        busy = true
        val ticket = ++revision
        val previousIssue = mutable.value.issue
        publish(WebApprovalPhase.Checking, previousIssue)
        scope.launch {
            try {
                if (!current(ticket)) return@launch
                runtime.approvalReturned() // Read only. Never starts, exchanges or cancels an attempt.
                if (current(ticket)) {
                    val unresolved = previousIssue == WebApprovalIssue.StartUnknown || previousIssue == WebApprovalIssue.CancelUnknown
                    publish(if (unresolved) WebApprovalPhase.Uncertain else if (approval != null) WebApprovalPhase.Waiting else WebApprovalPhase.Ready,
                        if (unresolved) previousIssue else null)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: TmdbException) {
                if (current(ticket)) publish(if (mayHaveAttempt && approval == null) WebApprovalPhase.Uncertain else WebApprovalPhase.Error,
                    if (previousIssue == WebApprovalIssue.StartUnknown || previousIssue == WebApprovalIssue.CancelUnknown) previousIssue else WebApprovalIssue.Check)
            } finally {
                finishOperation()
            }
        }
    }

    /** Close/scrim/Escape/Not now explicitly cancel ALL browser attempts, not a sign-out. */
    fun dismiss() {
        if (!active() || !visible || cancelRequested || mutable.value.phase == WebApprovalPhase.Cancelling) return
        cancelRequested = true
        mayHaveAttempt = true // Other tabs may own attempts even without a local descriptor.
        revision++ // A late start/read cannot replace the user's cancellation intent.
        approval = null
        publish(WebApprovalPhase.Cancelling, null)
        if (!busy) cancel()
    }

    private fun cancel() {
        if (!active() || !cancelRequested) return
        cancelRequested = false
        busy = true
        val ticket = ++revision
        publish(WebApprovalPhase.Cancelling, null)
        scope.launch {
            try {
                if (!active()) return@launch
                runtime.cancelApproval()
                if (current(ticket)) hide() // POST + repository read both completed; no fabricated guest state.
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: TmdbException) {
                if (current(ticket)) publish(WebApprovalPhase.Uncertain, WebApprovalIssue.CancelUnknown)
            } finally {
                finishOperation()
            }
        }
    }

    private fun finishOperation() {
        busy = false
        if (cancelRequested && active()) cancel() else publish()
    }

    private fun active(): Boolean = !disposed && scope.isActive
    private fun guest(): Boolean = (runtime.session.value as? WebSessionState.Ready)?.let { it.session == null } == true
    private fun signedIn(): Boolean = (runtime.session.value as? WebSessionState.Ready)?.session != null
    private fun current(ticket: Long): Boolean {
        if (!active()) return false
        if (signedIn()) hide()
        return visible && revision == ticket
    }

    private fun hide() {
        revision++
        visible = false
        approval = null
        mayHaveAttempt = false
        mutable.value = WebApprovalState(WebApprovalPhase.Closed, runtime.session.value)
    }

    private fun publish(phase: WebApprovalPhase = mutable.value.phase, issue: WebApprovalIssue? = mutable.value.issue) {
        if (!active() || !visible) return
        mutable.value = mutable.value.copy(
            phase = phase, session = runtime.session.value, issue = issue,
            canStart = !busy && !mayHaveAttempt && guest(),
            canOpen = !busy && approval != null && guest(),
            canCheck = !busy,
        )
    }

    override fun close() {
        if (disposed) return
        disposed = true
        hide()
        scope.cancel()
    }
}
