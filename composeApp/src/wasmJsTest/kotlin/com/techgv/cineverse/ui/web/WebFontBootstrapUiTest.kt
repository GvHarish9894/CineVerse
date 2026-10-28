@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui.web

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.font.FontListFontFamily
import com.techgv.cineverse.data.settings.SettingsRepository
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.ui.theme.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.MissingResourceException
import kotlin.test.*

class WebFontBootstrapUiTest {
    @Test fun permanentFontFailureDoesNotBlockSessionSettingsCatalogOrNavigation() =
        failedFontsDoNotBlockApp { "not a font".encodeToByteArray() }

    @Test fun missingFontDoesNotBlockSessionSettingsCatalogOrNavigation() =
        failedFontsDoNotBlockApp { throw MissingResourceException(it) }

    @Test fun rejectedFontReadDoesNotBlockSessionSettingsCatalogOrNavigation() =
        failedFontsDoNotBlockApp { path -> readWebBundledFontBytes(path) { webFontRequestFixture("network") } }

    @Test fun native404DoesNotBlockSessionSettingsCatalogOrNavigation() =
        failedFontsDoNotBlockApp { path -> readWebBundledFontBytes(path) { webFontRequestFixture("404") } }

    @Test fun rejectedBodyReadDoesNotBlockSessionSettingsCatalogOrNavigation() =
        failedFontsDoNotBlockApp { path -> readWebBundledFontBytes(path) { webFontRequestFixture("body") } }

    private fun failedFontsDoNotBlockApp(read: suspend (String) -> ByteArray) = runComposeUiTest {
        val f = WebRuntimeUiTest.Fixture()
        val repository = f.app.koin.get<SettingsRepository>()
        val history = FakeHistory()
        var loads = 0
        var fontLoadFinished = false
        var selected: CvFonts? = null
        try {
            f.failSession = false; f.failSettings = false
            f.runtime.start()
            setContent {
                ProvideWebBundledFonts(load = {
                    loads++
                    val fonts = loadWebBundledFonts(read)
                    fontLoadFinished = true
                    fonts
                }) {
                    val fonts = LocalCvFontOverride.current
                    SideEffect { selected = fonts }
                    WebApp(f.runtime, f.catalog, f.generation, f.settings, f.recent, history, repository)
                }
            }
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    while (!fontLoadFinished || f.catalogReads < 7) {
                        delay(10)
                        mainClock.advanceTimeByFrame()
                        waitForIdle()
                    }
                }
            }
            assertIs<WebSessionState.Ready>(f.runtime.session.value)
            assertNotNull(f.runtime.settingsHolder.state.value)
            onNodeWithText("Discover your next favourite").assertExists()
            onNodeWithText("Checking session…").assertDoesNotExist()
            onNodeWithText("Reading saved settings…").assertDoesNotExist()
            // Same external popstate path as root tests; render the real repository-backed Settings route.
            runOnIdle { history.push("/settings", ""); history.back(); history.forward() }
            onNodeWithTag("web-settings").assertExists()
            onNodeWithTag("settings-look").assertExists()
            onNodeWithText("Preferences are unknown. Waiting for a successful read.").assertDoesNotExist()
            assertSame(WebFallbackFonts, selected)
            onNodeWithText("My Library").performClick()
            assertEquals("/library?tab=watchlist", history.url)
            assertEquals(1, loads)
            assertEquals(0, f.edits)
        } finally { f.close() }
    }

    @Test fun lateSuccessfulFontsDoNotRecreateTheMountedGraphOrHistoryEntry() = runComposeUiTest {
        val mapping = WebSearchFonts()
        val f = WebRuntimeUiTest.Fixture()
        val history = FakeHistory()
        var navigationMounts = 0
        var navigationDisposals = 0
        val trackedHistory = object : WebHistory by history {
            override fun listen(onPop: () -> Unit): () -> Unit {
                navigationMounts++
                val close = history.listen(onPop)
                return { navigationDisposals++; close() }
            }
        }
        val gate = CompletableDeferred<Unit>()
        val update = mutableStateOf(0)
        var loaded: CvFonts? = null
        var loads = 0
        var mounts = 0
        var unmounts = 0
        try {
            f.failSession = false; f.failSettings = false
            f.runtime.start()
            setContent {
                update.value
                ProvideWebBundledFonts(load = { loads++; gate.await(); loadWebBundledFonts() }) {
                    val fonts = LocalCvFontOverride.current
                    SideEffect { loaded = fonts }
                    DisposableEffect(Unit) { mounts++; onDispose { unmounts++ } }
                    WebApp(f.runtime, f.catalog, f.generation, f.settings, f.recent, trackedHistory)
                }
            }
            waitUntil(timeoutMillis = 10_000) { f.catalogReads >= 7 }
            assertSame(WebFallbackFonts, loaded)
            onNodeWithText("My Library").performClick()
            onNodeWithText("Saved in this browser · sign in to sync with TMDB").assertExists()
            waitForIdle()
            val saved = history.saved
            val sessions = f.sessionReads
            val subscriptions = f.settingsSubscriptions
            val reads = f.catalogReads
            val generation = f.generation.current
            runOnIdle { update.value++; gate.complete(Unit) }
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    while (loaded?.body !is FontListFontFamily) {
                        delay(10)
                        mainClock.advanceTimeByFrame()
                        waitForIdle()
                    }
                }
            }
            waitForIdle()
            assertEquals(1, loads)
            assertEquals(1, mounts)
            assertEquals(0, unmounts)
            assertEquals(1, navigationMounts)
            assertEquals(0, navigationDisposals)
            assertEquals(sessions, f.sessionReads)
            assertEquals(subscriptions, f.settingsSubscriptions)
            assertEquals(reads, f.catalogReads)
            assertEquals(generation, f.generation.current)
            assertEquals(saved, history.saved)
            assertEquals(1, history.pushes)
            assertEquals("/library?tab=watchlist", history.url)
            assertEquals(0, f.edits)
            runOnIdle { history.back() }
            onNodeWithText("Discover your next favourite").assertExists()
        } finally { f.close(); mapping.close() }
    }

    @Test fun disposingFontProviderCancelsOnlyItsPendingLoad() = runComposeUiTest {
        val visible = mutableStateOf(true)
        var canceled = false
        var mounted = false
        setContent {
            if (visible.value) ProvideWebBundledFonts(load = {
                try { awaitCancellation() } finally { canceled = true }
            }) { SideEffect { mounted = true } }
        }
        waitUntil { mounted }
        runOnIdle { visible.value = false }
        waitUntil { canceled }
    }
}
