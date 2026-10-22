package com.techgv.cineverse.presentation

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.EpisodeDto
import com.techgv.cineverse.data.tmdb.SeasonDto
import com.techgv.cineverse.data.tmdb.SeasonSummaryDto
import com.techgv.cineverse.data.tmdb.TvDetailDto
import com.techgv.cineverse.data.tmdb.UnusedTmdbApi
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** §7.6 (moved from tvApp SeasonViewModelTest; the notice copy and SavedStateHandle round trip stay there). */
@OptIn(ExperimentalCoroutinesApi::class)
class SeasonStateHolderTest {
    private val dispatcher = StandardTestDispatcher()
    private val settings = MutableStateFlow(Settings())
    private val generation = GenerationCounter()
    private val calls = mutableListOf<String>()
    private val seasonGates = mutableMapOf<Int, CompletableDeferred<Unit>>()
    private var seasons = listOf(SeasonSummaryDto(10, 0, "Specials"), SeasonSummaryDto(11, 1), SeasonSummaryDto(12, 2))

    private val api = object : UnusedTmdbApi() {
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
    private val repo = catalog(api, generation, settings)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun holder(seasonNumber: Int, handle: SavedStateHandle = SavedStateHandle()) =
        SeasonStateHolder(7, seasonNumber, repo, settings, generation, SavedStateHandleStateSaver(handle))

    @Test fun loadsItsOwnTvDetailSpecialsLastAndSelectsTheRouteSeason() = runTest(dispatcher) {
        val holder = holder(2)
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 0), (holder.state.value.show as SectionState.Content).value.seasons.map { it.seasonNumber })
        assertEquals(2, holder.state.value.selected)
        assertEquals(2, (holder.state.value.episodes as SectionState.Content).value.seasonNumber)
        assertNull(holder.state.value.notice)
        assertEquals(listOf("tv/7", "season/2"), calls)
    }

    @Test fun missingSeasonSelectsTheFirstAndRaisesANotice() = runTest(dispatcher) {
        val holder = holder(9)
        advanceUntilIdle()
        assertEquals(1, holder.state.value.selected)
        assertEquals(SeasonNotice.SeasonNotAvailable, holder.state.value.notice)
        holder.noticeShown()
        assertNull(holder.state.value.notice)
    }

    @Test fun specialsOnlyShowSelectsSpecials() = runTest(dispatcher) {
        seasons = listOf(SeasonSummaryDto(10, 0, "Specials"))
        val holder = holder(1)
        advanceUntilIdle()
        assertEquals(0, holder.state.value.selected)
    }

    @Test fun noSeasonsSelectsNothingAndEpisodesAreEmpty() = runTest(dispatcher) {
        seasons = emptyList()
        val holder = holder(1)
        advanceUntilIdle()
        assertNull(holder.state.value.selected)
        assertEquals(SectionState.Empty, holder.state.value.episodes)
        assertNull(holder.state.value.notice)
    }

    @Test fun aSlowEarlierSeasonNeverReplacesTheNewlySelectedOne() = runTest(dispatcher) {
        val holder = holder(1)
        advanceUntilIdle()
        val slow = CompletableDeferred<Unit>().also { seasonGates[2] = it }
        holder.selectSeason(2)
        advanceUntilIdle()
        holder.selectSeason(0)
        slow.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, holder.state.value.selected)
        assertEquals(0, (holder.state.value.episodes as SectionState.Content).value.seasonNumber)
    }

    @Test fun appGenerationBumpWithoutASettingsEmissionReloadsDroppedEpisodes() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>().also { seasonGates[1] = it }
        val holder = holder(1)
        advanceUntilIdle()
        assertEquals(SectionState.Loading, holder.state.value.episodes)
        generation.bump()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, (holder.state.value.episodes as SectionState.Content).value.seasonNumber)
        assertEquals(listOf("tv/7", "season/1", "season/1"), calls)
    }

    @Test fun selectionAndOpenPanelAreSavedScalars() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val holder = holder(1, handle)
        advanceUntilIdle()
        holder.selectSeason(2)
        holder.openEpisode(1)
        advanceUntilIdle()

        val restored = holder(1, handle)
        advanceUntilIdle()
        assertEquals(2, restored.state.value.selected)
        assertEquals(1, restored.state.value.openEpisode)
        restored.closeEpisode()
        assertNull(holder(1, handle).state.value.openEpisode)
    }
}
