package com.techgv.cineverse.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.techgv.cineverse.AppContainer
import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.PollingApprovalFlow
import com.techgv.cineverse.data.auth.RequestCode
import com.techgv.cineverse.data.auth.SignInOutcome
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.presentation.LoginAction
import com.techgv.cineverse.presentation.LoginButton
import com.techgv.cineverse.presentation.LoginMessage
import com.techgv.cineverse.presentation.LoginScreenState
import com.techgv.cineverse.presentation.LoginStateHolder
import com.techgv.cineverse.presentation.SectionErrorKind
import com.techgv.cineverse.presentation.toLoginScreenState
import com.techgv.cineverse.ui.components.message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** §4.1 Login copy: [code] drives the QR; [waiting] shows the spinner; [banner] is the app-credential problem. */
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

        fun ready(code: RequestCode) = LoginScreenState.ready(code).toUiState()
    }
}

/** TV copy of the shared Login state. */
fun LoginScreenState.toUiState() = LoginUiState(
    code = code,
    message = when (message) {
        LoginMessage.RequestingCode -> "Getting a sign-in code…"
        LoginMessage.SigningIn -> "Signing you in…"
        LoginMessage.WaitingForApproval -> "Waiting for TMDB to confirm your approval…"
        LoginMessage.StillWaiting -> "Still waiting — press OK to check again"
        LoginMessage.CodeExpired -> "Code expired — press OK for a new one"
        LoginMessage.NotApproved -> "TMDB didn't approve this sign-in"
        LoginMessage.CouldNotFinish -> "Couldn't finish signing in — press OK for a new code"
        LoginMessage.Offline -> SectionErrorKind.Offline.message
        LoginMessage.CodeError -> "Couldn't get a sign-in code."
        null -> null
    },
    primaryLabel = when (button) {
        LoginButton.Approved -> LoginUiState.APPROVED_LABEL
        LoginButton.Checking -> "Checking…"
        LoginButton.CheckAgain -> LoginUiState.CHECK_AGAIN_LABEL
        LoginButton.NewCode -> LoginUiState.NEW_CODE_LABEL
        LoginButton.TryAgain -> "Try again"
    },
    primaryAction = action,
    waiting = waiting,
    banner = SectionErrorKind.AppCredential.message.takeIf { appCredentialProblem },
)

/** §3.3 / §4.1 copy for every outcome that keeps the user on Login; null for SignedIn / Superseded. */
fun SignInOutcome.toLoginState(code: RequestCode): LoginUiState? = toLoginScreenState(code)?.toUiState()

/**
 * TV adapter over [LoginStateHolder] with QR + polling approval ([PollingApprovalFlow]). The screen calls
 * [stopPolling] when it stops, loses window focus or is disposed.
 */
class LoginViewModel(
    auth: AuthRepository,
    settings: SettingsRepository,
    /** Outlives this screen: post-commit work must not be cancelled by [stopPolling] or dispose. */
    appScope: CoroutineScope,
) : ViewModel() {
    private val holder = LoginStateHolder(auth, PollingApprovalFlow(auth), settings, appScope, viewModelScope)

    val state: StateFlow<LoginUiState> =
        holder.state.map { it.toUiState() }.stateIn(viewModelScope, SharingStarted.Eagerly, holder.state.value.toUiState())

    /** Display name of the account that just signed in (credentials are already committed). */
    val signedIn: Flow<String> = holder.signedIn.map { it.displayName }

    fun onPrimary() = holder.onPrimary()

    /** BACK, guest, dispose, background: polling stops; the code stays for the next "I've approved it". */
    fun stopPolling() = holder.stopPolling()

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { LoginViewModel(container.authRepository, container.settingsRepository, container.appScope) }
        }
    }
}
