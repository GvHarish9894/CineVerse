package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.settings.HapticStrength
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.di.mobileModule
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.presentation.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MobileApplicationTest {
    @Test fun settingsAreUnknownUntilReadAndFailuresRetainLastGoodWithExplicitRetry() = runTest {
        val f = MobileFixture(backgroundScope)
        assertNull(f.graph.settings.value)
        assertNull(f.graph.identity.value)
        runCurrent()
        assertNotNull(f.graph.settings.value)
        assertNull(f.graph.identity.value) // No auth emission is not guest.
        f.auth.emissions.emit(null)
        runCurrent()
        assertEquals(MobileIdentity(null), f.graph.identity.value)
        val previous = f.graph.settings.value
        f.store.fail = true
        f.store.values.value = mapOf("language" to "hi-IN")
        runCurrent()
        assertTrue(f.graph.settingsFailed.value)
        assertEquals(previous, f.graph.settings.value)
        f.store.fail = false
        f.graph.retrySettings()
        runCurrent()
        assertFalse(f.graph.settingsFailed.value)
        assertEquals("hi-IN", f.graph.settings.value?.language)
        f.graph.close()
    }

    @Test fun contentInvalidatesExactlyOncePerGraphAndAppearanceDoesNotInvalidate() = runTest {
        val f = MobileFixture(backgroundScope)
        runCurrent()
        val graph = f.graph
        graph.retrySettings() // Re-entry/recomposition does not duplicate a live observer.
        graph.retrySettings()
        assertEquals(1, f.store.subscriptions)
        val initial = f.generation.current
        f.catalog.trending(TrendingWindow.Week)
        f.catalog.trending(TrendingWindow.Week)
        assertEquals(1, f.api.calls.size)
        f.settings.update { it.copy(language = "hi-IN") }
        runCurrent()
        assertEquals(initial + 1, f.generation.current)
        f.catalog.trending(TrendingWindow.Week)
        f.settings.update { it.copy(haptics = false, motion = true, glow = false) }
        runCurrent()
        assertEquals(initial + 1, f.generation.current)
        f.catalog.trending(TrendingWindow.Week)
        assertEquals(2, f.api.calls.size)
        // A → B → A is another invalidation, not equal to the first generation.
        f.settings.update { it.copy(language = "en-US") }
        runCurrent()
        assertEquals(initial + 2, f.generation.current)
        graph.close()
        f.settings.update { it.copy(region = "US") }
        runCurrent()
        assertEquals(initial + 2, f.generation.current)
    }

    @Test fun nativeModuleResolvesOneEagerGraphAcrossRepeatedEntrypointLookups() = runTest {
        val f = MobileFixture(backgroundScope)
        f.graph.close()
        val app = koinApplication {
            modules(module {
                single { f.catalog }
                single { f.generation }
                single { f.settings }
                single<com.techgv.cineverse.data.auth.AuthRepository> { f.auth }
                single { f.library }
                single(com.techgv.cineverse.di.AppScope) { backgroundScope }
            }, mobileModule())
        }
        runCurrent()
        val first = app.koin.get<MobileApplication>()
        assertSame(first, app.koin.get<MobileApplication>())
        assertSame(f.catalog, first.catalog)
        assertEquals(1, f.store.subscriptions)
        app.close()
        f.settings.update { it.copy(region = "US") }
        runCurrent()
        assertEquals(0L, f.generation.current)
    }

    @Test fun mobileStrengthMappingPreservesStoredEnumsAndUsesKitDurations() {
        assertEquals(listOf(6, 10, 16), HapticStrength.entries.map { Settings(strength = it).mobileAppearance().hapticStrength.baseMs })
        val appearance = Settings(haptics = false, glow = false, motion = true).mobileAppearance()
        assertFalse(appearance.haptics)
        assertFalse(appearance.ambientGlow)
        assertTrue(appearance.reduceMotion)
        assertEquals(listOf(6L, 12L, 20L), HapticStrength.entries.map { it.millis })
    }

    @Test fun homeUsesGraphGenerationRestoresTrendReloadsSettingsAndDropsInactiveTrailer() = runTest {
        val f = MobileFixture(backgroundScope)
        runCurrent()
        val saved = SavedStateHandle()
        fun home() = HomeStateHolder(f.catalog, f.generation, f.graph.settings.filterNotNull(), SavedStateHandleStateSaver(saved), backgroundScope)
        val home = home()
        runCurrent()
        assertTrue(home.uiState.value.rows.values.all { it is SectionState.Content })
        home.selectTrend(TrendingWindow.Day)
        runCurrent()
        assertEquals(TrendingWindow.Day, home().uiState.value.trend)
        f.settings.update { it.copy(language = "hi-IN") }
        runCurrent()
        assertTrue((home.uiState.value.hero as Hero.OfTitle).title.title.contains("hi-IN"))
        val gate = CompletableDeferred<Unit>()
        f.api.trailerGate = gate
        val events = mutableListOf<TrailerEvent>()
        backgroundScope.launch { home.events.collect { events += it } }
        home.setActive(true)
        home.playTrailer((home.uiState.value.hero as Hero.OfTitle).title)
        runCurrent()
        assertNotNull(home.uiState.value.trailerLoading)
        home.setActive(false)
        gate.complete(Unit)
        runCurrent()
        assertNull(home.uiState.value.trailerLoading)
        assertTrue(events.isEmpty())
        f.graph.close()
    }

    @Test fun graphActionsUseFakeGuestStorageAndDoNotReplayWritesOnReadRetry() = runTest {
        val f = MobileFixture(backgroundScope)
        f.auth.emissions.emit(null)
        runCurrent()
        val home = HomeStateHolder(f.catalog, f.generation, f.graph.settings.filterNotNull(), SavedStateHandleStateSaver(SavedStateHandle()), backgroundScope)
        runCurrent()
        val title = (home.uiState.value.hero as Hero.OfTitle).title
        f.graph.actions.toggleWatchlist(title)
        runCurrent()
        assertEquals(1, f.guest.rows.value.size)
        f.graph.retryLibraryStorage()
        runCurrent()
        assertEquals(1, f.guest.rows.value.size)
        f.graph.actions.toggleWatchlist(title)
        runCurrent()
        assertTrue(f.guest.rows.value.isEmpty())
        f.graph.close()
    }
}
