package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.ui.adaptive.ProvideWindowClass
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.theme.CineVerseTheme
import kotlinx.coroutines.launch
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MobileProfileScreenTest {
    @Test fun unknownIdentityDoesNotRenderGuestOrAccountActions() = runComposeUiTest {
        setContent { ProfileFixture(guest = false) }
        onNodeWithText("Checking account…").assertExists()
        onNodeWithText("Guest").assertDoesNotExist()
        onNodeWithText("Sign in").assertDoesNotExist()
        onNodeWithText("Sign out").assertDoesNotExist()
    }

    @Test fun verifiedGuestHasRealSignInCallbackAndNoInventedStatistics() = runComposeUiTest {
        var calls = 0
        setContent { ProfileFixture(onSignIn = { calls++ }) }
        onNodeWithText("Guest").assertExists()
        onNodeWithText("Not signed in · library stays on this device").assertExists()
        onNodeWithText("Sign in").performClick()
        runOnIdle { assertEquals(1, calls) }
        onNodeWithText("0").assertDoesNotExist()
    }

    @Test fun signedInHeaderUsesActualNameAndForwardsFullRenderedSession() = runComposeUiTest {
        val session = AuthSession(AccountObjectId("profile-account"), "Actual TMDB name", 23)
        val callbacks = mutableListOf<AuthSession>()
        setContent { ProfileFixture(guest = false, configure = { it.auth.emissions.tryEmit(session) }, onSignOut = callbacks::add) }
        onNodeWithText("Actual TMDB name").assertExists()
        onNodeWithText("TMDB account connected").assertExists()
        onNodeWithText("Sign out").performClick()
        runOnIdle { assertSame(session, callbacks.single()) }
        onNodeWithText("Sign in").assertDoesNotExist()
    }

    @Test fun actualLanguageAndRegionSheetsExposeSelectedReadableExactOptions() = runComposeUiTest {
        lateinit var f: MobileProfileComponentFixture
        setContent { ProfileFixture(onReady = { _, fixture -> f = fixture }) }
        val list = onNodeWithTag("mobile-profile")
        list.performScrollToNode(hasText("Content language"))
        onNodeWithText("Content language").performClick()
        onNodeWithTag("profile-option-en-US").assertIsSelected()
        onNodeWithText("Hindi · hi-IN").assertExists()
        onNodeWithText("Tamil · ta-IN").assertExists()
        onNodeWithText("Malayalam · ml-IN").assertExists()
        onNodeWithTag("profile-option-hi-IN").performClick()
        onNodeWithTag("profile-picker").assertDoesNotExist()
        runOnIdle { assertEquals("hi-IN", f.store.values.value["language"]) }
        list.performScrollToNode(hasText("Region"))
        onNodeWithText("Region").performClick()
        onNodeWithTag("profile-option-IN").assertIsSelected()
        onNodeWithTag("profile-option-GB").performClick()
        runOnIdle { assertEquals("GB", f.store.values.value["region"]) }
        list.performScrollToNode(hasText("Region"))
        onNodeWithText("Region").performClick()
        onNodeWithTag("profile-option-GB").assertIsSelected()
        onNodeWithText("Cancel").performClick()
        onNodeWithTag("profile-picker").assertDoesNotExist()
    }

    @Test fun sharedToggleAndStrengthControlsExposeStateAndPersistRealValues() = runComposeUiTest {
        lateinit var f: MobileProfileComponentFixture
        setContent { ProfileFixture(onReady = { _, fixture -> f = fixture }) }
        val list = onNodeWithTag("mobile-profile")
        list.performScrollToNode(hasText("Sounds"))
        onNodeWithText("Sounds").assertIsOff().performClick().assertIsOn()
        runOnIdle { assertEquals(true, f.store.values.value["sounds"]) }
        list.performScrollToNode(hasText("Haptic strength"))
        onNodeWithText("Haptic strength").performClick()
        runOnIdle { assertEquals("Strong", f.store.values.value["strength"]) }
        list.performScrollToNode(hasText("Include adult titles"))
        onNodeWithText("Include adult titles").assertIsOff().performClick().assertIsOn()
        runOnIdle { assertEquals(true, f.store.values.value["include_adult"]) }
        onNodeWithText("Autoplay trailers").assertDoesNotExist()
        onNodeWithText("Haptic indicator").assertDoesNotExist()
    }

    @Test fun failedMutationOffersReadRecoveryWithoutSuccessOrAutomaticReplay() = runComposeUiTest {
        lateinit var f: MobileProfileComponentFixture
        setContent { ProfileFixture(configure = { it.store.failWrites = true }, onReady = { _, fixture -> f = fixture }) }
        onNodeWithTag("mobile-profile").performScrollToNode(hasText("Sounds"))
        onNodeWithText("Sounds").performClick()
        onNodeWithTag("mobile-profile").performScrollToNode(hasTestTag("profile-write-error"))
        onNodeWithTag("profile-write-error").assertExists()
        val edits = f.store.edits
        runOnIdle { f.store.failWrites = false }
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("profile-write-error"))).performClick()
        onNodeWithTag("profile-write-error").assertDoesNotExist()
        onNodeWithTag("mobile-profile").performScrollToNode(hasText("Sounds"))
        onNodeWithText("Sounds").assertIsOff()
        runOnIdle { assertEquals(edits, f.store.edits) }
    }

    @Test fun failedReadHidesPreferencesAndRealRetryReadsAgain() = runComposeUiTest {
        lateinit var f: MobileProfileComponentFixture
        setContent { ProfileFixture(configure = { it.store.fail = true }, onReady = { _, fixture -> f = fixture }) }
        onNodeWithTag("profile-read-error").assertExists()
        onNodeWithText("Ambient glow").assertDoesNotExist()
        runOnIdle { f.store.fail = false }
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("profile-read-error"))).performClick()
        onNodeWithTag("profile-read-error").assertDoesNotExist()
        onNodeWithTag("mobile-profile").performScrollToNode(hasText("Ambient glow"))
        onNodeWithText("Ambient glow").assertIsOn()
    }

    @Test fun identityFailureIsNotGuestAndOffersRealReadRecovery() = runComposeUiTest {
        lateinit var f: MobileProfileComponentFixture
        setContent { ProfileFixture(configure = { it.auth.fail = true }, onReady = { _, fixture -> f = fixture }) }
        onNodeWithText("Account verification unavailable. Account actions are disabled.").assertExists()
        onNodeWithText("Sign in").assertDoesNotExist()
        runOnIdle { f.auth.fail = false }
        onNodeWithText("Retry account check").performClick()
        onNodeWithText("Guest").assertExists()
    }

    @Test fun loadingIdentityCannotOverwriteSavedScrollAndActualTopologyRestoresIt() = runComposeUiTest {
        val saved = SavedStateHandle(mapOf("profile.index" to 4, "profile.offset" to 37))
        lateinit var f: MobileProfileComponentFixture
        setContent { ProfileFixture(saved, guest = false, onReady = { _, fixture -> f = fixture }) }
        runOnIdle {
            assertEquals(4, saved.get<Int>("profile.index")); assertEquals(37, saved.get<Int>("profile.offset"))
            f.scope.launch { f.auth.emissions.emit(null) }
        }
        waitForIdle()
        onNodeWithTag("mobile-profile").performScrollToIndex(0)
        waitUntil { saved.get<Int>("profile.index") == 0 }
    }

    @Test fun automaticClampAndDisposalNeverReplaceOutOfRangeSavedTarget() = runComposeUiTest {
        val saved = SavedStateHandle(mapOf("profile.index" to 500, "profile.offset" to 37))
        var shown by mutableStateOf(true)
        setContent { ProfileFixture(saved, showScreen = shown) }
        waitForIdle()
        runOnIdle {
            assertEquals(500, saved.get<Int>("profile.index")); assertEquals(37, saved.get<Int>("profile.offset"))
            shown = false
        }
        onNodeWithTag("mobile-profile").assertDoesNotExist()
        runOnIdle { assertEquals(500, saved.get<Int>("profile.index")); assertEquals(37, saved.get<Int>("profile.offset")) }
    }

    @Test fun retainedEntryRestoresLatestUserScrollAfterRemount() = runComposeUiTest {
        val saved = SavedStateHandle(); var shown by mutableStateOf(true)
        setContent { ProfileFixture(saved, showScreen = shown) }
        onNodeWithTag("mobile-profile").performScrollToIndex(4)
        waitUntil { saved.get<Int>("profile.index") == 4 }
        val deep = onNodeWithTag("mobile-profile").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        runOnIdle { shown = false }
        onNodeWithTag("mobile-profile").assertDoesNotExist()
        runOnIdle { shown = true }
        waitForIdle()
        assertEquals(deep, onNodeWithTag("mobile-profile").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
    }

    @Test fun savedPickerRestoresAndDismissalRemovesOnlyTheValidPicker() = runComposeUiTest {
        val saved = SavedStateHandle(mapOf("profile.picker" to "Region"))
        setContent { ProfileFixture(saved) }
        onNodeWithTag("profile-picker").assertExists()
        onNodeWithTag("profile-option-IN").assertIsSelected()
        onNodeWithText("Cancel").performClick()
        runOnIdle { assertFalse(saved.contains("profile.picker")) }
    }

    @Test fun malformedSavedPickerIsExplainedWithoutResetOrPreferenceControls() = runComposeUiTest {
        val saved = SavedStateHandle(mapOf("profile.picker" to "autoplay"))
        setContent { ProfileFixture(saved) }
        onNodeWithText("Invalid saved profile state. Leave this entry to return.").assertExists()
        onNodeWithTag("profile-picker").assertDoesNotExist()
        onNodeWithText("Sign in").assertDoesNotExist()
        onNodeWithText("Ambient glow").assertDoesNotExist()
        runOnIdle { assertEquals("autoplay", saved.get<String>("profile.picker")) }
    }

    @Composable
    private fun ProfileFixture(saved: SavedStateHandle = SavedStateHandle(), guest: Boolean = true,
        configure: (MobileProfileComponentFixture) -> Unit = {},
        onReady: (MobileProfileViewModel, MobileProfileComponentFixture) -> Unit = { _, _ -> },
        onSignIn: () -> Unit = {}, onSignOut: (AuthSession) -> Unit = {}, showScreen: Boolean = true) {
        val scope = rememberCoroutineScope()
        val f = remember { MobileProfileComponentFixture(scope).also(configure) }
        val model = remember { f.model(saved) }
        LaunchedEffect(f) { if (guest) f.auth.emissions.emit(null) }
        DisposableEffect(model) { onDispose { model.close(); f.graph.close() } }
        DisposableEffect(model, showScreen) { model.setActive(showScreen); onDispose { model.setActive(false) } }
        SideEffect { onReady(model, f) }
        CineVerseTheme(ShellStyle.Mobile, AppearanceSettings(haptics = false, reduceMotion = true)) {
            ProvideWindowClass {
                Box(Modifier.requiredSize(360.dp, 640.dp)) {
                    if (showScreen) MobileProfileScreen(model, onSignIn, onSignOut)
                }
            }
        }
    }
}
