package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.domain.Hero
import com.techgv.cineverse.presentation.HomeRow
import com.techgv.cineverse.presentation.SectionErrorKind
import com.techgv.cineverse.presentation.SectionState
import com.techgv.cineverse.presentation.TrailerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MobileHomeStorageTest {
    @Test fun loadedHomeExplicitRetryRecoversSameSettingsAndFailedPageWithoutReplayingTrailer() = runTest {
        val f = MobileFixture(backgroundScope)
        val model = MobileHomeViewModel(f.graph, SavedStateHandle(), backgroundScope)
        runCurrent()
        val before = model.home.uiState.value
        val firstPage = (before.rows.getValue(HomeRow.Trending) as SectionState.Content).value
        val settings = f.graph.settings.value
        val emissions = mutableListOf<com.techgv.cineverse.data.settings.Settings?>()
        val events = mutableListOf<TrailerEvent>()
        backgroundScope.launch { f.graph.settings.collect { emissions += it } }
        backgroundScope.launch { model.home.events.collect { events += it } }
        runCurrent()

        f.store.fail = true
        f.store.values.value = mapOf("unrelated" to "storage-down") // Same effective persisted settings.
        model.home.loadMore(HomeRow.Trending)
        model.home.setActive(true)
        model.home.playTrailer((before.hero as Hero.OfTitle).title)
        runCurrent()
        assertTrue(f.graph.settingsFailed.value)
        assertTrue(model.storageFailed.value)
        val failedPage = (model.home.uiState.value.rows.getValue(HomeRow.Trending) as SectionState.Content).value
        assertEquals(firstPage.items, failedPage.items)
        assertEquals(SectionErrorKind.Generic, failedPage.moreError)
        assertEquals(before.hero, model.home.uiState.value.hero)
        assertEquals(before.genres, model.home.uiState.value.genres)
        assertEquals(listOf<TrailerEvent>(TrailerEvent.Failed), events)

        f.store.fail = false // Recovery itself emits no new Settings value.
        runCurrent()
        assertTrue(model.storageFailed.value)
        assertEquals(1, (model.home.uiState.value.rows.getValue(HomeRow.Trending) as SectionState.Content).value.page)
        model.retryStorage()
        runCurrent()
        assertFalse(f.graph.settingsFailed.value)
        assertFalse(model.storageFailed.value)
        assertEquals(settings, f.graph.settings.value)
        assertEquals(listOf(settings), emissions)
        val recovered = (model.home.uiState.value.rows.getValue(HomeRow.Trending) as SectionState.Content).value
        assertEquals(2, recovered.page)
        assertEquals(firstPage.items, recovered.items.take(firstPage.items.size))
        assertNull(recovered.moreError)
        assertEquals(before.rows - HomeRow.Trending, model.home.uiState.value.rows - HomeRow.Trending)
        assertEquals(before.hero, model.home.uiState.value.hero)
        assertNull(model.home.uiState.value.trailerLoading)
        assertTrue(f.api.calls.none { it.startsWith("videos/") })
        assertEquals(listOf<TrailerEvent>(TrailerEvent.Failed), events)
        assertTrue(f.guest.rows.value.isEmpty())
        f.graph.close()
    }

    @Test fun loadedRowsSurviveGenreFailureAndExplicitRetryRestartsGenresWithUnchangedSettings() = runTest {
        val f = MobileFixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        f.api.genreGate = gate
        f.api.failGenresStorage = true
        val model = MobileHomeViewModel(f.graph, SavedStateHandle(), backgroundScope)
        runCurrent()
        val before = model.home.uiState.value
        assertTrue(before.rows.values.all { it is SectionState.Content })
        gate.complete(Unit)
        runCurrent()
        assertEquals(SectionErrorKind.Generic, model.home.uiState.value.genresError)
        assertTrue(model.storageFailed.value)
        assertEquals(before.rows, model.home.uiState.value.rows)
        assertEquals(before.hero, model.home.uiState.value.hero)
        val settings = f.graph.settings.value
        assertEquals(2, f.api.genreCalls)
        f.api.failGenresStorage = false
        model.retryStorage()
        runCurrent()
        assertNull(model.home.uiState.value.genresError)
        assertFalse(model.storageFailed.value)
        assertEquals(setOf(28, 18), model.home.uiState.value.genres.keys)
        assertEquals(settings, f.graph.settings.value)
        assertEquals(before.rows, model.home.uiState.value.rows)
        assertEquals(before.hero, model.home.uiState.value.hero)
        assertEquals(4, f.api.genreCalls)
        assertTrue(f.api.calls.none { it.startsWith("videos/") })
        f.graph.close()
    }

    @Test fun disposedOwnerCannotPublishLateStorageDiagnosticsAndGraphObservationContinues() = runTest {
        val f = MobileFixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        f.api.genreGate = gate
        f.api.failGenresStorage = true
        val owner = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val model = MobileHomeViewModel(f.graph, SavedStateHandle(), owner)
        runCurrent()
        owner.cancel()
        gate.complete(Unit)
        runCurrent()
        assertFalse(model.storageFailed.value)
        assertNull(model.home.uiState.value.genresError)
        f.settings.update { it.copy(haptics = false) }
        runCurrent()
        assertEquals(false, f.graph.settings.value?.haptics)
        f.graph.close()
    }
}
