package com.techgv.cineverse.ui.person

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.PersonDetailDto
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.data.tmdb.unusedTmdbApi
import com.techgv.cineverse.ui.nav.Route
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** TV adapter: saved scalar through the SavedStateHandle, screen copy (rules: shared PersonStateHolderTest). */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val settings = MutableStateFlow(Settings(language = "ta-IN"))
    private val generation = GenerationCounter()
    private val calls = mutableListOf<String>()
    private val api = object : TmdbApi by unusedTmdbApi {
        override suspend fun person(id: Int, language: String, append: String): PersonDetailDto {
            calls += language
            return PersonDetailDto(id, name = "P", biography = if (language == "en-US") "English bio" else "")
        }
    }
    private val repo = TmdbRepository(api, generation, settings = { settings.value })

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `biography expansion is a saved screen scalar`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = PersonViewModel(Route.Person(1), handle, repo, settings, generation)
        assertFalse(vm.bioExpanded.value)
        vm.toggleBio()
        assertTrue(PersonViewModel(Route.Person(1), handle, repo, settings, generation).bioExpanded.value)
    }

    @Test
    fun `born chip with deathday`() {
        assertEquals("Born Jan 2, 1950 – Mar 4, 2020", bornChip("1950-01-02", "2020-03-04"))
        assertEquals("Born Jan 2, 1950", bornChip("1950-01-02", null))
        assertEquals(null, bornChip(null, "2020-03-04"))
    }
}
