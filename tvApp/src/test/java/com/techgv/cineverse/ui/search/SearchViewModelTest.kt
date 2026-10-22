package com.techgv.cineverse.ui.search

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.ui.FakeCatalogApi
import com.techgv.cineverse.ui.components.KeyboardKey
import com.techgv.cineverse.presentation.PagedItems
import com.techgv.cineverse.presentation.SectionState
import com.techgv.cineverse.ui.fakeRepository
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** TV adapter: keyboard editing and Clear's focus report (rules: shared SearchStateHolderTest). */
class SearchViewModelTest {
    private val api = FakeCatalogApi()
    private val app = GenerationCounter()
    private val settings = MutableStateFlow(Settings())

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SearchViewModel(fakeRepository(api, app, settings), app, settings, SavedStateHandle())

    private fun SectionState<PagedItems>.titles() = (this as SectionState.Content).value.items.map { (it as Hero.OfTitle).title.title }

    private fun TestScope.clears(vm: SearchViewModel): List<SectionState<PagedItems>> = mutableListOf<SectionState<PagedItems>>().also { out ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.clearSettled.collect { out += it } }
    }

    private fun SearchViewModel.type(text: String) = text.forEach { onTyped(it) }

    @Test fun `every Clear reports its settled results, not one-shot`() = runTest {
        val vm = viewModel()
        val clears = clears(vm)
        advanceUntilIdle()
        vm.type("x")
        advanceUntilIdle()
        vm.onKey(KeyboardKey.Clear)
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.query)
        assertEquals(listOf("trending@en-US-1-0", "trending@en-US-1-1", "trending@en-US-1-2"), clears.single().titles())

        vm.type("y")
        advanceUntilIdle()
        vm.onKey(KeyboardKey.Clear)
        advanceUntilIdle()
        assertEquals(2, clears.size)
    }

    @Test fun `Clear waits for the new generation, never reports the previous query's results`() = runTest {
        val trending = api.gate("trending/week/en-US/1")
        val vm = viewModel()
        val clears = clears(vm)
        vm.type("x")
        advanceUntilIdle()
        assertEquals("search:x-1-0", vm.uiState.value.results.titles().first())
        vm.onKey(KeyboardKey.Clear)
        advanceUntilIdle()
        assertEquals(emptyList<SectionState<PagedItems>>(), clears) // trending still loading
        trending.complete(Unit)
        advanceUntilIdle()
        assertEquals("trending@en-US-1-0", clears.single().titles().first())
    }

    @Test fun `typing after Clear supersedes its pending focus report`() = runTest {
        val trending = api.gate("trending/week/en-US/1")
        val vm = viewModel()
        val clears = clears(vm)
        vm.type("x")
        advanceUntilIdle()
        vm.onKey(KeyboardKey.Clear)
        vm.type("z")
        trending.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptyList<SectionState<PagedItems>>(), clears)
    }
}
