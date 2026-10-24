package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.techgv.cineverse.presentation.HomeStateHolder
import com.techgv.cineverse.presentation.SavedStateHandleStateSaver
import com.techgv.cineverse.ui.adaptive.ProvideWindowClass
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.shell.MobileShell
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.theme.CineVerseTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class MobileNavigationTest {
    @Test fun tabStacksKeepPushedRoutesAndBackReturnsToHome() = runComposeUiTest {
        setContent { MobileTestRoot() }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        onNodeWithText("Internal placeholder · movie 101").assertIsDisplayed()
        onNodeWithText("Explore", useUnmergedTree = true).performClick()
        onNodeWithText("Internal placeholder · This tab is not implemented in M1.").assertIsDisplayed()
        onNodeWithText("Home", useUnmergedTree = true).performClick()
        onNodeWithText("Internal placeholder · movie 101").assertIsDisplayed()
        onNodeWithText("Back").performClick()
        onNodeWithContentDescription("Details for week-en-US 1").assertIsDisplayed()
    }

    @Test fun selectedTabAndEachStackSurviveSaveRestore() = runComposeUiTest {
        val restoration = StateRestorationTester(this)
        restoration.setContent { MobileTestRoot() }
        onNodeWithContentDescription("Details for week-en-US 1").performClick()
        onNodeWithText("Profile", useUnmergedTree = true).performClick()
        restoration.emulateSaveAndRestore()
        onNodeWithText("Internal placeholder · This tab is not implemented in M1.").assertIsDisplayed()
        onNodeWithText("Home", useUnmergedTree = true).performClick()
        onNodeWithText("Internal placeholder · movie 101").assertIsDisplayed()
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
        onNodeWithText("Internal placeholder · Trending").assertIsDisplayed()
        onNodeWithText("Back").performClick()
        onNodeWithText("Internal placeholder · This tab is not implemented in M1.").assertIsDisplayed()
        onNodeWithText("Home", useUnmergedTree = true).performClick()
        onNodeWithText("Trending").assertIsDisplayed()
    }
}

@Composable
private fun MobileTestRoot(savedHome: SavedStateHandle = remember { SavedStateHandle() }) {
    val scope = rememberCoroutineScope()
    val fixture = remember { MobileFixture(scope) }
    val owner = remember { MobileTestOwner() }
    val home = remember { HomeStateHolder(fixture.catalog, fixture.generation, fixture.graph.settings.filterNotNull(),
        SavedStateHandleStateSaver(savedHome), scope) }
    LaunchedEffect(Unit) { fixture.auth.emissions.emit(null) }
    DisposableEffect(Unit) { onDispose { fixture.graph.close(); owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner) {
        CineVerseTheme(ShellStyle.Mobile, AppearanceSettings(haptics = false, reduceMotion = true)) {
            ProvideWindowClass { MobileShell { MobileRoot(fixture.graph, home) } }
        }
    }
}

private class MobileTestOwner : LifecycleOwner, ViewModelStoreOwner {
    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    override val viewModelStore = ViewModelStore()
}
