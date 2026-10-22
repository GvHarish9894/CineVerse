package com.techgv.cineverse.presentation

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §7.7 (moved from tvApp SearchViewModelTest; keyboard editing and Clear's focus report stay there). */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchStateHolderTest {
    private val api = FakeCatalogApi()
    private val app = GenerationCounter()
    private val settings = MutableStateFlow(Settings())
    private val handle = SavedStateHandle()

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun holder() = SearchStateHolder(catalog(api, app, settings), app, settings, SavedStateHandleStateSaver(handle))

    private fun SectionState<PagedItems>.items() = (this as SectionState.Content).value.items

    private fun SectionState<PagedItems>.titles() = items().map { (it as Hero.OfTitle).title.title }

    private fun SearchStateHolder.type(text: String) = text.forEach { setQuery(currentQuery + it) }

    @Test fun emptyQueryShowsTrendingAndTypingSearchesAfterTheDebounce() = runTest {
        val holder = holder()
        advanceUntilIdle()
        assertEquals("trending@en-US-1-0", holder.uiState.value.results.titles().first())
        holder.type("dune")
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS - 1)
        assertEquals(SectionState.Loading, holder.uiState.value.results)
        advanceUntilIdle()
        assertEquals("search:dune-1-0", holder.uiState.value.results.titles().first())
        assertEquals(listOf("search/dune/1"), api.calls.filter { it.startsWith("search/") }) // one request per settled query
    }

    @Test fun staleResponseOfAnOlderQueryIsDropped() = runTest {
        val old = api.gate("search/a/1")
        val holder = holder()
        holder.type("a")
        advanceUntilIdle()
        holder.type("b")
        advanceUntilIdle()
        old.complete(Unit)
        advanceUntilIdle()
        assertEquals("ab", holder.uiState.value.query)
        assertEquals("search:ab-1-0", holder.uiState.value.results.titles().first())
    }

    @Test fun resultsMixTitlesAndPeopleInApiOrder() = runTest {
        api.mixedSearch = true
        val holder = holder()
        holder.setQuery("x")
        advanceUntilIdle()
        val items = holder.uiState.value.results.items()
        assertEquals(listOf(true, false, true), items.map { it is Hero.OfTitle })
        assertTrue(holder.uiState.value.searching)
    }

    @Test fun whitespaceOnlyChangesSaveTheQueryWithoutSearchingAgain() = runTest {
        val holder = holder()
        holder.setQuery("dune")
        advanceUntilIdle()
        holder.setQuery("dune ")
        advanceUntilIdle()
        assertEquals(listOf("search/dune/1"), api.calls.filter { it.startsWith("search/") })
        assertEquals("dune ", holder().currentQuery) // restored from the same saved state
    }
}
