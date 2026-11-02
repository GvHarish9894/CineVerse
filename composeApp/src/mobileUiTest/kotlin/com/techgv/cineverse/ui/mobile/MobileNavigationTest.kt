package com.techgv.cineverse.ui.mobile

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.techgv.cineverse.presentation.HomeRow
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

    @Test fun publicSaveableRegistryRecreationRestoresRememberSaveableScalar() = runComposeUiTest {
        val restoration = PortableStateRestoration()
        lateinit var changeValue: () -> Unit
        var instances = 0
        setContent {
            restoration.Content {
                var value by rememberSaveable { mutableIntStateOf(1) }
                SideEffect { changeValue = { value = 7 } }
                DisposableEffect(Unit) {
                    instances++
                    onDispose {}
                }
                Text("Saved scalar $value")
            }
        }
        onNodeWithText("Saved scalar 1").assertIsDisplayed()
        runOnIdle {
            assertEquals(1, instances)
            changeValue()
        }
        onNodeWithText("Saved scalar 7").assertIsDisplayed()
        runOnIdle { restoration.emulateSaveAndRestore() }
        onNodeWithText("Saved scalar 7").assertIsDisplayed()
        runOnIdle { assertEquals(2, instances) }
    }

    @Test fun selectedTabAndEachStackSurviveSaveRestore() = runComposeUiTest {
        val restoration = PortableStateRestoration()
        setContent { restoration.Content { MobileTestRoot() } }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        onNodeWithText("Profile", useUnmergedTree = true).performClick()
        runOnIdle { restoration.emulateSaveAndRestore() }
        onNodeWithTag("mobile-profile").assertIsDisplayed()
        onNodeWithText("Not signed in · library stays on this device").assertIsDisplayed()
        onNodeWithText("Home", useUnmergedTree = true).performClick()
        onNodeWithText("Detail title 101").assertIsDisplayed()
        // Re-selecting the active tab pops its stack.
        onNodeWithText("Home", useUnmergedTree = true).performClick()
        onNodeWithContentDescription("Details for week-en-US 1").assertIsDisplayed()
    }

    @Test fun homeScrollAndTrendRestoreAcrossTabsAndSaveRestore() = runComposeUiTest {
        val restoration = PortableStateRestoration()
        val savedHome = SavedStateHandle()
        setContent {
            // A real save/restore never clears the ViewModelStore; clearing it makes NavHost drop the Home entry's saved scroll.
            val owner = rememberMobileTestOwner()
            restoration.Content { MobileTestRoot(savedHome, owner = owner) }
        }
        onNodeWithTag("mobile-home").performScrollToNode(hasText("Today"))
        onNodeWithText("Today").performClick()
        onNodeWithTag("mobile-home").performScrollToNode(hasText("Now playing in theatres"))
        onNodeWithText("Search", useUnmergedTree = true).performClick()
        onNodeWithText("Home", useUnmergedTree = true).performClick()
        onNodeWithText("Now playing in theatres").assertIsDisplayed()
        // The trend chip lives in a LazyColumn item that is composed-out once scrolled past.
        onNodeWithTag("mobile-home").performScrollToNode(hasText("Today"))
        onNodeWithText("Today").assertIsSelected()
        onNodeWithTag("mobile-home").performScrollToNode(hasText("Now playing in theatres"))
        runOnIdle { restoration.emulateSaveAndRestore() }
        onNodeWithText("Now playing in theatres").assertIsDisplayed()
        onNodeWithTag("mobile-home").performScrollToNode(hasText("Today"))
        onNodeWithText("Today").assertIsSelected()
        onNodeWithText("Home", useUnmergedTree = true).performClick()
        onNodeWithContentDescription("Details for day-en-US 1").assertIsDisplayed()
    }

    @Test fun seeAllUsesExploreHandoffAndReturnsWithoutLosingHome() = runComposeUiTest {
        setContent { MobileTestRoot() }
        // Scroll the row to the top; scrolling only until "Trending" is visible leaves "See all" under the floating tab bar.
        onNodeWithTag("mobile-home").performScrollToKey(HomeRow.Trending.key)
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
    owner: MobileTestOwner = rememberMobileTestOwner(),
    haptics: Haptics = Haptics.None,
    configureFixture: (MobileFixture) -> Unit = {},
    onReady: (MobileHomeViewModel, MobileFixture) -> Unit = { _, _ -> },
    reduceMotion: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val fixture = remember { MobileFixture(scope).also(configureFixture) }
    val model = remember { MobileHomeViewModel(fixture.graph, savedHome, scope) }
    SideEffect { onReady(model, fixture) }
    LaunchedEffect(Unit) { fixture.auth.emissions.emit(null) }
    DisposableEffect(Unit) { onDispose { fixture.graph.close() } }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner) {
        CineVerseTheme(ShellStyle.Mobile, AppearanceSettings(haptics = false, reduceMotion = reduceMotion)) {
            CompositionLocalProvider(LocalHaptics provides haptics) {
                ProvideWindowClass { MobileShell { MobileRoot(fixture.graph, model, RecordingMobileExternalActions()) } }
            }
        }
    }
}

@Composable
internal fun rememberMobileTestOwner(): MobileTestOwner {
    val owner = remember { MobileTestOwner() }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return owner
}

internal class MobileTestOwner : LifecycleOwner, ViewModelStoreOwner {
    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    override val viewModelStore = ViewModelStore()
}
