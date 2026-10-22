package com.techgv.cineverse.ui.browse

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.ui.FakeCatalogApi
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.ui.fakeRepository
import com.techgv.cineverse.ui.nav.BrowseKind
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BrowseViewModelTest {
    private val api = FakeCatalogApi()
    private val app = GenerationCounter()
    private val settings = MutableStateFlow(Settings())
    private val handle = SavedStateHandle()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = BrowseViewModel(fakeRepository(api, app, settings), app, settings, handle, BrowseKind.Movies)

    private fun BrowseViewModel.titles() =
        (uiState.value.grid as SectionState.Content).value.items.map { (it as Hero.OfTitle).title.title }

    @Test fun `first tab loads, next page appends`() = runTest {
        api.totalPages = 2
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(listOf("popular@en-US-1-0", "popular@en-US-1-1", "popular@en-US-1-2"), vm.titles())
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(6, vm.titles().size)
        assertEquals("popular@en-US-2-0", vm.titles()[3])
    }

    @Test fun `tab change drops the previous tab's late response and is saved`() = runTest {
        val popular = api.gate("movie/popular/en-US/1")
        val vm = viewModel()
        advanceUntilIdle()
        vm.selectTab(2) // Upcoming
        advanceUntilIdle()
        popular.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.tab)
        assertEquals("upcoming@en-US-1-0", vm.titles().first())
        assertEquals(2, viewModel().uiState.value.tab) // restored from the same SavedStateHandle
    }
}
