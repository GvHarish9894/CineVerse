package com.techgv.cineverse.ui.mobile

import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.tmdb.AccountObjectId
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MobileLibraryViewModelTest {
    private val account = AuthSession(AccountObjectId("library-account"), "Account", 1)

    @Test fun startsInactiveAndUnknownIsNotGuestEvenWhenRepositoryEmitsGuest() = runTest {
        val f = MobileLibraryFixture(backgroundScope)
        f.source.tryEmit(null)
        val model = MobileLibraryViewModel(f.base.graph, f.repository, SavedStateHandle(), backgroundScope)
        runCurrent()
        assertEquals(0, f.sessionReads)
        model.setActive(true); runCurrent()
        assertNull(model.state.value.library)
        assertEquals(0, f.sessionReads)
        f.base.auth.emissions.tryEmit(null); runCurrent()
        val state = assertNotNull(model.state.value.library)
        assertTrue(state.sourceIdentityKnown)
        assertNull(state.sourceIdentity)
        assertFalse(state.signedIn)
        assertEquals(SectionState.Empty, state.movies.section)
        f.base.graph.close()
    }

    @Test fun fullSourceIdentityMustMatchAuthoritativeGenerationAndStaleCallbacksCannotDispatch() = runTest {
        val f = MobileLibraryFixture(backgroundScope)
        f.identity(account)
        val model = MobileLibraryViewModel(f.base.graph, f.repository, SavedStateHandle(), backgroundScope)
        model.setActive(true); runCurrent()
        val old = model.state.value
        assertNotNull(old.library)
        val newer = account.copy(sessionGeneration = 2)
        f.base.auth.emissions.tryEmit(newer); runCurrent()
        assertNull(model.state.value.library) // Repository still carries generation 1.
        var dispatched = 0
        model.open(old.context, libraryFixtureEntry(MediaType.Movie).title.ref, MobileNavigator { dispatched++ })
        model.explore(old.context) { dispatched++ }
        model.signIn(old.context) { dispatched++ }
        model.selectTab(old.context, LibraryTab.Rated)
        assertEquals(0, dispatched)
        f.source.tryEmit(newer); runCurrent()
        assertEquals(newer, assertNotNull(model.state.value.library).sourceIdentity)
        assertEquals(LibraryTab.Watchlist, model.state.value.library?.tab)
        f.base.graph.close()
    }

    @Test fun malformedTabPagingScrollAndContradictoryPairsRemainUntouchedWithoutReads() = runTest {
        val cases = listOf(
            mapOf("library.tab" to 1), mapOf("library.tab" to "Unknown"),
            mapOf("mobileLibrary.Watchlist.moviePage" to "2"), mapOf("mobileLibrary.Watchlist.tvPage" to 501),
            mapOf("mobileLibrary.Rated.moviePage" to 0), mapOf("mobileLibrary.Watchlist.index" to -1, "mobileLibrary.Watchlist.offset" to 0),
            mapOf("mobileLibrary.Watchlist.index" to 2), mapOf("mobileLibrary.Rated.offset" to 0),
            mapOf("mobileLibrary.Favourites.index" to false, "mobileLibrary.Favourites.offset" to 0),
        )
        cases.forEach { raw ->
            val f = MobileLibraryFixture(backgroundScope)
            f.identity(account)
            val saved = SavedStateHandle(raw)
            val model = MobileLibraryViewModel(f.base.graph, f.repository, saved, backgroundScope)
            model.setActive(true); model.retryStorage(); runCurrent()
            assertTrue(model.state.value.invalid)
            assertNull(model.state.value.library)
            assertEquals(0, f.sessionReads)
            assertTrue(f.calls.isEmpty())
            assertEquals(raw, saved.keys().associateWith { saved.get<Any?>(it) })
            f.base.graph.close()
        }
    }

    @Test fun independentPagingFailureRequiresExplicitRetryAndCap500UsesActualCompletion() = runTest {
        val f = MobileLibraryFixture(backgroundScope)
        f.identity(account)
        val model = MobileLibraryViewModel(f.base.graph, f.repository, SavedStateHandle(), backgroundScope)
        model.setActive(true); runCurrent()
        val token = model.state.value.context
        f.failPage = MediaType.Movie to 2
        model.loadMore(token, MediaType.Movie); model.loadMore(token, MediaType.Tv); runCurrent()
        assertEquals(1, model.state.value.library?.movies?.loadedPage)
        assertEquals(2, model.state.value.library?.tv?.loadedPage)
        repeat(3) { model.loadMore(token, MediaType.Movie); runCurrent() }
        assertEquals(1, f.calls.count { it.second == MediaType.Movie && it.third == 2 })
        f.failPage = null
        model.retry(token, MediaType.Movie); runCurrent()
        assertEquals(2, model.state.value.library?.movies?.loadedPage)
        f.lastPage = 501
        repeat(498) { model.loadMore(token, MediaType.Tv); runCurrent() }
        assertEquals(500, model.state.value.library?.tv?.loadedPage)
        assertFalse(assertNotNull(model.state.value.library).tv.hasMore)
        model.loadMore(token, MediaType.Tv); runCurrent()
        assertTrue(f.calls.none { it.third > 500 })
        assertEquals(0, f.writes)
        f.base.graph.close()
    }

    @Test fun reentryAndTabReturnRestoreActualPageTargetsBeforeSavedScrollWithoutWrites() = runTest {
        val f = MobileLibraryFixture(backgroundScope)
        f.identity(account)
        val saved = SavedStateHandle()
        val model = MobileLibraryViewModel(f.base.graph, f.repository, saved, backgroundScope)
        model.setActive(true); runCurrent()
        var token = model.state.value.context
        model.loadMore(token, MediaType.Movie); model.loadMore(token, MediaType.Tv); runCurrent()
        model.saveScroll(token, 5, 17)
        model.selectTab(token, LibraryTab.Rated); runCurrent()
        token = model.state.value.context
        model.selectTab(token, LibraryTab.Watchlist); runCurrent()
        assertTrue(model.state.value.scrollReady)
        assertEquals(2, model.state.value.library?.movies?.loadedPage)
        assertEquals(2, model.state.value.library?.tv?.loadedPage)
        assertEquals(5, model.state.value.position.index)
        assertEquals(17, model.state.value.position.offset)
        model.setActive(false)
        val old = model.state.value.context
        assertNull(model.state.value.library)
        model.loadMore(old, MediaType.Movie)
        model.setActive(true); runCurrent()
        assertTrue(model.state.value.scrollReady)
        assertEquals(2, model.state.value.library?.movies?.loadedPage)
        assertEquals(5, model.state.value.position.index)
        assertEquals(2, saved.get<Int>("mobileLibrary.Watchlist.moviePage"))
        assertEquals(0, f.writes)
        assertEquals(0, f.base.store.edits)
        f.base.graph.close()
    }

    @Test fun restorationStopsOnPageErrorAndDoesNotSaveClampedScrollOrRetryAutomatically() = runTest {
        val f = MobileLibraryFixture(backgroundScope)
        f.identity(account)
        val model = MobileLibraryViewModel(f.base.graph, f.repository, SavedStateHandle(), backgroundScope)
        model.setActive(true); runCurrent()
        model.loadMore(model.state.value.context, MediaType.Movie); runCurrent()
        model.saveScroll(model.state.value.context, 5, 21)
        model.setActive(false)
        f.failPage = MediaType.Movie to 2
        model.setActive(true); runCurrent()
        assertFalse(model.state.value.scrollReady)
        assertEquals(5, model.state.value.position.index)
        model.saveScroll(model.state.value.context, 0, 0)
        assertEquals(5, model.state.value.position.index)
        val calls = f.calls.size
        runCurrent(); assertEquals(calls, f.calls.size)
        f.failPage = null
        model.retry(model.state.value.context, MediaType.Movie); runCurrent()
        assertTrue(model.state.value.scrollReady)
        assertEquals(2, model.state.value.library?.movies?.loadedPage)
        f.base.graph.close()
    }

    @Test fun identityChangeDropsOldPagePlanAndProcessRecreationDoesNotClaimUnprovedDeepScroll() = runTest {
        val f = MobileLibraryFixture(backgroundScope)
        f.identity(account)
        val saved = SavedStateHandle()
        val model = MobileLibraryViewModel(f.base.graph, f.repository, saved, backgroundScope)
        model.setActive(true); runCurrent()
        model.loadMore(model.state.value.context, MediaType.Movie); runCurrent()
        model.saveScroll(model.state.value.context, 8, 9)
        f.identity(account.copy(sessionGeneration = 2)); runCurrent()
        assertEquals(1, model.state.value.library?.movies?.loadedPage)
        assertEquals(0, model.state.value.position.index)
        model.setActive(false)
        val recreated = MobileLibraryViewModel(f.base.graph, f.repository, SavedStateHandle(mapOf(
            "library.tab" to "Rated", "mobileLibrary.Rated.moviePage" to 4,
            "mobileLibrary.Rated.index" to 20, "mobileLibrary.Rated.offset" to 30,
        )), backgroundScope)
        recreated.setActive(true); runCurrent()
        assertEquals(LibraryTab.Rated, recreated.state.value.library?.tab)
        assertEquals(1, recreated.state.value.library?.movies?.loadedPage)
        assertEquals(0, recreated.state.value.position.index)
        f.base.graph.close()
    }

    @Test fun pageGuestAndSessionStorageFailuresMaskContentAndRestartActualReadersOnlyOnRecovery() = runTest {
        val f = MobileLibraryFixture(backgroundScope)
        f.identity(account)
        val model = MobileLibraryViewModel(f.base.graph, f.repository, SavedStateHandle(), backgroundScope)
        model.setActive(true); runCurrent()
        f.storagePage = MediaType.Movie to 2
        model.loadMore(model.state.value.context, MediaType.Movie); runCurrent()
        assertTrue(model.state.value.failed)
        assertNull(model.state.value.library)
        val reads = f.sessionReads
        f.storagePage = null; runCurrent()
        assertEquals(reads, f.sessionReads)
        model.retryStorage(); runCurrent()
        assertNotNull(model.state.value.library)
        assertTrue(f.sessionReads > reads)
        f.sessionFailure = true
        f.source.tryEmit(account.copy(sessionGeneration = 3)); runCurrent()
        assertTrue(model.state.value.failed)
        assertNull(model.state.value.library)
        f.sessionFailure = false
        f.source.tryEmit(account)
        model.retryStorage(); runCurrent()
        assertNotNull(model.state.value.library)
        f.identity(null); runCurrent()
        f.guest.fail = true
        f.guest.rows.value = listOf(com.techgv.cineverse.data.library.GuestLibraryRow(MediaRef(MediaType.Movie, 1), LibraryKind.WATCHLIST, null, 0, "{}"))
        runCurrent()
        assertTrue(model.state.value.failed)
        assertNull(model.state.value.library)
        f.guest.fail = false
        f.guest.rows.value = emptyList()
        model.retryStorage(); runCurrent()
        assertEquals(SectionState.Empty, model.state.value.library?.movies?.section)
        assertEquals(0, f.writes)
        assertEquals(0, f.guest.writes)
        f.base.graph.close()
    }

    @Test fun equalSettingsRecoveryRecreatesReaderAndBackgroundCompletionNeverPublishes() = runTest {
        val f = MobileLibraryFixture(backgroundScope)
        f.identity(account)
        val model = MobileLibraryViewModel(f.base.graph, f.repository, SavedStateHandle(), backgroundScope)
        model.setActive(true); runCurrent()
        val preferences = f.base.graph.settings.value
        f.base.store.fail = true
        f.base.store.values.value = mapOf("library-owned" to "failure")
        runCurrent()
        assertTrue(model.state.value.failed)
        assertNull(model.state.value.library)
        f.base.store.fail = false
        val reads = f.sessionReads
        model.retryStorage(); runCurrent()
        assertEquals(preferences, f.base.graph.settings.value)
        assertTrue(f.sessionReads > reads)
        assertNotNull(model.state.value.library)
        f.gate = CompletableDeferred()
        model.loadMore(model.state.value.context, MediaType.Movie); runCurrent()
        model.setActive(false)
        f.gate!!.complete(Unit); runCurrent()
        assertNull(model.state.value.library)
        f.gate = null
        model.setActive(true); runCurrent()
        f.base.settings.update { it.copy(language = "hi-IN") }; runCurrent()
        assertNotNull(model.state.value.library)
        assertEquals(0, f.writes)
        f.base.graph.close()
    }

    @Test fun observableGuestChangesRemainLiveAcrossEntryWithoutComponentWrites() = runTest {
        val f = MobileLibraryFixture(backgroundScope)
        f.identity(null)
        val model = MobileLibraryViewModel(f.base.graph, f.repository, SavedStateHandle(), backgroundScope)
        model.setActive(true); runCurrent()
        assertEquals(SectionState.Empty, model.state.value.library?.movies?.section)
        f.guest.rows.value = listOf(com.techgv.cineverse.data.library.GuestLibraryRow(
            MediaRef(MediaType.Movie, 42), LibraryKind.WATCHLIST, null, 0, """{"title":"Live guest"}"""))
        runCurrent()
        val entries = (assertNotNull(model.state.value.library).movies.section as SectionState.Content).value
        assertEquals("Live guest", entries.single().title.title)
        model.setActive(false)
        f.guest.rows.value = emptyList()
        model.setActive(true); runCurrent()
        assertEquals(SectionState.Empty, model.state.value.library?.movies?.section)
        model.close()
        val reads = f.sessionReads
        model.setActive(true); model.retryStorage(); runCurrent()
        assertEquals(reads, f.sessionReads)
        assertNull(model.state.value.library)
        assertEquals(0, f.guest.writes)
        f.base.graph.close()
    }

    @Test fun authoritativeIdentityFailureMasksGuestUntilActualEqualIdentityRecovery() = runTest {
        val f = MobileLibraryFixture(backgroundScope)
        f.base.graph.close()
        var identityFailure = false
        val auth = object : com.techgv.cineverse.data.auth.AuthRepository by f.base.auth {
            override val session = flow {
                f.base.auth.session.collect {
                    if (identityFailure) throw com.techgv.cineverse.data.storage.StorageException("owned identity read")
                    emit(it)
                }
            }
        }
        val graph = MobileApplication(f.base.catalog, f.base.generation, f.base.settings, auth,
            f.repository, f.base.recent, backgroundScope)
        f.identity(null)
        val model = MobileLibraryViewModel(graph, f.repository, SavedStateHandle(), backgroundScope)
        model.setActive(true); runCurrent()
        val old = model.state.value.context
        assertNotNull(model.state.value.library)
        identityFailure = true
        f.base.auth.emissions.tryEmit(account); runCurrent()
        assertTrue(graph.identityFailed.value)
        assertNull(model.state.value.library)
        assertTrue(model.state.value.failed)
        var signIns = 0
        model.signIn(old) { signIns++ }
        model.retryStorage(old)
        assertEquals(0, signIns)
        assertTrue(graph.identityFailed.value)
        identityFailure = false
        f.base.auth.emissions.tryEmit(null)
        model.retryStorage(); runCurrent()
        assertFalse(graph.identityFailed.value)
        assertFalse(model.state.value.failed)
        assertNotNull(model.state.value.library)
        graph.close()
    }

    @Test fun nonCooperativeOldAccountCompletionCannotReplaceNewGenerationOrDispatch() = runTest {
        val f = MobileLibraryFixture(backgroundScope)
        f.identity(account)
        val model = MobileLibraryViewModel(f.base.graph, f.repository, SavedStateHandle(), backgroundScope)
        model.setActive(true); runCurrent()
        val old = model.state.value.context
        val pending = CompletableDeferred<Unit>()
        f.nonCooperative = true
        f.gate = pending
        model.loadMore(old, MediaType.Movie); runCurrent()
        f.gate = null
        val next = account.copy(sessionGeneration = 2)
        f.identity(next); runCurrent()
        assertEquals(next, model.state.value.library?.sourceIdentity)
        pending.complete(Unit); runCurrent()
        assertEquals(next, model.state.value.library?.sourceIdentity)
        assertEquals(1, model.state.value.library?.movies?.loadedPage)
        var navigation = 0
        model.open(old, libraryFixtureEntry(MediaType.Movie).title.ref, MobileNavigator { navigation++ })
        assertEquals(0, navigation)
        f.base.graph.close()
    }

    @Test fun onlyCurrentLoadedTitleCanNavigateAndGuestCtasRequireVisibleConfirmedSource() = runTest {
        val f = MobileLibraryFixture(backgroundScope)
        f.identity(account)
        val model = MobileLibraryViewModel(f.base.graph, f.repository, SavedStateHandle(), backgroundScope)
        model.setActive(true); runCurrent()
        var destination: MobileDestination? = null
        val token = model.state.value.context
        val ref = libraryFixtureEntry(MediaType.Tv).title.ref
        model.open(token, ref, MobileNavigator { destination = it })
        assertEquals(MobileDestination.Detail(ref), destination)
        destination = null
        model.open(token, MediaRef(MediaType.Movie, 999), MobileNavigator { destination = it })
        assertNull(destination)
        var ctas = 0
        model.signIn(token) { ctas++ }
        assertEquals(0, ctas)
        f.identity(null); runCurrent()
        model.signIn(model.state.value.context) { ctas++ }
        model.explore(model.state.value.context) { ctas++ }
        assertEquals(2, ctas)
        model.setActive(false)
        model.explore(model.state.value.context) { ctas++ }
        assertEquals(2, ctas)
        f.base.graph.close()
    }
}
