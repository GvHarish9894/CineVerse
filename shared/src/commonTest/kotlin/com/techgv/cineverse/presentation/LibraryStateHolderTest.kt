package com.techgv.cineverse.presentation

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.library.GuestLibrarySource
import com.techgv.cineverse.data.library.LibraryRepository
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** §7.8 (moved from tvApp LibraryViewModelTest) plus guest groups and tab switching. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryStateHolderTest {
    private val dispatcher = StandardTestDispatcher()
    private val account = FakeAccountLibrary()
    private val identity = MutableStateFlow<AuthSession?>(session())
    private val guest = GuestLibrarySource(InMemoryGuestLibraryStore())
    private val repo = LibraryRepository(identity, guest, account)
    private val handle = SavedStateHandle()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun holder() = LibraryStateHolder(repo, SavedStateHandleStateSaver(handle))

    private fun LibraryGroup.ids() = (section as SectionState.Content).value.map { it.title.ref.id }

    @Test fun entryRevalidationReadsPastTheWarmListCacheAndAFailedRefreshKeepsTheItems() = runTest(dispatcher) {
        account.watchlist += MediaRef(MediaType.Movie, 1)
        val holder = holder()
        runCurrent() // the first load warms the list cache
        assertEquals(listOf(1), holder.state.value.movies.ids())
        assertEquals(SectionState.Empty, holder.state.value.tv.section) // the TV group is independent

        account.watchlist += MediaRef(MediaType.Movie, 2) // changed on TMDB; the cache still holds the old page
        holder.revalidate()
        runCurrent()
        assertEquals(listOf(1, 2), holder.state.value.movies.ids())

        account.failListPage = 1
        holder.revalidate()
        runCurrent()
        assertEquals(listOf(1, 2), holder.state.value.movies.ids(), "stale items stay")
        assertEquals(SectionErrorKind.Offline, holder.state.value.movies.refreshError)

        account.failListPage = null
        holder.retry(MediaType.Movie)
        runCurrent()
        assertNull(holder.state.value.movies.refreshError)
    }

    @Test fun guestTabsReadTheLocalLibraryAndTheTabIsSaved() = runTest(dispatcher) {
        identity.value = null
        guest.write(title(3), LibraryKind.FAVOURITE, LibraryState(true, false, true, null, emptySet()))
        val holder = holder()
        runCurrent()
        assertEquals(SectionState.Empty, holder.state.value.movies.section)
        holder.selectTab(LibraryTab.Favourites)
        runCurrent()
        assertEquals(listOf(3), holder.state.value.movies.ids())
        assertEquals(false, holder.state.value.signedIn)
        assertEquals(LibraryTab.Favourites, holder().state.value.tab) // restored from the same saved state
    }

    @Test fun aWriteRevalidatesTheSignedInLists() = runTest(dispatcher) {
        val holder = holder()
        runCurrent()
        assertEquals(SectionState.Empty, holder.state.value.movies.section)
        repo.toggleWatchlist(title(5), identity.value)
        runCurrent()
        assertEquals(listOf(5), holder.state.value.movies.ids())
    }
}
