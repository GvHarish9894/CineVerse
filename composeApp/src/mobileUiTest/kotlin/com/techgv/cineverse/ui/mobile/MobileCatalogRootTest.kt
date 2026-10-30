package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.*
import com.techgv.cineverse.ui.adaptive.ProvideWindowClass
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.shell.MobileShell
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.theme.CineVerseTheme
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MobileCatalogRootTest {
    @Test fun activationRequiresResumedCurrentEntryAndTabRemovalRetainsOwner() = runComposeUiTest {
        lateinit var state: CatalogBindingState
        setContent { CatalogBindingFixture { state = it } }
        waitUntil { state.first.lifecycle.currentState == Lifecycle.State.RESUMED && state.second.lifecycle.currentState == Lifecycle.State.RESUMED }
        runOnIdle {
            state.binding.attach(state.first, state.firstOwner)
            state.binding.currentChanged(state.first)
            assertFalse(state.firstOwner.activeState)
            state.binding.setResumed(true)
            assertTrue(state.firstOwner.activeState)

            state.binding.setResumed(false)
            assertFalse(state.firstOwner.activeState)
            state.binding.setResumed(true)
            assertTrue(state.firstOwner.activeState)

            state.binding.detach(state.first, state.firstOwner)
            assertFalse(state.firstOwner.activeState)
            assertFalse(state.firstOwner.closed, "Temporary tab removal must not close a retained entry owner")
            state.binding.attach(state.first, state.firstOwner)
            assertTrue(state.firstOwner.activeState)

            state.binding.currentChanged(state.second)
            assertFalse(state.firstOwner.activeState, "Destination changes revoke the previous owner synchronously")
            state.binding.attach(state.second, state.secondOwner)
            assertTrue(state.secondOwner.activeState)
            state.binding.leave()
            assertFalse(state.secondOwner.activeState)
        }
    }

    @Test fun realPersonEntryUsesExactIdContentAndPoppedStoreClosesItsOwner() = runComposeUiTest {
        lateinit var state: CatalogRouteState
        setContent { CatalogRouteFixture { state = it } }
        onNodeWithText("Open person 431").performClick()
        onNodeWithTag("mobile-person").assertExists()
        onNodeWithText("Root actor 431").assertIsDisplayed()
        onNodeWithText("Root biography 431").assertExists()
        runOnIdle {
            assertEquals(listOf("person/431/en-US"), state.fixture.api.catalogCalls.filter { it.startsWith("person/") })
            assertEquals(431, state.people.single().id)
        }
        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("Open person 431").assertIsDisplayed()
        runOnIdle {
            val calls = state.fixture.api.catalogCalls.size
            state.people.single().setActive(true)
            assertEquals(calls, state.fixture.api.catalogCalls.size, "A popped entry store must leave its cleared owner closed")
        }
    }

    @Test fun seasonSelectionCreatesNewExactOwnerAndBackRetainsThePreviousOwner() = runComposeUiTest {
        lateinit var state: CatalogRouteState
        setContent { CatalogRouteFixture { state = it } }
        onNodeWithText("Open season 3").performClick()
        onNodeWithTag("mobile-season").assertExists()
        onNodeWithText("Actual show 42 en-US").assertIsDisplayed()
        onNodeWithText("Actual episode 3-1").assertExists()
        lateinit var first: MobileSeasonViewModel
        runOnIdle {
            first = state.seasons.single()
            assertEquals(42, first.seriesId)
            assertEquals(3, first.seasonNumber)
            assertNotNull(first.state.value.context)
        }
        onNodeWithTag("season-option-7").performClick()
        // The season chips repeat "Season N"; the heading is the owner's own season.
        onNodeWithTag("season-title").assertTextEquals("Season 7").assertIsDisplayed()
        onNodeWithText("Actual episode 7-1").assertExists()
        lateinit var second: MobileSeasonViewModel
        runOnIdle {
            assertNull(first.state.value.context, "Opening another season must revoke the old route before navigation")
            assertEquals(2, state.seasons.size)
            second = state.seasons.last()
            assertNotSame(first, second)
            assertEquals(42, second.seriesId)
            assertEquals(7, second.seasonNumber)
            assertNotNull(second.state.value.context)
            assertEquals(listOf("season/42/3/en-US", "season/42/7/en-US"),
                state.fixture.api.catalogCalls.filter { it.startsWith("season/") })
        }
        onNodeWithContentDescription("Back").performClick()
        onNodeWithTag("season-title").assertTextEquals("Season 3").assertIsDisplayed()
        runOnIdle {
            assertNull(second.state.value.context)
            assertSame(first, state.seasons.first(), "Back must restore the retained entry owner, not retarget the newer one")
            assertNotNull(first.state.value.context)
        }
    }
}

