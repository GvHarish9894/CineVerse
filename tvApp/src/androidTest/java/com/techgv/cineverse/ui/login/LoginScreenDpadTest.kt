package com.techgv.cineverse.ui.login

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.techgv.cineverse.data.auth.AndroidKeystoreKeys
import com.techgv.cineverse.data.auth.NativeAuthRepository
import com.techgv.cineverse.data.auth.CredentialCipher
import com.techgv.cineverse.data.auth.DataStoreCredentialStore
import com.techgv.cineverse.data.settings.DataStoreSettingsStore
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.data.tmdb.AccessTokenBody
import com.techgv.cineverse.data.tmdb.AccessTokenDto
import com.techgv.cineverse.data.tmdb.RequestTokenBody
import com.techgv.cineverse.data.tmdb.RequestTokenDto
import com.techgv.cineverse.data.tmdb.TmdbApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy

/** §4.1 / §1.2 with real D-pad keys: OK starts the wait (focus stays), guest leaves and stops polling. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LoginScreenDpadTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var exchangeCalls = 0
    private val unused = Proxy.newProxyInstance(TmdbApi::class.java.classLoader, arrayOf(TmdbApi::class.java)) { _, method, _ ->
        throw UnsupportedOperationException(method.name)
    } as TmdbApi

    /** Never approves: the exchange suspends until the poll is cancelled. */
    private val api = object : TmdbApi by unused {
        override suspend fun createRequestToken(body: RequestTokenBody) = RequestTokenDto("request-token")
        override suspend fun createAccessToken(body: AccessTokenBody): AccessTokenDto {
            exchangeCalls++
            CompletableDeferred<Unit>().await()
            error("unreachable")
        }
    }

    private fun store(name: String) =
        PreferenceDataStoreFactory.create { File(context.cacheDir, "$name-${System.nanoTime()}.preferences_pb") }

    @Test
    fun okStartsWaiting_rightThenOkContinuesAsGuest() {
        val auth = NativeAuthRepository(api, DataStoreCredentialStore(store("login-test-credentials"), CredentialCipher(AndroidKeystoreKeys("login_test"))), onAccountChanged = {})
        val vm = LoginViewModel(auth, SettingsRepository(DataStoreSettingsStore(store("login-test-settings"))), CoroutineScope(Dispatchers.Default))
        var guest = 0
        compose.setContent { LoginScreen(message = null, onContinueAsGuest = { guest++ }, onSignedIn = {}, vm = vm) }
        compose.waitUntil(5_000) { compose.onAllNodes(isFocused() and hasText("I've approved it")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("I've approved it").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Checking…")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Checking…").assertIsFocused()
        compose.onNodeWithText("Waiting for TMDB to confirm your approval…").assertExists()
        assertEquals(1, exchangeCalls)

        compose.onNodeWithText("Checking…").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Continue as guest").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(1, guest)
        assertEquals(false, vm.state.value.polling)
    }
}
