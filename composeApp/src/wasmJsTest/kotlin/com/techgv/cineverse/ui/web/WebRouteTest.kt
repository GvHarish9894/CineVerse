package com.techgv.cineverse.ui.web

import kotlin.test.*

class WebRouteTest {
    @Test fun directUrlsRestoreTypedDestinationsAndCanonicalEncodedLinks() {
        val routes = listOf(WebRoute.Home, WebRoute.Browse("movie", "upcoming", "vote_average.desc", listOf(12, 28)),
            WebRoute.Browse("tv", "on_the_air"), WebRoute.People, WebRoute.Detail("movie", 603), WebRoute.Detail("tv", 1399),
            WebRoute.Person(287), WebRoute.Season(1399, 0), WebRoute.Search("東京 & love / ? # +", "person"),
            WebRoute.Library("favourites"), WebRoute.Settings, WebRoute.Trailer("aB_09-xYz12", "A & B"))
        routes.forEach { assertEquals(it, parseWebRoute(it.url()), it.url()) }
        assertEquals(WebRoute.Browse("movie", genres = listOf(12, 28)), parseWebRoute("/movies?genres=28%2C12%2C28"))
        assertEquals(WebRoute.Search("a b+c"), parseWebRoute("/search?q=a+b%2Bc"))
        assertEquals("/movie/3", parseWebRoute("/movie/0003").url())
    }

    @Test fun malformedUnknownAndAccountOperationUrlsAreSafeNotFound() {
        listOf("//evil.test", "/movie/0", "/movie/-1", "/movie/2147483648", "/movie/1.0", "/person/1/", "/tv/1/season/-1",
            "/tv/1/season/2147483648", "/movie/%2f", "/movies?list=delete", "/movies?sort=bad", "/movies?genres=1,,2", "/movies?genres=-1",
            "/search?q=%", "/search?q=%E0%A4", "/search?q=%00", "/search?q=a&q=b", "/search?facet=bad", "/search?unknown=x",
            "/library?tab=delete", "/settings?signout=true", "/api/auth/start", "/auth/callback", "/#delete", "/trailer/short", "/unknown",
            "/search?q=" + "a".repeat(8192)).forEach { assertEquals(WebRoute.NotFound, parseWebRoute(it), it.take(100)) }
    }

    @Test fun searchDoesNotInheritTvTwentyEightCharacterPolicy() {
        val query = "a long web search query ".repeat(5)
        assertEquals(WebRoute.Search(query), parseWebRoute(WebRoute.Search(query).url()))
    }

    @Test fun artworkRejectsUntrustedUrlsAndNeverUsesApiClient() {
        assertEquals("https://image.tmdb.org/t/p/w342/poster.jpg", webImage("/poster.jpg", "w342"))
        listOf(null, "https://evil.test/p.jpg", "//evil.jpg", "/../x.jpg", "/x.jpg?token=x").forEach { assertNull(webImage(it, "w342")) }
    }
}

internal class FakeHistory(initial: String = "/") : WebHistory {
    private data class Item(val url: String, val saved: String)
    private val items = mutableListOf(Item(initial, ""))
    private var index = 0
    private var pop: (() -> Unit)? = null
    var pushes = 0
    override val url get() = items[index].url
    override val saved get() = items[index].saved
    override fun push(url: String, saved: String) { while (items.lastIndex > index) items.removeAt(items.lastIndex); items += Item(url, saved); index++; pushes++ }
    override fun replace(url: String, saved: String) { items[index] = Item(url, saved) }
    override fun listen(onPop: () -> Unit): () -> Unit { pop = onPop; return { pop = null } }
    fun back() { if (index > 0) { index--; pop?.invoke() } }
    fun forward() { if (index < items.lastIndex) { index++; pop?.invoke() } }
}

class WebNavigationTest {
    @Test fun directEntryRefreshAndBackForwardRestoreIndependentScrollAndScalars() {
        val history = FakeHistory("/")
        val nav = WebNavigation(history)
        nav.entry.value.putInt("page.index", 4)
        nav.entry.value.putInt("page.offset", 65)
        nav.entry.value.putString("home.trend", "Day")
        nav.navigate(WebRoute.Detail("movie", 603))
        nav.entry.value.putInt("page.index", 2)
        history.back()
        assertEquals(WebRoute.Home, nav.entry.value.route)
        assertEquals(4, nav.entry.value.getInt("page.index"))
        assertEquals(65, nav.entry.value.getInt("page.offset"))
        assertEquals("Day", nav.entry.value.getString("home.trend"))
        history.forward()
        assertEquals(WebRoute.Detail("movie", 603), nav.entry.value.route)
        assertEquals(2, nav.entry.value.getInt("page.index"))
        nav.close()
        val refreshed = WebNavigation(history)
        assertEquals(WebRoute.Detail("movie", 603), refreshed.entry.value.route)
        assertEquals(2, refreshed.entry.value.getInt("page.index"))
        refreshed.close()
    }

    @Test fun typingReplacesHistoryWhileNavigationPushesAndDisposedEntriesCannotOverwriteIt() {
        val history = FakeHistory()
        val nav = WebNavigation(history)
        val old = nav.entry.value
        nav.navigate(WebRoute.Search("a"))
        nav.navigate(WebRoute.Search("a b"), replace = true)
        nav.navigate(WebRoute.Search("a b"), replace = true)
        assertEquals(1, history.pushes)
        old.putInt("page.index", 999)
        assertFalse(history.saved.contains("999"))
        history.back()
        assertEquals(WebRoute.Home, nav.entry.value.route)
        history.forward()
        assertEquals(WebRoute.Search("a b"), nav.entry.value.route)
        nav.close()
        history.back()
        assertEquals(WebRoute.Search("a b"), nav.entry.value.route)
        nav.navigate(WebRoute.Settings)
        assertEquals(1, history.pushes)
    }

    @Test fun corruptSavedStateIsNotATrustedHolderEnumOrNegativeScroll() {
        val history = FakeHistory()
        history.replace("/", "{\"home.trend\":\"Invalid\",\"page.index\":\"-5\"}")
        val nav = WebNavigation(history)
        assertNull(nav.entry.value.getString("home.trend"))
        assertNull(nav.entry.value.getInt("page.index"))
        nav.close()
        history.replace("/person/0", "not json")
        val invalid = WebNavigation(history)
        assertEquals(WebRoute.NotFound, invalid.entry.value.route)
        invalid.close()
    }
}
