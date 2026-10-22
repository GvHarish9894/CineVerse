package com.techgv.cineverse.presentation

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.PersonDetailDto
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §7.5 / D7 (moved from tvApp PersonViewModelTest). */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonStateHolderTest {
    private val dispatcher = StandardTestDispatcher()
    private val settings = MutableStateFlow(Settings(language = "ta-IN"))
    private val generation = GenerationCounter()
    private val calls = mutableListOf<String>()
    private var personGate: CompletableDeferred<Unit>? = null
    private val api = object : UnusedTmdbApi() {
        override suspend fun person(id: Int, language: String, append: String): PersonDetailDto {
            calls += language
            personGate?.await()
            return PersonDetailDto(id, name = "P", biography = if (language == "en-US") "English bio" else "")
        }
    }
    private val repo = catalog(api, generation, settings)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun holder(handle: SavedStateHandle = SavedStateHandle()) =
        PersonStateHolder(1, repo, settings, generation, SavedStateHandleStateSaver(handle))

    @Test fun blankLocalizedBiographyIsFilledOnceFromEnglishAndLabelled() = runTest(dispatcher) {
        val holder = holder()
        advanceUntilIdle()
        val person = (holder.person.value as SectionState.Content).value
        assertEquals("English bio", person.biography)
        assertTrue(person.biographyInEnglish)
        assertEquals(listOf("ta-IN", "en-US"), calls)
    }

    @Test fun appGenerationBumpWithoutASettingsEmissionReloadsTheDroppedResult() = runTest(dispatcher) {
        settings.value = Settings(language = "en-US")
        val gate = CompletableDeferred<Unit>().also { personGate = it }
        val holder = holder()
        advanceUntilIdle()
        assertEquals(SectionState.Loading, holder.person.value)
        generation.bump()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(holder.person.value is SectionState.Content)
        assertEquals(listOf("en-US", "en-US"), calls)
    }

    @Test fun biographyExpansionIsASavedScreenScalar() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val holder = holder(handle)
        assertFalse(holder.bioExpanded.value)
        holder.toggleBio()
        assertTrue(holder.bioExpanded.value)
        assertTrue(holder(handle).bioExpanded.value)
    }
}
