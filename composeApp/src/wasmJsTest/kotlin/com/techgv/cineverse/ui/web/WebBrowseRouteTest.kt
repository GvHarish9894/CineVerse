package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.tmdb.*
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.presentation.BrowseSelection
import kotlin.test.*

class WebBrowseRouteTest {
    @Test fun everyCuratedChipAndPeopleKeepExactSource() {
        listOf("popular", "now_playing", "upcoming", "top_rated").zip(MovieList.entries).forEach { (list, value) ->
            assertEquals(BrowseSelection.Movies(value), parseWebRoute("/movies?list=$list").browseSelection())
        }
        listOf("popular", "airing_today", "on_the_air", "top_rated").zip(TvList.entries).forEach { (list, value) ->
            assertEquals(BrowseSelection.Tv(value), parseWebRoute("/tv?list=$list").browseSelection())
        }
        assertEquals(BrowseSelection.People, WebRoute.People.browseSelection())
    }

    @Test fun legacyFilteredCuratedUrlsAreTruthfullyGenericDiscover() {
        val upcoming = parseWebRoute("/movies?list=upcoming&genres=28&sort=vote_average.desc")
        assertEquals(BrowseSelection.Discover(MediaType.Movie, listOf(28), DiscoverSort.Rating), upcoming.browseSelection())
        val onAir = parseWebRoute("/tv?list=on_the_air&genres=18")
        assertEquals(BrowseSelection.Discover(MediaType.Tv, listOf(18)), onAir.browseSelection())
        assertEquals(BrowseSelection.Discover(MediaType.Tv), parseWebRoute("/tv?list=discover").browseSelection())
        assertEquals(BrowseSelection.Discover(MediaType.Movie), parseWebRoute("/movies?list=upcoming&sort=popularity.desc").browseSelection())
    }

    @Test fun mediaSpecificSortsAndExistingTvAliasesPreserveMeaning() {
        DiscoverSort.entries.forEach { sort ->
            listOf("movie", "tv").forEach { media ->
                val route = WebRoute.Browse(media, "discover", sort.webValue(media))
                assertEquals(sort, (parseWebRoute(route.url()).browseSelection() as BrowseSelection.Discover).sort)
            }
        }
        assertEquals(DiscoverSort.ReleaseDate, (parseWebRoute("/tv?sort=primary_release_date.desc").browseSelection() as BrowseSelection.Discover).sort)
        assertEquals(DiscoverSort.Title, (parseWebRoute("/tv?sort=title.asc").browseSelection() as BrowseSelection.Discover).sort)
        assertEquals(WebRoute.NotFound, parseWebRoute("/movies?sort=first_air_date.desc"))
    }

    @Test fun externalGenresMatchE1BoundaryAndCanonicalization() {
        val twenty = (1..20).toList()
        assertEquals(twenty, (parseWebRoute("/movies?genres=" + (twenty + 1).reversed().joinToString(",")) as WebRoute.Browse).genres)
        listOf("1000000", "0", "-1", "1,,2", "", (1..21).joinToString(",")).forEach {
            assertEquals(WebRoute.NotFound, parseWebRoute("/movies?genres=$it"), it)
        }
        assertEquals(listOf(999999), (parseWebRoute("/tv?genres=999999") as WebRoute.Browse).genres)
        assertEquals(WebRoute.NotFound, parseWebRoute(WebRoute.Browse("invalid").url()))
    }

    @Test fun filterHistoryRefreshBackForwardKeepUrlSelectionAndSavedScroll() {
        val history = FakeHistory("/movies?list=upcoming")
        val nav = WebNavigation(history)
        val curated = nav.entry.value
        curated.route.browseSelection().saveTo(curated)
        curated.putInt("browse.index", 9); curated.putInt("browse.offset", 37); curated.putInt("browse.page", 2)
        val filtered = WebRoute.Browse("movie", "discover", "title.asc", listOf(28))
        nav.navigate(filtered)
        nav.entry.value.route.browseSelection().saveTo(nav.entry.value)
        nav.entry.value.putInt("browse.index", 3)
        history.back()
        assertEquals(BrowseSelection.Movies(MovieList.Upcoming), nav.entry.value.route.browseSelection())
        assertEquals(9, nav.entry.value.getInt("browse.index"))
        assertEquals(37, nav.entry.value.getInt("browse.offset"))
        assertEquals(2, nav.entry.value.getInt("browse.page"))
        history.forward()
        assertEquals(filtered, nav.entry.value.route)
        assertEquals(3, nav.entry.value.getInt("browse.index"))
        nav.close()
        val refreshed = WebNavigation(history)
        assertEquals(filtered.browseSelection(), BrowseSelection.restoreFrom(refreshed.entry.value))
        assertEquals(3, refreshed.entry.value.getInt("browse.index"))
        refreshed.close()
    }

    @Test fun invalidSavedScalarsShowInvalidStateWithoutOverwritingHistory() {
        listOf("not json", "{\"browse.index\":\"-1\"}", "{\"browse.offset\":\"oops\"}", "{\"browse.page\":\"501\"}",
            "{\"productBrowse.source\":\"Bogus\"}", "{\"productBrowse.sort\":\"Popularity\"}",
            "{\"productBrowse.source\":\"Movies\",\"productBrowse.value\":\"Bogus\"}",
            "{\"productBrowse.source\":\"People\"}").forEach { saved ->
            val history = FakeHistory("/movies")
            history.replace(history.url, saved)
            val nav = WebNavigation(history)
            assertEquals(WebRoute.NotFound, nav.entry.value.route, saved)
            assertEquals(saved, history.saved)
            assertEquals("/movies", history.url)
            nav.close()
        }
    }

    @Test fun invalidSavedDiscoverAndContradictorySelectionNeverReachHolder() {
        val bad = listOf(
            "{\"productBrowse.source\":\"Discover\",\"productBrowse.value\":\"Movie\",\"productBrowse.genres\":\"1000000\",\"productBrowse.sort\":\"Popularity\"}",
            "{\"productBrowse.source\":\"People\",\"productBrowse.genres\":\"1\"}")
        bad.forEach { saved ->
            val history = FakeHistory("/people").apply { replace(url, saved) }
            val nav = WebNavigation(history)
            assertEquals(WebRoute.NotFound, nav.entry.value.route)
            assertEquals(saved, history.saved)
            nav.close()
        }
    }
}
