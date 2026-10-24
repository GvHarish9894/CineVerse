package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.storage.StorageException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.settings.AccountDefaults
import com.techgv.cineverse.data.settings.HapticStrength
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.AccountDto
import com.techgv.cineverse.data.tmdb.TmdbException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.io.IOException

/** §7.9 Content language values with their display names (prototype `LANGS`). */
val LANGUAGE_NAMES = mapOf(
    "en-US" to "English",
    "hi-IN" to "हिन्दी · Hindi",
    "ta-IN" to "தமிழ் · Tamil",
    "ml-IN" to "മലയാളം · Malayalam",
    "es-ES" to "Español",
)

val REGION_NAMES = mapOf("IN" to "India", "US" to "United States", "GB" to "United Kingdom")

/** ←→ cycling: the value [delta] steps from [current] in [values], wrapping at both ends. */
fun <T> List<T>.cycle(current: T, delta: Int): T = this[(indexOf(current) + delta).mod(size)]

/**
 * The account's TMDB defaults mapped onto supported values (`en` → `en-US`; unsupported language / region
 * keep the current one), or null when they would change nothing.
 */
fun accountDefaultsOffer(session: AuthSession, account: AccountDto, current: Settings): AccountDefaults? {
    val language = Settings.LANGUAGES.firstOrNull { it.substringBefore('-') == account.language } ?: current.language
    val region = account.region?.takeIf { it in Settings.REGIONS } ?: current.region
    if (language == current.language && region == current.region && account.includeAdult == current.includeAdult) return null
    return AccountDefaults(session.accountObjectId.value, language, region, account.includeAdult)
}

/** §7.9 on/off rows. */
enum class Toggle { Haptics, Sounds, Glow, Motion, Adult }

/** §7.9 ←→ rows (OK = +1). */
enum class Choice { Strength, Language, Region }

/** [accountDefaults] is the pending offer of the signed-in account only. */
data class SettingsUiState(
    val settings: Settings,
    val accountName: String?,
    val accountDefaults: AccountDefaults?,
)

/**
 * §7.9 Settings: the toggles and ←→ choices (language / region changes invalidate the TMDB cache at the composition
 * root, §3.4), the signed-in account's defaults offer and §4.2 sign-out. [scope]: see [HomeStateHolder].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStateHolder(
    private val settings: SettingsRepository,
    private val auth: AuthRepository,
    scope: CoroutineScope? = null,
    private val onStorageFailure: (StorageException) -> Unit = {},
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val reads = MutableStateFlow(0L)

    /** Retry a failed read; mutations are never replayed automatically. */
    fun retryStorage() { reads.update { it + 1 } }

    /** Null until the first settings read: a shell restores focus only once real values show. */
    val state: StateFlow<SettingsUiState?> =
        reads.flatMapLatest { combine(settings.settings, auth.session, settings.pendingAccountDefaults) { s, session, pending ->
            SettingsUiState(
                settings = s,
                accountName = session?.displayName,
                accountDefaults = session?.let { pending[it.accountObjectId.value] },
            )
        }.catch { failure ->
            if (failure is StorageException) onStorageFailure(failure) else throw failure
        } }.stateIn(this.scope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggle(toggle: Toggle) = edit {
        when (toggle) {
            Toggle.Haptics -> it.copy(haptics = !it.haptics)
            Toggle.Sounds -> it.copy(sounds = !it.sounds)
            Toggle.Glow -> it.copy(glow = !it.glow)
            Toggle.Motion -> it.copy(motion = !it.motion)
            Toggle.Adult -> it.copy(includeAdult = !it.includeAdult)
        }
    }

    fun cycle(choice: Choice, delta: Int) = edit {
        when (choice) {
            Choice.Strength -> it.copy(strength = HapticStrength.entries.cycle(it.strength, delta))
            Choice.Language -> it.copy(language = Settings.LANGUAGES.cycle(it.language, delta))
            Choice.Region -> it.copy(region = Settings.REGIONS.cycle(it.region, delta))
        }
    }

    /** Resolves [offer] only while its account is still the signed-in one (a stale screen's click does nothing). */
    fun resolveAccountDefaults(offer: AccountDefaults, apply: Boolean) {
        scope.launch {
            try {
                if (auth.session.first()?.accountObjectId?.value == offer.accountObjectId) {
                    settings.resolveAccountDefaults(offer.accountObjectId, apply)
                }
            } catch (failure: StorageException) { onStorageFailure(failure) }
        }
    }

    private val signOutEvents = Channel<Boolean>(Channel.BUFFERED)

    /** true once the local sign-out is done (remote revocation may still run), false if it failed. */
    val signOutResults: Flow<Boolean> = signOutEvents.receiveAsFlow()

    private var signingOut = false

    /** §4.2: local sign-out first (non-cancellable in the repository), remote revocation best-effort. */
    fun signOut() {
        if (signingOut) return
        signingOut = true
        scope.launch {
            var signedOut = false
            try {
                auth.signOut {
                    signedOut = true
                    signOutEvents.trySend(true)
                }
            } catch (e: IOException) {
                if (!signedOut) signOutEvents.trySend(false)
            } catch (e: TmdbException) {
                if (!signedOut) signOutEvents.trySend(false)
            } finally {
                signingOut = false
            }
        }
    }

    private fun edit(transform: (Settings) -> Settings) {
        scope.launch {
            try { settings.update(transform) }
            catch (failure: StorageException) { onStorageFailure(failure) }
        }
    }
}
