package com.techgv.cineverse.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.techgv.cineverse.AppContainer
import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.RequestCode
import com.techgv.cineverse.data.auth.SignInOutcome
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.TmdbException
import com.techgv.cineverse.ui.components.SectionErrorKind
import com.techgv.cineverse.ui.components.toSectionErrorKind
import com.techgv.cineverse.ui.settings.accountDefaultsOffer
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** What OK on the primary button does. */
enum class LoginAction { None, Poll, NewCode }

/** §4.1 Login: [code] drives the QR; [waiting] shows the spinner; [banner] is the app-credential problem. */
data class LoginUiState(
    val code: RequestCode? = null,
    val message: String? = null,
    val primaryLabel: String = APPROVED_LABEL,
    val primaryAction: LoginAction = LoginAction.None,
    val waiting: Boolean = false,
    val banner: String? = null,
) {
    val polling: Boolean get() = waiting && code != null

    companion object {
        const val APPROVED_LABEL = "I've approved it"
        const val NEW_CODE_LABEL = "Get a new code"
        const val CHECK_AGAIN_LABEL = "Check again"

        val requestingCode = LoginUiState(message = "Getting a sign-in code…", waiting = true)
        fun ready(code: RequestCode) = LoginUiState(code, primaryAction = LoginAction.Poll)
        fun polling(code: RequestCode) =
            LoginUiState(code, "Waiting for TMDB to confirm your approval…", "Checking…", waiting = true)

        fun codeError(kind: SectionErrorKind) = LoginUiState(
            message = if (kind == SectionErrorKind.Offline) kind.message else "Couldn't get a sign-in code.",
            primaryLabel = "Try again",
            primaryAction = LoginAction.NewCode,
            banner = kind.message.takeIf { kind == SectionErrorKind.AppCredential },
        )
    }
}

/** §3.3 / §4.1 copy for every outcome that keeps the user on Login; null for SignedIn / Superseded. */
fun SignInOutcome.toLoginState(code: RequestCode): LoginUiState? = when (this) {
    is SignInOutcome.SignedIn, SignInOutcome.Superseded -> null
    SignInOutcome.AlreadyPolling -> LoginUiState.ready(code)
    SignInOutcome.TimedOut ->
        LoginUiState(code, "Still waiting — press OK to check again", LoginUiState.CHECK_AGAIN_LABEL, LoginAction.Poll)
    SignInOutcome.CodeExpired ->
        LoginUiState(null, "Code expired — press OK for a new one", LoginUiState.NEW_CODE_LABEL, LoginAction.NewCode)
    is SignInOutcome.Rejected ->
        LoginUiState(null, "TMDB didn't approve this sign-in", LoginUiState.NEW_CODE_LABEL, LoginAction.NewCode)
    is SignInOutcome.Failed ->
        LoginUiState(null, "Couldn't finish signing in — press OK for a new code", LoginUiState.NEW_CODE_LABEL, LoginAction.NewCode)
    SignInOutcome.AppCredentialError -> LoginUiState(
        code,
        primaryLabel = LoginUiState.CHECK_AGAIN_LABEL,
        primaryAction = LoginAction.Poll,
        banner = SectionErrorKind.AppCredential.message,
    )
}

/**
 * One request code per screen; polling only on "I've approved it" and only while the screen is started,
 * window-focused and composed (the screen calls [stopPolling]). Results commit only for the current
 * generation (§1.1); a newer press or [stopPolling] bumps it.
 */
class LoginViewModel(private val auth: AuthRepository, private val settings: SettingsRepository) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState.requestingCode)
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val signedInEvents = Channel<String>(Channel.BUFFERED)

    /** Display name of the account that just signed in (credentials are already committed). */
    val signedIn: Flow<String> = signedInEvents.receiveAsFlow()

    private val generation = GenerationCounter()
    private var work: Job? = null

    init {
        requestCode()
    }

    fun onPrimary() = when (_state.value.primaryAction) {
        LoginAction.Poll -> poll()
        LoginAction.NewCode -> requestCode()
        LoginAction.None -> Unit
    }

    /** BACK, guest, dispose, background: polling stops; the code stays for the next "I've approved it". */
    fun stopPolling() {
        val code = _state.value.code ?: return
        if (!_state.value.polling) return
        generation.bump()
        work?.cancel()
        _state.value = LoginUiState.ready(code)
    }

    private fun requestCode() = start(LoginUiState.requestingCode) { generation ->
        val next = try {
            LoginUiState.ready(auth.createRequestCode())
        } catch (e: TmdbException) {
            LoginUiState.codeError(e.toSectionErrorKind())
        }
        commit(generation, next)
    }

    private fun poll() {
        val code = _state.value.code ?: return
        start(LoginUiState.polling(code)) { generation ->
            val outcome = auth.awaitApproval(code)
            if (!this.generation.isCurrent(generation)) return@start
            when (outcome) {
                is SignInOutcome.SignedIn -> {
                    accountDefaultsOffer(outcome.credentials, outcome.account, settings.current())
                        ?.let { settings.offerAccountDefaults(it) }
                    signedInEvents.send(outcome.credentials.displayName)
                }
                // A sign-out / expiry raced the commit: nothing was saved and the code is consumed; start over quietly.
                SignInOutcome.Superseded -> requestCode()
                else -> outcome.toLoginState(code)?.let { _state.value = it }
            }
        }
    }

    /** Replaces the running step: the previous one is cancelled (and its poll lock released) first. */
    private fun start(initial: LoginUiState, block: suspend (generation: Long) -> Unit) {
        val previous = work
        val generation = generation.bump()
        _state.value = initial
        work = viewModelScope.launch {
            previous?.cancelAndJoin()
            block(generation)
        }
    }

    private fun commit(generation: Long, next: LoginUiState) {
        if (this.generation.isCurrent(generation)) _state.value = next
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { LoginViewModel(container.authRepository, container.settingsRepository) }
        }
    }
}
