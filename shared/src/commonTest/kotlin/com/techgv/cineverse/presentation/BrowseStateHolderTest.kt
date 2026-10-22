package com.techgv.cineverse.presentation

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** §7.3 (moved from tvApp BrowseViewModelTest). */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseStateHolderTest {
    private val api = FakeCatalogApi()
    private val app = GenerationCounter()
    private val settings = MutableStateFlow(Settings())
    private val handle = SavedStateHandle()

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun holder(kind: BrowseKind = BrowseKind.Movies) =
        BrowseStateHolder(catalog(api, app, settings), app, settings, SavedStateHandleStateSaver(handle), kind)

    private fun BrowseStateHolder.titles() =
        (uiState.value.grid as SectionState.Content).value.items.map { (it as Hero.OfTitle).title.title }

    @Test fun firstTabLoadsAndTheNextPageAppends() = runTest {
        api.totalPages = 2
        val holder = holder()
        advanceUntilIdle()
        assertEquals(listOf("popular@en-US-1-0", "popular@en-US-1-1", "popular@en-US-1-2"), holder.titles())
        holder.loadMore()
        advanceUntilIdle()
        assertEquals(6, holder.titles().size)
        assertEquals("popular@en-US-2-0", holder.titles()[3])
    }

    @Test fun tabChangeDropsThePreviousTabsLateResponseAndIsSaved() = runTest {
        val popular = api.gate("movie/popular/en-US/1")
        val holder = holder()
        advanceUntilIdle()
        holder.selectTab(2) // Upcoming
        advanceUntilIdle()
        popular.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, holder.uiState.value.tab)
        assertEquals("upcoming@en-US-1-0", holder.titles().first())
        assertEquals(2, holder().uiState.value.tab) // restored from the same saved state
    }

    @Test fun settingsChangeReloadsTheGridAndUnknownTabsAreIgnored() = runTest {
        val holder = holder(BrowseKind.People)
        advanceUntilIdle()
        holder.selectTab(5)
        settings.value = Settings(language = "es-ES")
        advanceUntilIdle()
        assertEquals(0, holder.uiState.value.tab)
        assertEquals(listOf("people/en-US/1", "people/es-ES/1"), api.calls)
    }
}
