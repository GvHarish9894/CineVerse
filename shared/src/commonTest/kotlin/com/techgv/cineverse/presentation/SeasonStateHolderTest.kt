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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
    private var ignoreSeasonCancellation = false
    private var seasons = listOf(SeasonSummaryDto(10, 0, "Specials"), SeasonSummaryDto(11, 1), SeasonSummaryDto(12, 2))

    private val api = object : UnusedTmdbApi() {
        override suspend fun tvDetail(id: Int, language: String, append: String, sessionId: String?): TvDetailDto {
            calls += "tv/$id"
            return TvDetailDto(id, name = "Show", seasons = seasons)
        }

        override suspend fun season(seriesId: Int, seasonNumber: Int, language: String): SeasonDto {
            calls += "season/$seasonNumber"
            if (ignoreSeasonCancellation) withContext(NonCancellable) { seasonGates[seasonNumber]?.await() }
            else seasonGates[seasonNumber]?.await()
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
        val handle = SavedStateHandle()
        val holder = holder(9, handle)
        advanceUntilIdle()
        assertEquals(1, holder.state.value.selected)
        assertEquals(1, handle.get<Int>("selectedSeason"))
        assertEquals(1, (holder.state.value.episodes as SectionState.Content).value.seasonNumber)
        assertEquals(listOf("tv/7", "season/1"), calls)
        assertEquals(SeasonNotice.SeasonNotAvailable, holder.state.value.notice)
        holder.noticeShown()
        assertNull(holder.state.value.notice)
    }

    @Test fun missingSavedSeasonStillFallsBackByDefaultInsteadOfUsingTheRoute() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf("selectedSeason" to 9, "openEpisode" to 4))
        val holder = holder(2, handle)
        advanceUntilIdle()
        assertEquals(1, holder.state.value.selected)
        assertEquals(1, handle.get<Int>("selectedSeason"))
        assertEquals(4, handle.get<Int>("openEpisode"))
        assertEquals(4, holder.state.value.openEpisode)
        assertEquals(SeasonNotice.SeasonNotAvailable, holder.state.value.notice)
        assertEquals(listOf("tv/7", "season/1"), calls)
    }

    @Test fun specialsOnlyShowSelectsSpecials() = runTest(dispatcher) {
        seasons = listOf(SeasonSummaryDto(10, 0, "Specials"))
        val holder = holder(1)
        advanceUntilIdle()
        assertEquals(0, holder.state.value.selected)
    }

    @Test fun noSeasonsSelectsNothingAndEpisodesAreEmpty() = runTest(dispatcher) {
        seasons = emptyList()
        val handle = SavedStateHandle(mapOf("selectedSeason" to 9))
        val holder = holder(1, handle)
        advanceUntilIdle()
        assertNull(holder.state.value.selected)
        assertNull(handle.get<Int>("selectedSeason"))
        assertEquals(SectionState.Empty, holder.state.value.episodes)
        assertNull(holder.state.value.notice)
        assertEquals(listOf("tv/7"), calls)
    }

    private class RecordingSaver(val handle: SavedStateHandle = SavedStateHandle()) :
        StateSaver by SavedStateHandleStateSaver(handle) {
        val writes = mutableListOf<Pair<String, Int?>>()
        override fun putInt(key: String, value: Int?) {
            writes += key to value
            handle[key] = value
        }
    }

    private fun noFallbackHolder(seasonNumber: Int, saver: RecordingSaver) =
        SeasonStateHolder(7, seasonNumber, repo, settings, generation, saver, fallbackToFirstSeason = false)

    @Test fun missingRouteSeasonWithoutFallbackDoesNotFetchOrSaveAnotherSeason() = runTest(dispatcher) {
        val saver = RecordingSaver(SavedStateHandle(mapOf("openEpisode" to 4, "otherRawState" to "unchanged")))
        val holder = noFallbackHolder(9, saver)
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 0), (holder.state.value.show as SectionState.Content).value.seasons.map { it.seasonNumber })
        assertNull(holder.state.value.selected)
        assertEquals(SectionState.Empty, holder.state.value.episodes)
        assertEquals(SeasonNotice.SeasonNotAvailable, holder.state.value.notice)
        assertEquals(4, holder.state.value.openEpisode)
        assertEquals(setOf("openEpisode", "otherRawState"), saver.handle.keys())
        assertEquals("unchanged", saver.handle.get<String>("otherRawState"))
        assertEquals(emptyList(), saver.writes)
        assertEquals(listOf("tv/7"), calls)
        holder.retryEpisodes()
        holder.retry()
        advanceUntilIdle()
        assertNull(holder.state.value.selected)
        assertEquals(SectionState.Empty, holder.state.value.episodes)
        assertEquals(emptyList(), saver.writes)
        assertEquals(listOf("tv/7"), calls)
    }

    @Test fun missingSavedSeasonWithoutFallbackPreservesItEvenWhenRouteIsAvailable() = runTest(dispatcher) {
        val saver = RecordingSaver(SavedStateHandle(mapOf("selectedSeason" to 9, "openEpisode" to 4)))
        val holder = noFallbackHolder(2, saver)
        advanceUntilIdle()
        assertNull(holder.state.value.selected)
        assertEquals(SectionState.Empty, holder.state.value.episodes)
        assertEquals(SeasonNotice.SeasonNotAvailable, holder.state.value.notice)
        assertEquals(9, saver.handle.get<Int>("selectedSeason"))
        assertEquals(4, saver.handle.get<Int>("openEpisode"))
        assertEquals(4, holder.state.value.openEpisode)
        assertEquals(emptyList(), saver.writes)
        assertEquals(listOf("tv/7"), calls)
    }

    @Test fun knownRouteSeasonWithoutFallbackLoadsItsRealNumberNotItsPosition() = runTest(dispatcher) {
        seasons = listOf(SeasonSummaryDto(10, 0), SeasonSummaryDto(13, 3), SeasonSummaryDto(17, 7))
        val saver = RecordingSaver()
        val holder = noFallbackHolder(7, saver)
        advanceUntilIdle()
        assertEquals(7, holder.state.value.selected)
        assertEquals(7, (holder.state.value.episodes as SectionState.Content).value.seasonNumber)
        assertNull(holder.state.value.notice)
        assertEquals(listOf("selectedSeason" to 7), saver.writes)
        assertEquals(listOf("tv/7", "season/7"), calls)
    }

    @Test fun knownSavedSeasonWithoutFallbackStillOverridesTheRoute() = runTest(dispatcher) {
        val saver = RecordingSaver(SavedStateHandle(mapOf("selectedSeason" to 2)))
        val holder = noFallbackHolder(9, saver)
        advanceUntilIdle()
        assertEquals(2, holder.state.value.selected)
        assertEquals(2, (holder.state.value.episodes as SectionState.Content).value.seasonNumber)
        assertNull(holder.state.value.notice)
        assertEquals(listOf("selectedSeason" to 2), saver.writes)
        assertEquals(listOf("tv/7", "season/2"), calls)
    }

    @Test fun specialsRouteWithoutFallbackLoadsSeasonZero() = runTest(dispatcher) {
        val saver = RecordingSaver()
        val holder = noFallbackHolder(0, saver)
        advanceUntilIdle()
        assertEquals(0, holder.state.value.selected)
        assertEquals(0, (holder.state.value.episodes as SectionState.Content).value.seasonNumber)
        assertNull(holder.state.value.notice)
        assertEquals(listOf("selectedSeason" to 0), saver.writes)
        assertEquals(listOf("tv/7", "season/0"), calls)
    }

    @Test fun savedSpecialsWithoutFallbackStillOverridesTheRoute() = runTest(dispatcher) {
        val saver = RecordingSaver(SavedStateHandle(mapOf("selectedSeason" to 0)))
        val holder = noFallbackHolder(2, saver)
        advanceUntilIdle()
        assertEquals(0, holder.state.value.selected)
        assertEquals(0, (holder.state.value.episodes as SectionState.Content).value.seasonNumber)
        assertNull(holder.state.value.notice)
        assertEquals(listOf("selectedSeason" to 0), saver.writes)
        assertEquals(listOf("tv/7", "season/0"), calls)
    }

    @Test fun noSeasonsWithoutFallbackPreservesSavedSelectionAndDoesNotFetch() = runTest(dispatcher) {
        seasons = emptyList()
        val saver = RecordingSaver(SavedStateHandle(mapOf("selectedSeason" to 9)))
        val holder = noFallbackHolder(1, saver)
        advanceUntilIdle()
        assertEquals(emptyList(), (holder.state.value.show as SectionState.Content).value.seasons)
        assertNull(holder.state.value.selected)
        assertEquals(SectionState.Empty, holder.state.value.episodes)
        assertEquals(SeasonNotice.SeasonNotAvailable, holder.state.value.notice)
        assertEquals(9, saver.handle.get<Int>("selectedSeason"))
        assertEquals(emptyList(), saver.writes)
        assertEquals(listOf("tv/7"), calls)
    }

    @Test fun noSeasonsWithoutFallbackDoesNotSaveADefaultForTheRoute() = runTest(dispatcher) {
        seasons = emptyList()
        val saver = RecordingSaver()
        val holder = noFallbackHolder(0, saver)
        advanceUntilIdle()
        assertNull(holder.state.value.selected)
        assertEquals(SectionState.Empty, holder.state.value.episodes)
        assertEquals(SeasonNotice.SeasonNotAvailable, holder.state.value.notice)
        assertEquals(emptySet(), saver.handle.keys())
        assertEquals(emptyList(), saver.writes)
        assertEquals(listOf("tv/7"), calls)
    }

    @Test fun unavailableReloadWithoutFallbackRevokesEarlierEpisodesWithoutRewritingSelection() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>().also { seasonGates[2] = it }
        ignoreSeasonCancellation = true
        val saver = RecordingSaver(SavedStateHandle(mapOf("selectedSeason" to 2)))
        val holder = noFallbackHolder(2, saver)
        advanceUntilIdle()
        assertEquals(SectionState.Loading, holder.state.value.episodes)
        assertEquals(listOf("tv/7", "season/2"), calls)
        saver.writes.clear()

        seasons = listOf(SeasonSummaryDto(11, 1))
        settings.value = settings.value.copy(language = "fr-FR")
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(1), (holder.state.value.show as SectionState.Content).value.seasons.map { it.seasonNumber })
        assertNull(holder.state.value.selected)
        assertEquals(SectionState.Empty, holder.state.value.episodes)
        assertEquals(SeasonNotice.SeasonNotAvailable, holder.state.value.notice)
        assertEquals(2, saver.handle.get<Int>("selectedSeason"))
        assertEquals(emptyList(), saver.writes)
        assertEquals(listOf("tv/7", "season/2", "tv/7"), calls)
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
