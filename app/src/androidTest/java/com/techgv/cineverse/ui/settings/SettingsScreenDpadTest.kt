package com.techgv.cineverse.ui.settings

import androidx.compose.ui.input.key.Key
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.techgv.cineverse.appContainer
import com.techgv.cineverse.data.auth.AndroidKeystoreKeys
import com.techgv.cineverse.data.auth.AuthRepository
import com.techgv.cineverse.data.auth.CredentialCipher
import com.techgv.cineverse.data.auth.CredentialStore
import com.techgv.cineverse.data.auth.Credentials
import com.techgv.cineverse.data.settings.AccountDefaults
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.AccountId
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.data.tmdb.SessionId
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.UserAccessToken
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy

/** §7.9 with real D-pad keys: focus lands on the first row, DOWN moves, ←→ cycle a value without moving focus. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsScreenDpadTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = context.appContainer.settingsRepository

    @Before
    fun resetSettings() = runBlocking { settings.update { Settings() } }

    @After
    fun restoreSettings() = runBlocking { settings.update { Settings() } }

    @Test
    fun leftRightCycleHapticStrength_focusStaysOnTheRow() {
        compose.setContent { SettingsScreen(onSignIn = {}, onSignedOut = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(isFocused() and hasContentDescription("Haptic feedback, on")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("Haptic feedback, on").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("Haptic strength, Medium").assertIsFocused()

        compose.onNodeWithContentDescription("Haptic strength, Medium").performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Haptic strength, Strong")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Haptic strength, Strong").assertIsFocused()

        compose.onNodeWithContentDescription("Haptic strength, Strong").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Haptic strength, Medium")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Haptic strength, Medium").assertIsFocused()
    }

    @Test
    fun accountDefaultsRow_dismissWithDpad_focusFallsBackIntoTheList() {
        // A local signed-in account (no network: the API is never called) with a pending offer.
        fun store(name: String) = PreferenceDataStoreFactory.create { File(context.cacheDir, "$name-${System.nanoTime()}.preferences_pb") }
        val api = Proxy.newProxyInstance(TmdbApi::class.java.classLoader, arrayOf(TmdbApi::class.java)) { _, m, _ ->
            throw UnsupportedOperationException(m.name)
        } as TmdbApi
        val credentialStore = CredentialStore(store("settings-test-credentials"), CredentialCipher(AndroidKeystoreKeys("settings_test")))
        val localSettings = SettingsRepository(store("settings-test-settings"))
        runBlocking {
            credentialStore.save(Credentials(UserAccessToken("t"), AccountObjectId("aoid"), SessionId("s"), AccountId(1), "harish", "Harish Gv"))
            localSettings.offerAccountDefaults(AccountDefaults("aoid", "hi-IN", "US", includeAdult = false))
        }
        val vm = SettingsViewModel(SavedStateHandle(), localSettings, AuthRepository(api, credentialStore, onAccountChanged = {}))
        compose.setContent { SettingsScreen(onSignIn = {}, onSignedOut = {}, vm = vm) }

        compose.waitUntil(5_000) { compose.onAllNodes(isFocused() and hasText("Apply")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Use your TMDB account defaults (हिन्दी · Hindi, United States)").assertExists()

        compose.onNodeWithText("Apply").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Dismiss").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Dismiss")).fetchSemanticsNodes().isEmpty() }
        // Focus never gets lost with the vanished buttons: it lands on the first settings row.
        compose.waitUntil(5_000) { compose.onAllNodes(isFocused() and hasContentDescription("Haptic feedback, on")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Content language, English").assertExists()
    }
}
