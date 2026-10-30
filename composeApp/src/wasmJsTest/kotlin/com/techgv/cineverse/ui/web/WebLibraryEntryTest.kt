package com.techgv.cineverse.ui.web

import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.data.tmdb.TmdbError
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.presentation.*
import kotlin.test.*

class WebLibraryEntryTest {
    @Test fun realUrlsMapAllTabsAndAbsentCanonicalTabIsSeededOnlyAfterValidation() {
        listOf("watchlist" to LibraryTab.Watchlist, "favourites" to LibraryTab.Favourites, "ratings" to LibraryTab.Rated).forEach { (url, tab) ->
            val entry = WebEntry(parseWebRoute("/library?tab=$url"), mutableMapOf())
            assertEquals(tab, validateWebLibraryEntry(entry).tab)
            WebLibraryFixture().use { fixture -> fixture.owner(entry).use {
                assertEquals(tab.name, entry.getString("library.tab"))
                assertEquals(1, fixture.factoryCalls)
                assertEquals(2, fixture.activeGuest)
            } }
        }
    }

    @Test fun malformedContradictoryAndWrongTypedHistoryNeverConstructHolderOrFetchAndPreserveRaw() {
        val invalid = listOf(
            mutableMapOf("library.tab" to "Rated"), mutableMapOf("library.tab" to "watchlist"), mutableMapOf("library.tab" to ""),
            mutableMapOf("library.movie.page" to "0"), mutableMapOf("library.movie.page" to "501"), mutableMapOf("library.movie.page" to "01"),
            mutableMapOf("library.tv.page" to "true"), mutableMapOf("library.tv.index" to "-1", "library.tv.offset" to "0"),
            mutableMapOf("library.movie.index" to "1"), mutableMapOf("library.movie.index" to "0", "library.movie.offset" to "1.5"),
            mutableMapOf("library.tv.page" to "2147483648"), mutableMapOf("library.movie.page" to "")
        )
        WebLibraryFixture().use { f ->
            invalid.forEach { raw ->
                val before = raw.toMap()
                assertFailsWith<IllegalArgumentException> { f.owner(webLibraryEntry(values = raw)) }
                assertEquals(before, raw)
            }
            assertFailsWith<IllegalArgumentException> { f.owner(WebEntry(WebRoute.Library("invalid"), mutableMapOf())) }
            assertFailsWith<IllegalArgumentException> { f.owner(WebEntry(WebRoute.NotFound, mutableMapOf())) }
            assertEquals(0, f.factoryCalls); assertEquals(0, f.guestSubscriptions); assertTrue(f.reads.isEmpty())
        }
        // WebNavigation is the existing primitive-type trust boundary; it rejects non-string saved values.
        val history = object : WebHistory {
            override val url = "/library?tab=watchlist"
            override val saved = """{"library.tab":true}"""
            override fun push(url: String, saved: String) = error("No history writes")
            override fun replace(url: String, saved: String) = error("Invalid raw must stay untouched")
            override fun listen(onPop: () -> Unit): () -> Unit = {}
        }
        val nav = WebNavigation(history)
        assertEquals(WebRoute.NotFound, nav.entry.value.route)
        nav.close()
    }

    @Test fun tabCountsAreKnownOnlyForBothCompleteFreshGroupsAndNeverInactiveTotals() {
        val one = LibraryGroup(SectionState.Content(listOf(com.techgv.cineverse.data.library.LibraryEntry(webLibraryTitle(MediaType.Movie, 7), null))))
        assertNull(libraryKnownCount(LibraryUiState(movies = one)))
        assertNull(libraryKnownCount(LibraryUiState(movies = one, tv = LibraryGroup(SectionState.Error(SectionErrorKind.Offline)))))
        assertEquals(1, libraryKnownCount(LibraryUiState(movies = one, tv = LibraryGroup(SectionState.Empty))))
        // A loaded first page with more to page, or a stale group, is not a total.
        assertNull(libraryKnownCount(LibraryUiState(movies = one.copy(hasMore = true), tv = LibraryGroup(SectionState.Empty))))
        assertNull(libraryKnownCount(LibraryUiState(movies = one, tv = LibraryGroup(SectionState.Empty, refreshError = SectionErrorKind.Offline))))
        assertEquals(WebRoute.Detail("movie", 7), webLibraryTitle(MediaType.Movie, 7).ref.libraryDetailRoute())
        assertEquals(WebRoute.Detail("tv", 82), webLibraryTitle(MediaType.Tv, 82).ref.libraryDetailRoute())
    }

