package com.techgv.cineverse.presentation

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.data.tmdb.VideoDto
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §7.2 Home rules (moved from tvApp HomeViewModelTest; the Player-route / window-focus mapping stays there). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeStateHolderTest {
    private val api = FakeCatalogApi()
    private val app = GenerationCounter()
    private val settings = MutableStateFlow(Settings(language = "en-US"))
    private val handle = SavedStateHandle()
    private val dune = title(7).copy(title = "Dune")

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun holder() = HomeStateHolder(catalog(api, app, settings), app, settings, SavedStateHandleStateSaver(handle))

    private fun TestScope.events(holder: HomeStateHolder): List<TrailerEvent> = mutableListOf<TrailerEvent>().also { events ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.events.collect { events += it } }
    }

    private fun HomeUiState.heroTitle() = (hero as Hero.OfTitle).title.title

    private fun trailer(name: String = "Trailer") = VideoDto(key = "yt1", name = name, site = "YouTube", type = "Trailer", official = true)

    @Test fun rowsGenresAndTheDefaultHeroLoad() = runTest {
        val holder = holder()
        advanceUntilIdle()
        val state = holder.uiState.value
        assertTrue(state.rows.values.all { it is SectionState.Content })
        assertEquals("trending@en-US-1-0", state.heroTitle())
        assertEquals(mapOf(28 to "movie@en-US", 18 to "tv@en-US"), state.genres)
    }

    @Test fun deferredOldLanguageGenresCannotOverwriteTheNewLanguage() = runTest {
        val oldGenres = api.gate("genres/movie/en-US")
        val holder = holder()
        advanceUntilIdle()
        settings.value = Settings(language = "hi-IN")
        advanceUntilIdle()
        oldGenres.complete(Unit)
        advanceUntilIdle()
        assertEquals(mapOf(28 to "movie@hi-IN", 18 to "tv@hi-IN"), holder.uiState.value.genres)
    }

    @Test fun settingsReloadDropsTheSelectedHeroOfTheOldLanguage() = runTest {
        val holder = holder()
        advanceUntilIdle()
        holder.selectHero(Hero.OfTitle(dune), delayMillis = 120)
        advanceUntilIdle()
        assertEquals("Dune", holder.uiState.value.heroTitle())
        settings.value = Settings(language = "hi-IN")
        advanceUntilIdle()
        assertEquals("trending@hi-IN-1-0", holder.uiState.value.heroTitle())
    }

    @Test fun aNewerHeroSelectionCancelsAPendingOne() = runTest {
        val holder = holder()
        advanceUntilIdle()
        holder.selectHero(Hero.OfTitle(dune), delayMillis = 120)
        holder.selectHero(Hero.OfTitle(title(8)), delayMillis = 120)
        advanceUntilIdle()
        assertEquals("Title 8", holder.uiState.value.heroTitle())
    }

    @Test fun trendWindowIsSavedAndReloadsOnlyTheTrendingRow() = runTest {
        val holder = holder()
        advanceUntilIdle()
        api.calls.clear()
        holder.selectTrend(TrendingWindow.Day)
        advanceUntilIdle()
        assertEquals(listOf("trending/day/en-US/1"), api.calls)
        assertEquals(TrendingWindow.Day, holder().uiState.value.trend) // restored from the same saved state
    }

    @Test fun pagingForwardsOnlyToTheRequestedRowAndGatesDuplicateLoads() = runTest {
        api.totalPages = 2
        val holder = holder()
        advanceUntilIdle()
        val otherRows = holder.uiState.value.rows - HomeRow.Trending
        api.calls.clear()
        val gate = api.gate("trending/week/en-US/2")
        holder.loadMore(HomeRow.Trending)
        holder.loadMore(HomeRow.Trending)
        advanceUntilIdle()
        assertEquals(listOf("trending/week/en-US/2"), api.calls)
        gate.complete(Unit)
        advanceUntilIdle()
        val page = (holder.uiState.value.rows.getValue(HomeRow.Trending) as SectionState.Content).value
        assertEquals(2, page.page)
        assertEquals(6, page.items.size)
        assertEquals(otherRows, holder.uiState.value.rows - HomeRow.Trending)
        holder.loadMore(HomeRow.Trending)
        advanceUntilIdle()
        assertEquals(listOf("trending/week/en-US/2"), api.calls)
    }

    @Test fun trailerPlaysTheFirstYouTubeVideoWithItsNameAsKicker() = runTest {
        api.videosFor = { listOf(trailer("Official Trailer")) }
        val holder = holder()
        val events = events(holder)
        holder.setActive(true)
        holder.playTrailer(dune)
        advanceUntilIdle()
        assertEquals(listOf<TrailerEvent>(TrailerEvent.Play("yt1", "Dune", "Official Trailer")), events)
        assertNull(holder.uiState.value.trailerLoading)
        assertEquals("Trailer", trailerOf("Dune", "yt2", "").kicker)
    }

    @Test fun noLocalizedTrailerFallsBackToEnglishOnceThenReportsUnavailable() = runTest {
        settings.value = Settings(language = "hi-IN")
        val holder = holder()
        val events = events(holder)
        holder.setActive(true)
        holder.playTrailer(dune)
        advanceUntilIdle()
        assertEquals(listOf("videos/7/hi-IN", "videos/7/en-US"), api.calls.filter { it.startsWith("videos/") })
        assertEquals(listOf<TrailerEvent>(TrailerEvent.Unavailable("Dune")), events)
    }

    @Test fun failedTrailerFetchReportsFailed() = runTest {
        api.failVideos = true
        val holder = holder()
        val events = events(holder)
        holder.setActive(true)
        holder.playTrailer(dune)
        advanceUntilIdle()
        assertEquals(listOf<TrailerEvent>(TrailerEvent.Failed), events)
    }

    @Test fun trailerSupersededBySettingsOrDeactivationEmitsNothing() = runTest {
        api.videosFor = { listOf(trailer()) }
        val gate = api.gate("videos/7/en-US")
        val holder = holder()
        val events = events(holder)
        holder.setActive(true)
        holder.playTrailer(dune)
        advanceUntilIdle()
        assertEquals(dune.ref, holder.uiState.value.trailerLoading)
        settings.value = Settings(language = "hi-IN")
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptyList(), events)
        assertNull(holder.uiState.value.trailerLoading)

        api.gates.clear()
        val again = api.gate("videos/7/hi-IN")
        holder.playTrailer(dune)
        advanceUntilIdle()
        holder.setActive(false)
        holder.setActive(true)
        again.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptyList(), events)
    }

    @Test fun trailerPressWhileInactiveIsIgnored() = runTest {
        api.videosFor = { listOf(trailer()) }
        val holder = holder()
        val events = events(holder)
        holder.playTrailer(dune)
        advanceUntilIdle()
        assertEquals(emptyList(), events)
        assertEquals(emptyList(), api.calls.filter { it.startsWith("videos/") })
    }
}
