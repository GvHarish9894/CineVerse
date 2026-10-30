package com.techgv.cineverse.ui.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.presentation.HomeRow
import com.techgv.cineverse.presentation.SectionState
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MobileHomeStorageRecoveryTest {
    @Test fun loadedHomeSettingsBannerRetriesHolderReadsEvenWhenRecoveredSettingsAreEqual() = runComposeUiTest {
        lateinit var model: MobileHomeViewModel
        lateinit var fixture: MobileFixture
        setContent { MobileTestRoot(onReady = { owner, f -> model = owner; fixture = f }) }
        onNodeWithContentDescription("Details for week-en-US 1").assertIsDisplayed()
        val retainedModel = model
        val before = model.home.uiState.value
        runOnIdle {
            fixture.store.fail = true
            fixture.store.values.value = mapOf("unrelated" to "storage-down")
            model.home.loadMore(HomeRow.Trending)
        }
        onNodeWithTag("mobile-home").performScrollToNode(hasTestTag("home-settings-recovery"))
        onNodeWithText("Saved preferences unavailable; keeping the last loaded values.").assertIsDisplayed()
        runOnIdle {
            assertTrue(model.storageFailed.value)
            assertEquals(before.hero, model.home.uiState.value.hero)
            fixture.store.fail = false
        }
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("home-settings-recovery"))).performClick()
        waitForIdle()
        runOnIdle {
            assertSame(retainedModel, model)
            assertFalse(fixture.graph.settingsFailed.value)
            assertFalse(model.storageFailed.value)
            val page = (model.home.uiState.value.rows.getValue(HomeRow.Trending) as SectionState.Content).value
            assertEquals(2, page.page)
            assertNull(page.moreError)
            assertEquals(before.hero, model.home.uiState.value.hero)
            assertEquals(before.rows - HomeRow.Trending, model.home.uiState.value.rows - HomeRow.Trending)
            assertTrue(fixture.api.calls.none { it.startsWith("videos/") })
            assertTrue(fixture.guest.rows.value.isEmpty())
        }
        onNodeWithTag("home-settings-recovery").assertDoesNotExist()
    }

    @Test fun genreFailureHasVisibleExplicitRecoveryWithoutReplacingLoadedHome() = runComposeUiTest {
        lateinit var model: MobileHomeViewModel
        lateinit var fixture: MobileFixture
        val gate = CompletableDeferred<Unit>()
        setContent {
            MobileTestRoot(configureFixture = {
                it.api.genreGate = gate
                it.api.failGenresStorage = true
            }, onReady = { owner, f -> model = owner; fixture = f })
        }
        onNodeWithContentDescription("Details for week-en-US 1").assertIsDisplayed()
        val retainedModel = model
        val before = model.home.uiState.value
        runOnIdle { gate.complete(Unit) }
        onNodeWithTag("mobile-home").performScrollToNode(hasTestTag("home-genres-recovery"))
        onNodeWithText("Genre names unavailable; keeping loaded content.").assertIsDisplayed()
        runOnIdle { fixture.api.failGenresStorage = false }
        onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("home-genres-recovery"))).performClick()
        waitForIdle()
        runOnIdle {
            assertSame(retainedModel, model)
            assertNull(model.home.uiState.value.genresError)
            assertFalse(model.storageFailed.value)
            assertEquals(setOf(28, 18), model.home.uiState.value.genres.keys)
            assertEquals(before.rows, model.home.uiState.value.rows)
            assertEquals(before.hero, model.home.uiState.value.hero)
            assertTrue(fixture.api.calls.none { it.startsWith("videos/") })
        }
        onNodeWithTag("home-genres-recovery").assertDoesNotExist()
    }
}