    @Test fun guestStorageRetryResubscribesEqualReadRetainsOwnerAndNeverWrites() {
        WebLibraryFixture().use { f -> f.owner().use { owner ->
            assertEquals(2, libraryKnownCount(assertNotNull(owner.visibleState())))
            f.failGuest = true; f.guestRevision.value++
            assertEquals(0, f.activeGuest)
            assertNotNull(owner.visibleState())
            assertNotNull(owner.visibleState()!!.movies.refreshError)
            val subscriptions = f.guestSubscriptions
            f.failGuest = false
            owner.retryStorage()
            assertEquals(1, f.upstreamRetries)
            assertTrue(f.guestSubscriptions > subscriptions)
            assertEquals(2, f.activeGuest)
            assertNull(owner.visibleState()!!.movies.refreshError)
            assertEquals(1, f.factoryCalls)
            assertTrue(f.reads.isEmpty())
        } }
    }

    @Test fun accountPagesAndErrorsAreIndependentAndFailureNeverAutoRetries() {
        WebLibraryFixture().use { f ->
            f.signIn()
            f.owner().use { owner ->
                f.failPage = MediaType.Movie to 2
                owner.loadMore(MediaType.Movie)
                assertEquals(SectionErrorKind.Offline, owner.visibleState()!!.movies.moreError)
                assertEquals(1, owner.pages.value[MediaType.Movie])
                val failures = f.reads.count { it.second == MediaType.Movie && it.third == 2 }
                repeat(3) { owner.loadMore(MediaType.Movie) }
                assertEquals(failures, f.reads.count { it.second == MediaType.Movie && it.third == 2 })
                owner.loadMore(MediaType.Tv)
                assertEquals(2, owner.pages.value[MediaType.Tv])
                assertEquals(1, owner.pages.value[MediaType.Movie])
                f.failPage = null; owner.retry(MediaType.Movie)
                assertEquals(2, owner.pages.value[MediaType.Movie])
                assertNull(owner.visibleState()!!.movies.moreError)
                f.failPage = MediaType.Movie to 1; owner.revalidate()
                assertEquals(2, (owner.visibleState()!!.movies.section as SectionState.Content).value.size)
                assertNotNull(owner.visibleState()!!.movies.refreshError)
                f.failPage = null; owner.retryStorage()
                assertNull(owner.visibleState()!!.movies.refreshError)
                assertEquals(2, owner.pages.value[MediaType.Movie])
                assertEquals(1, f.factoryCalls)
                assertEquals(0, f.guestSubscriptions)
            }
        }
    }

    @Test fun unknownUncorrelatedSameNameDifferentAccountAndNewGenerationHideRows() {
        WebLibraryFixture().use { f -> f.signIn(); f.owner().use { owner ->
            assertNotNull(owner.visibleState())
            f.authoritative = WebSessionState.Checking
            assertNull(owner.visibleState())
            f.authoritative = WebSessionState.Failed(TmdbError.Network)
            assertNull(owner.visibleState())
            f.authoritative = WebSessionState.Ready(null)
            assertNull(owner.visibleState())
            f.authoritative = WebSessionState.Ready(webLibrarySession("another-account"))
            assertNull(owner.visibleState())
            f.authoritative = WebSessionState.Ready(webLibrarySession(generation = Long.MAX_VALUE))
            assertNull(owner.visibleState())
        } }
        listOf(null, WebLibraryResultIdentity.Guest,
            WebLibraryResultIdentity.Account(webLibrarySession("another-account")),
            WebLibraryResultIdentity.Account(webLibrarySession(generation = Long.MAX_VALUE))).forEach { proof ->
            WebLibraryFixture().use { f -> f.signIn(); f.producedIdentity = proof
                f.owner().use { assertNull(it.visibleState()) }
            }
        }
    }

    @Test fun disposalCancelsPendingPagesAndRetriesCannotReviveOwner() {
        WebLibraryFixture().use { f ->
            f.signIn()
            val owner = f.owner()
            f.waitPage = MediaType.Movie to 2
            owner.loadMore(MediaType.Movie)
            assertTrue(owner.visibleState()!!.movies.loadingMore)
            owner.close()
            assertEquals(1, f.canceled)
            val reads = f.reads.size
            f.gate.complete(Unit)
            owner.retryStorage(); owner.loadMore(MediaType.Movie); owner.retry(MediaType.Tv); owner.revalidate()
            assertNull(owner.visibleState()); assertEquals(reads, f.reads.size); assertEquals(0, f.upstreamRetries)
        }
    }
}
