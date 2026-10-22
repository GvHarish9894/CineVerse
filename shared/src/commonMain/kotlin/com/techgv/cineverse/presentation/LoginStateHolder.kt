package com.techgv.cineverse.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.auth.ApprovalAttempt
import com.techgv.cineverse.data.auth.ApprovalFlow
import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.auth.RequestCode
import com.techgv.cineverse.data.auth.SignInOutcome
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** What the primary button does. */
enum class LoginAction { None, Poll, NewCode }

/** Copy keys of the Login status line (§3.3 / §4.1); each shell owns the text. */
enum class LoginMessage { RequestingCode, SigningIn, WaitingForApproval, StillWaiting, CodeExpired, NotApproved, CouldNotFinish, Offline, CodeError }

/** Copy keys of the primary button. */
enum class LoginButton { Approved, Checking, CheckAgain, NewCode, TryAgain }

/**
 * §4.1 Login: [code] drives the approval page / QR; [waiting] shows the spinner; [appCredentialProblem] the
 * app-configuration banner (7 / 10).
 */
data class LoginScreenState(
    val code: RequestCode? = null,
    val message: LoginMessage? = null,
    val button: LoginButton = LoginButton.Approved,
    val action: LoginAction = LoginAction.None,
    val waiting: Boolean = false,
    val appCredentialProblem: Boolean = false,
) {
    val polling: Boolean get() = waiting && code != null

    companion object {
        val requestingCode = LoginScreenState(message = LoginMessage.RequestingCode, waiting = true)

        /** Terminal: the sign-in is committed, the request code is consumed and never offered again. */
        val signedIn = LoginScreenState(message = LoginMessage.SigningIn)
        fun ready(code: RequestCode) = LoginScreenState(code, action = LoginAction.Poll)
        fun polling(code: RequestCode) = LoginScreenState(code, LoginMessage.WaitingForApproval, LoginButton.Checking, waiting = true)

        fun codeError(kind: SectionErrorKind) = LoginScreenState(
            message = if (kind == SectionErrorKind.Offline) LoginMessage.Offline else LoginMessage.CodeError,
            button = LoginButton.TryAgain,
            action = LoginAction.NewCode,
            appCredentialProblem = kind == SectionErrorKind.AppCredential,
        )
    }
}

/** §3.3 / §4.1: the state for every outcome that keeps the user on Login; null for SignedIn / Superseded. */
fun SignInOutcome.toLoginScreenState(code: RequestCode): LoginScreenState? = when (this) {
    is SignInOutcome.SignedIn, SignInOutcome.Superseded -> null
    SignInOutcome.AlreadyPolling -> LoginScreenState.ready(code)
    SignInOutcome.TimedOut -> LoginScreenState(code, LoginMessage.StillWaiting, LoginButton.CheckAgain, LoginAction.Poll)
    SignInOutcome.CodeExpired -> LoginScreenState(null, LoginMessage.CodeExpired, LoginButton.NewCode, LoginAction.NewCode)
    is SignInOutcome.Rejected -> LoginScreenState(null, LoginMessage.NotApproved, LoginButton.NewCode, LoginAction.NewCode)
    is SignInOutcome.Failed -> LoginScreenState(null, LoginMessage.CouldNotFinish, LoginButton.NewCode, LoginAction.NewCode)
    SignInOutcome.AppCredentialError ->
        LoginScreenState(code, button = LoginButton.CheckAgain, action = LoginAction.Poll, appCredentialProblem = true)
}

/**
 * One request code per screen, approved through [approval]; [ApprovalFlow.awaitApproval] runs only on the primary
 * action and only while the shell keeps the screen in front (it calls [stopPolling] on BACK / background / dispose).
 * Results commit only for the current generation (§1.1); a newer press or [stopPolling] bumps it — except the
 * sign-in commit, which is the terminal success however the wait ends. [scope]: see [HomeStateHolder].
 */
class LoginStateHolder(
    private val auth: AuthRepository,
    private val approval: ApprovalFlow,
    private val settings: SettingsRepository,
    /** Outlives this screen: post-commit work must not be cancelled by [stopPolling] or dispose. */
    private val appScope: CoroutineScope,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(LoginScreenState.requestingCode)
    val state: StateFlow<LoginScreenState> = _state.asStateFlow()

    private val signedInEvents = Channel<AuthSession>(Channel.BUFFERED)

    /** The session that just signed in (already committed); once per screen. */
    val signedIn: Flow<AuthSession> = signedInEvents.receiveAsFlow()

    private val generation = GenerationCounter()
    private var work: Job? = null
    private var attempt: ApprovalAttempt? = null

    init {
        requestCode()
        // A committed session (even when [stopPolling] cancelled the wait right after the commit, or it exists on
        // resume) leaves Login exactly once and never re-offers the consumed code.
        this.scope.launch {
            val session = auth.session.filterNotNull().first()
            generation.bump()
            _state.value = LoginScreenState.signedIn
            signedInEvents.send(session)
        }
    }

    fun onPrimary() = when (_state.value.action) {
        LoginAction.Poll -> poll()
        LoginAction.NewCode -> requestCode()
        LoginAction.None -> Unit
    }

    /** BACK, guest, dispose, background: the wait stops; the code stays for the next primary action. */
    fun stopPolling() {
        val code = _state.value.code ?: return
        if (!_state.value.polling) return
        generation.bump()
        work?.cancel()
        _state.value = LoginScreenState.ready(code)
    }

    private fun requestCode() = start(LoginScreenState.requestingCode) { generation ->
        try {
            val attempt = ApprovalAttempt.of(auth.createRequestCode())
            approval.start(attempt)
            commit(generation, LoginScreenState.ready(attempt.code)) { this.attempt = attempt }
        } catch (e: TmdbException) {
            commit(generation, LoginScreenState.codeError(e.toSectionErrorKind()))
        }
    }

    private fun poll() {
        val attempt = attempt?.takeIf { it.code == _state.value.code } ?: return
        start(LoginScreenState.polling(attempt.code)) { generation ->
            val outcome = approval.awaitApproval(attempt)
            when {
                // Committed: the session observer (init) leaves Login; the optional offer outlives this job.
                // shortcut: a cancel landing inside the commit itself loses the offer (sign-in still completes).
                outcome is SignInOutcome.SignedIn -> appScope.launch {
                    accountDefaultsOffer(outcome.session, outcome.account, settings.current())
                        ?.let { settings.offerAccountDefaults(it) }
                }
                !this.generation.isCurrent(generation) -> Unit
                // A sign-out / expiry raced the commit (or a newer attempt replaced this one): nothing was saved; start over quietly.
                outcome == SignInOutcome.Superseded -> requestCode()
                else -> outcome.toLoginScreenState(attempt.code)?.let { _state.value = it }
            }
        }
    }

    /** Replaces the running step: the previous one is cancelled (and its poll lock released) first. */
    private fun start(initial: LoginScreenState, block: suspend (generation: Long) -> Unit) {
        val previous = work
        val generation = generation.bump()
        _state.value = initial
        work = scope.launch {
            previous?.cancelAndJoin()
            block(generation)
        }
    }

    private fun commit(generation: Long, next: LoginScreenState, also: () -> Unit = {}) {
        if (!this.generation.isCurrent(generation)) return
        also()
        _state.value = next
    }
}
