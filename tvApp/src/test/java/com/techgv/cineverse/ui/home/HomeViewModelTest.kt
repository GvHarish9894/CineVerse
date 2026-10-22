package com.techgv.cineverse.ui.home

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.VideoDto
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.ui.FakeCatalogApi
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
import org.junit.Before
import org.junit.Test

/** TV adapter: trailer events → Player routes / toasts, "active" = resumed and window-focused (rules: shared HomeStateHolderTest). */
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

    @Test fun `pending trailer navigation is dropped when window focus is lost before the fetch completes`() = runTest {
        api.videos = listOf(VideoDto(key = "yt1", name = "Trailer", site = "YouTube", type = "Trailer"))
        val gate = api.gate("videos/7/en-US")
        val vm = viewModel()
        val events = events(vm)
        vm.setActive(true)
        vm.playTrailer(dune)
        advanceUntilIdle()
        vm.setWindowFocused(false) // BACK opened the Exit dialog over Home (Home stays RESUMED)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptyList<HomeEvent>(), events)
        assertNull(vm.uiState.value.trailerLoading)
        vm.setWindowFocused(true) // Stay: a new press plays again
        vm.playTrailer(dune)
        advanceUntilIdle()
        assertEquals(listOf(HomeEvent.Open(Route.Player("yt1", title = "Dune", kicker = "Trailer"))), events)
    }

    @Test fun `trailer fetch completing after Home paused emits no navigation while events are still collected`() = runTest {
        api.videos = listOf(VideoDto(key = "yt1", name = "Trailer", site = "YouTube", type = "Trailer"))
        val gate = api.gate("videos/7/en-US")
        val vm = viewModel()
        val events = events(vm)
        vm.setActive(true)
        vm.playTrailer(dune)
        advanceUntilIdle()
        vm.setActive(false) // RESUMED → STARTED: Detail pushed, Home still STARTED during the exit transition
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptyList<HomeEvent>(), events)
        assertNull(vm.uiState.value.trailerLoading)
    }
}
