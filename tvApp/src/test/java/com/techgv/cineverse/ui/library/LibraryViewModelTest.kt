package com.techgv.cineverse.ui.library

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.library.accountSource
import com.techgv.cineverse.data.library.FakeAccountApi
import com.techgv.cineverse.data.library.FakeLibraryDao
import com.techgv.cineverse.data.library.guestSource
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.data.library.credentials
import com.techgv.cineverse.data.library.session
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.ui.components.SectionErrorKind
import com.techgv.cineverse.ui.components.SectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = FakeAccountApi()
    private val repo = LibraryRepository(
        MutableStateFlow(credentials().session()), guestSource(FakeLibraryDao()), accountSource(api),
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun LibraryViewModel.movieIds() = (state.value.movies.section as SectionState.Content).value.map { it.title.ref.id }

    @Test
    fun `entry revalidation reads past the warm list cache and a failed refresh keeps the items with an error`() = runTest(dispatcher) {
        api.watchlist += MediaRef(MediaType.Movie, 1)
        val vm = LibraryViewModel(SavedStateHandle(), repo)
        runCurrent() // the first load warms the 10-min list cache
        assertEquals(listOf(1), vm.movieIds())

        api.watchlist += MediaRef(MediaType.Movie, 2) // changed on TMDB; the cache still holds the old page
        vm.revalidate()
        runCurrent()
        assertEquals(listOf(1, 2), vm.movieIds())

        api.failListPage = 1
        vm.revalidate()
        runCurrent()
        assertEquals("stale items stay", listOf(1, 2), vm.movieIds())
        assertEquals(SectionErrorKind.Offline, vm.state.value.movies.refreshError)

        api.failListPage = null
        vm.retry(MediaType.Movie)
        runCurrent()
        assertEquals(null, vm.state.value.movies.refreshError)
    }
}
