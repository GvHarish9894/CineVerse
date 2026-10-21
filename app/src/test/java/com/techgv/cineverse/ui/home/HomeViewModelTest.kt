package com.techgv.cineverse.ui.home

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.VideoDto
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.ui.FakeCatalogApi
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.ui.fakeRepository
import com.techgv.cineverse.ui.nav.Route
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HomeViewModelTest {
    private val api = FakeCatalogApi()
    private val app = GenerationCounter()
    private val settings = MutableStateFlow(Settings(language = "en-US"))
    private val dune = Title(MediaRef(MediaType.Movie, 7), "Dune", "", null, null, null, 0.0, 0, emptyList(), MediaType.Movie)

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = HomeViewModel(fakeRepository(api, app, settings), app, settings, SavedStateHandle())

    private fun TestScope.events(vm: HomeViewModel): List<HomeEvent> = mutableListOf<HomeEvent>().also { events ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
    }

    private fun HomeUiState.heroTitle() = (hero as Hero.OfTitle).title.title

    @Test fun `rows, genres and the default hero load`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state.rows.values.all { it is SectionState.Content })
        assertEquals("trending@en-US-1-0", state.heroTitle())
        assertEquals(mapOf(28 to "movie@en-US", 18 to "tv@en-US"), state.genres)
    }

    @Test fun `deferred old-language genres cannot overwrite the new language`() = runTest {
        val oldGenres = api.gate("genres/movie/en-US")
        val vm = viewModel()
        advanceUntilIdle()
        settings.value = Settings(language = "hi-IN")
        advanceUntilIdle()
        oldGenres.complete(Unit)
        advanceUntilIdle()
        assertEquals(mapOf(28 to "movie@hi-IN", 18 to "tv@hi-IN"), vm.uiState.value.genres)
    }

    @Test fun `settings reload drops the focused hero of the old language`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onItemFocused(Hero.OfTitle(dune))
        advanceUntilIdle()
        assertEquals("Dune", vm.uiState.value.heroTitle())
        settings.value = Settings(language = "hi-IN")
        advanceUntilIdle()
        assertEquals("trending@hi-IN-1-0", vm.uiState.value.heroTitle())
    }

    @Test fun `trailer opens the Player with the title and the video name as kicker`() = runTest {
        api.videos = listOf(VideoDto(key = "yt1", name = "Official Trailer", site = "YouTube", type = "Trailer", official = true))
        val vm = viewModel()
        val events = events(vm)
        vm.setActive(true)
        vm.playTrailer(dune)
        advanceUntilIdle()
        assertEquals(listOf(HomeEvent.Open(Route.Player("yt1", title = "Dune", kicker = "Official Trailer"))), events)
        assertNull(vm.uiState.value.trailerLoading)
    }

    @Test fun `trailer with a blank video name uses Trailer as kicker`() = runTest {
        api.videos = listOf(VideoDto(key = "yt2", name = "", site = "YouTube", type = "Teaser"))
        val vm = viewModel()
        val events = events(vm)
        vm.setActive(true)
        vm.playTrailer(dune)
        advanceUntilIdle()
        assertEquals(listOf(HomeEvent.Open(Route.Player("yt2", title = "Dune", kicker = "Trailer"))), events)
    }

    @Test fun `trailer fetch superseded by a settings reload emits no navigation`() = runTest {
        api.videos = listOf(VideoDto(key = "yt1", name = "Trailer", site = "YouTube", type = "Trailer"))
        val gate = api.gate("videos/7/en-US")
        val vm = viewModel()
        val events = events(vm)
        vm.setActive(true)
        vm.playTrailer(dune)
        advanceUntilIdle()
        assertEquals(dune.ref, vm.uiState.value.trailerLoading)
        settings.value = Settings(language = "hi-IN")
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptyList<HomeEvent>(), events)
        assertNull(vm.uiState.value.trailerLoading)
    }

    @Test fun `trailer fetch outliving the screen emits no navigation after it returns`() = runTest {
        api.videos = listOf(VideoDto(key = "yt1", name = "Trailer", site = "YouTube", type = "Trailer"))
        val gate = api.gate("videos/7/en-US")
        val vm = viewModel()
        val events = events(vm)
        vm.setActive(true)
        vm.playTrailer(dune)
        advanceUntilIdle()
        vm.setActive(false) // e.g. "More info" pushed Detail while the trailer was loading
        vm.setActive(true) // back on Home
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptyList<HomeEvent>(), events)
    }
}
