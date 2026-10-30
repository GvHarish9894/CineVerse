package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.presentation.HomeStateHolder
import com.techgv.cineverse.ui.adaptive.ProvideWindowClass
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.haptics.Haptics
import com.techgv.cineverse.ui.haptics.LocalHaptics
import com.techgv.cineverse.ui.shell.MobileShell
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.theme.CineVerseTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalTestApi::class)
class MobileNavigationTest {
    @Test fun tabStacksKeepPushedRoutesAndBackReturnsToHome() = runComposeUiTest {
        lateinit var home: HomeStateHolder
        lateinit var api: MobileCatalogApi
        setContent { MobileTestRoot(onReady = { model, fixture -> home = model.home; api = fixture.api }) }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        val retainedHome = home
        onNodeWithText("Detail title 101").assertIsDisplayed()
        onNodeWithText("Explore", useUnmergedTree = true).performClick()
        onNodeWithText("Movies · Popular").assertIsDisplayed()
        onNodeWithText("Home", useUnmergedTree = true).onParent().performSemanticsAction(SemanticsActions.OnClick) { selectHome ->
            selectHome()
            // A queued trailer press must be rejected even before navigation's next lifecycle frame.
            home.playTrailer((home.uiState.value.hero as Hero.OfTitle).title)
        }
        onNodeWithText("Detail title 101").assertIsDisplayed()
        runOnIdle {
            assertSame(retainedHome, home)
            home.playTrailer((home.uiState.value.hero as Hero.OfTitle).title)
        }
        waitForIdle()
        runOnIdle {
            assertEquals(emptyList(), api.calls.filter { it.startsWith("videos/") }, "Hidden retained Home must reject trailer lookup")
            assertNull(home.uiState.value.trailerLoading)
        }
        onNodeWithContentDescription("Back").performClick()
        onNodeWithContentDescription("Details for week-en-US 1").assertIsDisplayed()
        runOnIdle { home.playTrailer((home.uiState.value.hero as Hero.OfTitle).title) }
        waitForIdle()
        runOnIdle { assertEquals(listOf("videos/101/en-US"), api.calls.filter { it.startsWith("videos/") }) }
    }

    @Test fun selectedTabAndEachStackSurviveSaveRestore() = runComposeUiTest {
        val restoration = StateRestorationTester(this)
        restoration.setContent { MobileTestRoot() }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        onNodeWithText("Profile", useUnmergedTree = true).performClick()
        restoration.emulateSaveAndRestore()
        onNodeWithTag("mobile-profile").assertIsDisplayed()
        onNodeWithText("Not signed in · library stays on this device").assertIsDisplayed()
        onNodeWithText("Home", useUnmergedTree = true).performClick()
        onNodeWithText("Detail title 101").assertIsDisplayed()
        // Re-selecting the active tab pops its stack.
        onNodeWithText("Home", useUnmergedTree = true).performClick()
        onNodeWithContentDescription("Details for week-en-US 1").assertIsDisplayed()
    }

    @Test fun homeScrollAndTrendRestoreAcrossTabsAndSaveRestore() = runComposeUiTest {
        val restoration = StateRestorationTester(this)
        val savedHome = SavedStateHandle()
        restoration.setContent { MobileTestRoot(savedHome) }
        onNodeWithTag("mobile-home").performScrollToNode(hasText("Today"))
        onNodeWithText("Today").performClick()
        onNodeWithTag("mobile-home").performScrollToNode(hasText("Now playing in theatres"))
        onNodeWithText("Search", useUnmergedTree = true).performClick()
        onNodeWithText("Home", useUnmergedTree = true).performClick()
        onNodeWithText("Now playing in theatres").assertIsDisplayed()
        restoration.emulateSaveAndRestore()
        onNodeWithText("Now playing in theatres").assertIsDisplayed()
        onNodeWithText("Home", useUnmergedTree = true).performClick()
        onNodeWithContentDescription("Details for day-en-US 1").assertIsDisplayed()
    }

    @Test fun seeAllUsesExploreHandoffAndReturnsWithoutLosingHome() = runComposeUiTest {
        setContent { MobileTestRoot() }
        onNodeWithTag("mobile-home").performScrollToNode(hasText("Trending"))
        onAllNodesWithText("See all")[0].performClick()
        onNodeWithText("Trending · This week · Movies, TV and People").assertIsDisplayed()
        onNodeWithText("Back").performClick()
        onNodeWithText("Movies · Popular").assertIsDisplayed()
        onNodeWithText("Home", useUnmergedTree = true).performClick()
        onNodeWithText("Trending").assertIsDisplayed()
    }
}

@Composable
internal fun MobileTestRoot(
    savedHome: SavedStateHandle = remember { SavedStateHandle() },
    haptics: Haptics = Haptics.None,
    configureFixture: (MobileFixture) -> Unit = {},
    onReady: (MobileHomeViewModel, MobileFixture) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    val fixture = remember { MobileFixture(scope).also(configureFixture) }
    val owner = remember { MobileTestOwner() }
    val model = remember { MobileHomeViewModel(fixture.graph, savedHome, scope) }
    SideEffect { onReady(model, fixture) }
    LaunchedEffect(Unit) { fixture.auth.emissions.emit(null) }
    DisposableEffect(Unit) { onDispose { fixture.graph.close(); owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner) {
        CineVerseTheme(ShellStyle.Mobile, AppearanceSettings(haptics = false, reduceMotion = true)) {
            CompositionLocalProvider(LocalHaptics provides haptics) {
                ProvideWindowClass { MobileShell { MobileRoot(fixture.graph, model, RecordingMobileExternalActions()) } }
            }
        }
    }
}

private class MobileTestOwner : LifecycleOwner, ViewModelStoreOwner {
    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    override val viewModelStore = ViewModelStore()
}
