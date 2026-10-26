package com.techgv.cineverse.ui.web

import com.techgv.cineverse.data.tmdb.SearchFacet
import kotlin.test.*

class WebSearchRouteTest {
    @Test fun allFacetsRoundTripAndLongRawInputRemainsUrlOwned() {
        SearchFacet.entries.forEach { facet ->
            val route = WebRoute.Search("  " + "x".repeat(201) + "  ", facet.webValue())
            assertTrue(route.representable())
            assertEquals(facet, route.searchFacet())
            val nav = WebNavigation(FakeHistory(route.url()))
            assertEquals(route.query, nav.entry.value.getString("productSearch.query"))
            assertEquals(facet.name, nav.entry.value.getString("productSearch.facet"))
            nav.close()
        }
        assertFalse(WebRoute.Search("x\u0000y").representable())
        assertFalse(WebRoute.Search("界".repeat(1000)).representable())
    }

    @Test fun replacementsKeepIdentityAndOnlySourceChangesResetSearchPosition() {
        val history = FakeHistory("/search?q=film&facet=movie")
        val nav = WebNavigation(history)
        val entry = nav.entry.value
        entry.putInt("search.page", 3); entry.putInt("search.index", 12); entry.putInt("search.offset", 22)
        entry.putString("unrelated", "keep")
        nav.navigate(WebRoute.Search(" film ", "movie"), true)
        assertSame(entry, nav.entry.value)
        assertEquals(3, entry.getInt("search.page"))
        assertEquals(12, entry.getInt("search.index"))
        nav.navigate(WebRoute.Search(" film ", "tv"), true)
        assertSame(entry, nav.entry.value)
        assertNull(entry.getInt("search.page")); assertNull(entry.getInt("search.offset"))
        assertEquals("Tv", entry.getString("productSearch.facet"))
        assertEquals("keep", entry.getString("unrelated"))
        nav.navigate(WebRoute.Search("new", "tv"), true)
        assertEquals("new", entry.getString("productSearch.query"))
        val reload = WebNavigation(history)
        assertEquals(WebRoute.Search("new", "tv"), reload.entry.value.route)
        assertEquals(0, history.pushes)
        reload.close(); nav.close()
    }

    @Test fun pushPopReloadRestoreIndependentSearchScalarsAndDetachOldEntries() {
        val history = FakeHistory("/search?q=one&facet=person")
        val nav = WebNavigation(history)
        val first = nav.entry.value
        first.putInt("search.page", 2); first.putInt("search.index", 5)
        nav.navigate(WebRoute.Search("two", "movie"))
        assertNotSame(first, nav.entry.value)
        history.back()
        assertNotSame(first, nav.entry.value)
        assertEquals(WebRoute.Search("one", "person"), nav.entry.value.route)
        assertEquals(2, nav.entry.value.getInt("search.page"))
        assertEquals(5, nav.entry.value.getInt("search.index"))
        first.putString("productSearch.query", "stale")
        assertFalse(history.saved.contains("stale"))
        history.forward()
        assertEquals(WebRoute.Search("two", "movie"), nav.entry.value.route)
        nav.close()
    }

    @Test fun contradictoryWrongTypeUnknownAndUnboundedHistoryAreRejectedWithoutReset() {
        listOf(
            "{\"productSearch.query\":\"other\"}", "{\"productSearch.facet\":\"Tv\"}",
            "{\"productSearch.query\":1}", "{\"productSearch.facet\":null}",
            "{\"productSearch.facet\":\"invalid\"}", "{\"productSearch.unknown\":\"x\"}",
            "{\"search.page\":\"501\"}", "{\"search.index\":\"100001\"}", "{\"search.offset\":\"-1\"}",
            "[]", "not json",
        ).forEach { saved ->
            val history = FakeHistory("/search?q=one&facet=movie")
            history.replace(history.url, saved)
            val nav = WebNavigation(history)
            assertEquals(WebRoute.NotFound, nav.entry.value.route, saved)
            assertEquals(saved, history.saved)
            assertEquals("/search?q=one&facet=movie", history.url)
            nav.close()
        }
    }

    @Test fun failedHistoryReplaceDoesNotPublishRouteOrScalars() {
        val delegate = FakeHistory("/search?q=one")
        var fail = false
        val history = object : WebHistory by delegate {
            override fun replace(url: String, saved: String) { if (fail) error("fixture write failed"); delegate.replace(url, saved) }
        }
        val nav = WebNavigation(history)
        val entry = nav.entry.value
        val saved = history.saved
        fail = true
        assertFailsWith<IllegalStateException> { nav.navigate(WebRoute.Search("two"), true) }
        assertSame(entry, nav.entry.value)
        assertEquals(WebRoute.Search("one"), entry.route)
        assertEquals(saved, history.saved)
        assertEquals("one", entry.getString("productSearch.query"))
        nav.close()
    }
}
