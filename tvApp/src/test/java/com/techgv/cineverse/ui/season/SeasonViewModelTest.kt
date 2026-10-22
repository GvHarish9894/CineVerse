package com.techgv.cineverse.ui.season

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.EpisodeDto
import com.techgv.cineverse.data.tmdb.SeasonDto
import com.techgv.cineverse.data.tmdb.SeasonSummaryDto
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.data.tmdb.TvDetailDto
import com.techgv.cineverse.data.tmdb.toSeason
import com.techgv.cineverse.data.tmdb.unusedTmdbApi
import com.techgv.cineverse.ui.nav.Route
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** TV adapter: notice copy, SavedStateHandle round trip, screen copy (rules: shared SeasonStateHolderTest). */
@OptIn(ExperimentalCoroutinesApi::class)
class SeasonViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val settings = MutableStateFlow(Settings())
    private val generation = GenerationCounter()
    private val calls = mutableListOf<String>()
    private val seasonGates = mutableMapOf<Int, CompletableDeferred<Unit>>()
    private var seasons = listOf(SeasonSummaryDto(10, 0, "Specials"), SeasonSummaryDto(11, 1), SeasonSummaryDto(12, 2))

    private val api = object : TmdbApi by unusedTmdbApi {
        override suspend fun tvDetail(id: Int, language: String, append: String, sessionId: String?): TvDetailDto {
            calls += "tv/$id"
            return TvDetailDto(id, name = "Show", seasons = seasons)
        }

        override suspend fun season(seriesId: Int, seasonNumber: Int, language: String): SeasonDto {
            calls += "season/$seasonNumber"
            seasonGates[seasonNumber]?.await()
            return SeasonDto(seasonNumber, seasonNumber, episodes = listOf(EpisodeDto(seasonNumber * 100, episodeNumber = 1)))
        }
    }
    private val repo = TmdbRepository(api, generation, settings = { settings.value })

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(seasonNumber: Int, handle: SavedStateHandle = SavedStateHandle()) =
        SeasonViewModel(Route.Season(7, seasonNumber), handle, repo, settings, generation)

    @Test
    fun `missing season selects the first and toasts`() = runTest(dispatcher) {
        val vm = viewModel(9)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.selected)
        assertEquals(SEASON_NOT_AVAILABLE, vm.state.value.notice)
        vm.noticeShown()
        advanceUntilIdle()
        assertNull(vm.state.value.notice)
    }

    @Test
    fun `selection and open panel survive process death via saved state`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = viewModel(1, handle)
        advanceUntilIdle()
        vm.selectSeason(2)
        vm.openEpisode(1)
        advanceUntilIdle()

        val restored = viewModel(1, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        advanceUntilIdle()
        assertEquals(2, restored.state.value.selected)
        assertEquals(1, restored.state.value.openEpisode)
    }

    @Test
    fun `episode meta - unaired shows Airs date and no rating`() {
        val today = kotlinx.datetime.LocalDate(2026, 10, 21)
        val aired = EpisodeDto(1, episodeNumber = 1, airDate = "2026-10-01", runtime = 48, voteAverage = 8.25, voteCount = 3)
        val unaired = aired.copy(airDate = "2026-11-02")
        fun meta(e: EpisodeDto) = episodeMeta(SeasonDto(1, 1, episodes = listOf(e)).toSeason(7).episodes.single(), today)
        assertEquals(listOf("Oct 1, 2026", "48m", "★ 8.3"), meta(aired))
        assertEquals(listOf("Airs Nov 2, 2026", "48m"), meta(unaired))
    }
}
