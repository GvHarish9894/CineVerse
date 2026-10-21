package com.techgv.cineverse.ui.detail

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.MovieDetailDto
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.data.tmdb.VideoDto
import com.techgv.cineverse.data.tmdb.VideosDto
import com.techgv.cineverse.data.tmdb.unusedTmdbApi
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.ui.components.SectionState
import com.techgv.cineverse.ui.nav.Route
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val calls = mutableListOf<String>()
    private val settings = MutableStateFlow(Settings())
    private val generation = GenerationCounter()
    private var movieGate: CompletableDeferred<Unit>? = null

    private val api = object : TmdbApi by unusedTmdbApi {
        override suspend fun movieDetail(id: Int, language: String, append: String, sessionId: String?): MovieDetailDto {
            calls += "movie/$language"
            movieGate?.await()
            return if (language == ENGLISH) {
                MovieDetailDto(id, title = "Title", tagline = "English tagline", overview = "English overview")
            } else {
                MovieDetailDto(id, title = "Title", tagline = "", overview = "स्थानीय")
            }
        }

        override suspend fun videos(media: MediaType, id: Int, language: String): VideosDto {
            calls += "videos/$language"
            return VideosDto(listOf(VideoDto(key = "yt1", site = "YouTube", type = "Trailer", official = true)))
        }
    }
    private val repo = TmdbRepository(api, generation, settings = { settings.value })

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): DetailViewModel =
        DetailViewModel(Route.Detail(MediaType.Movie, 550), SavedStateHandle(), repo, settings, generation)
            .also { advanceUntilIdle() }

    @Test
    fun `en-US - one catalog request, no fallbacks`() = runTest(dispatcher) {
        val vm = viewModel()
        assertEquals(listOf("movie/en-US"), calls)
        val detail = (vm.state.value.detail as SectionState.Content).value
        assertEquals("English tagline", detail.tagline)
        assertFalse(detail.taglineInEnglish)
        assertTrue(detail.videos.isEmpty()) // no en-US refetch when the language already is en-US
    }

    @Test
    fun `non-en-US - blank text filled from cached en-US detail and labelled, empty videos refetched in en-US`() = runTest(dispatcher) {
        settings.value = Settings(language = "hi-IN")
        val vm = viewModel()
        val detail = (vm.state.value.detail as SectionState.Content).value
        assertEquals("English tagline", detail.tagline)
        assertTrue(detail.taglineInEnglish)
        assertEquals("स्थानीय", detail.overview)
        assertEquals(listOf("yt1"), detail.videos.map { it.key })
        assertEquals(listOf("movie/hi-IN", "movie/en-US", "videos/en-US"), calls)
    }

    @Test
    fun `app generation bump without a settings emission - the dropped result reloads, never stuck Loading`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>().also { movieGate = it }
        val vm = viewModel()
        assertEquals(SectionState.Loading, vm.state.value.detail)
        generation.bump() // sign-in / sign-out: no new settings value reaches the screen
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.detail is SectionState.Content)
        assertEquals(listOf("movie/en-US", "movie/en-US"), calls)
    }

    @Test
    fun `language change reloads the visible detail`() = runTest(dispatcher) {
        viewModel()
        settings.value = Settings(language = "es-ES")
        advanceUntilIdle()
        assertTrue("movie/es-ES" in calls)
    }
}