private class TrackingCatalogOwner : MobileCatalogEntryModel {
    var activeState = false
    var closed = false
    override fun setActive(value: Boolean) { activeState = value }
}

private class CatalogBindingState(
    val binding: MobileCatalogRootBinding,
    val first: NavBackStackEntry,
    val second: NavBackStackEntry,
) {
    val firstOwner = TrackingCatalogOwner()
    val secondOwner = TrackingCatalogOwner()
}

@Composable
private fun CatalogBindingFixture(onReady: (CatalogBindingState) -> Unit) {
    val first = rememberNavController()
    val second = rememberNavController()
    val firstEntry by first.currentBackStackEntryAsState()
    val secondEntry by second.currentBackStackEntryAsState()
    Column {
        NavHost(first, startDestination = MobilePersonRoute(431)) { composable<MobilePersonRoute> {} }
        NavHost(second, startDestination = MobileSeasonRoute(42, 3)) { composable<MobileSeasonRoute> {} }
    }
    val a = firstEntry
    val b = secondEntry
    if (a != null && b != null) {
        val state = remember(a, b) { CatalogBindingState(MobileCatalogRootBinding(), a, b) }
        SideEffect { onReady(state) }
        DisposableEffect(state) { onDispose { state.binding.close() } }
    }
}

private class CatalogRouteState(val fixture: MobileDetailRootFixture) {
    val people = mutableListOf<MobilePersonViewModel>()
    val seasons = mutableListOf<MobileSeasonViewModel>()
}

@Composable
private fun CatalogRouteFixture(onReady: (CatalogRouteState) -> Unit) {
    val scope = rememberCoroutineScope()
    val fixture = remember { MobileDetailRootFixture(scope) }
    val state = remember { CatalogRouteState(fixture) }
    val controller = rememberNavController()
    val binding = rememberMobileCatalogRootBinding(controller)
    DisposableEffect(binding) {
        binding.setResumed(true)
        onDispose { binding.setResumed(false); fixture.graph.close() }
    }
    val navigator = MobileNavigator { destination -> binding.leave(); controller.open(destination) }
    fun back() { binding.leave(); controller.popBackStack() }
    LaunchedEffect(Unit) { fixture.auth.emissions.emit(null) }
    SideEffect { onReady(state) }
    CineVerseTheme(ShellStyle.Mobile, AppearanceSettings(haptics = false, reduceMotion = true)) {
        ProvideWindowClass {
            MobileShell {
                NavHost(controller, startDestination = MobileTabRoot) {
                    composable<MobileTabRoot> {
                        Column {
                            PillButton("Open person 431", { navigator.open(MobileDestination.Person(431)) })
                            PillButton("Open season 3", { navigator.open(MobileDestination.Season(42, 3)) })
                        }
                    }
                    composable<MobilePersonRoute> { entry ->
                        MobilePersonRouteEntry(fixture.graph, entry, binding, navigator, ::back) {
                            if (it !in state.people) state.people += it
                        }
                    }
                    composable<MobileSeasonRoute> { entry ->
                        MobileSeasonRouteEntry(fixture.graph, entry, binding, navigator, ::back) {
                            if (it !in state.seasons) state.seasons += it
                        }
                    }
                }
            }
        }
    }
}
