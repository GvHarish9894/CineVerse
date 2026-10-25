package com.techgv.cineverse.ui.web

import kotlinx.browser.window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class BrowserHistoryTest {
    @Test fun actualPopstateBackAndForwardRestoreDirectUrlAndEntryState() = runTest { withContext(Dispatchers.Default) {
        val history = BrowserHistory()
        val originalUrl = history.url
        val originalSaved = history.saved
        history.replace("/movie/603", "{}")
        val nav = WebNavigation(history)
        try {
            assertEquals(WebRoute.Detail("movie", 603), nav.entry.value.route)
            nav.entry.value.putInt("page.offset", 80)
            nav.navigate(WebRoute.Search("web & history"))
            window.history.back()
            withTimeout(5_000) { nav.entry.first { it.route == WebRoute.Detail("movie", 603) } }
            assertEquals(80, nav.entry.value.getInt("page.offset"))
            window.history.forward()
            withTimeout(5_000) { nav.entry.first { it.route == WebRoute.Search("web & history") } }
            assertEquals("/search?q=web%20%26%20history", history.url)
        } finally {
            nav.close()
            history.replace(originalUrl, originalSaved)
        }
    } }
}
