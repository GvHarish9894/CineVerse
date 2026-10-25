@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.data.storage.BrowserStorageFailure
import com.techgv.cineverse.data.tmdb.SearchFacet
import com.techgv.cineverse.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class WebSearchTest {
    private fun SemanticsNodeInteraction.assertInput(value: String) = assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))

    private fun ready(writes: Boolean = false) = WebRuntimeUiTest.Fixture(writes).apply {
        failSession = false; failSettings = false; withTitles = true
    }

    @Test fun unavailableDesktopSearchHeaderPreservesTvInputUntilExplicitRecoveredSubmission() = unavailableSearchHeader(1100.dp)

    @Test fun unavailablePhoneSearchHeaderPreservesTvInputUntilExplicitRecoveredSubmission() = unavailableSearchHeader(390.dp)

    private fun unavailableSearchHeader(width: Dp) = runComposeUiTest {
        val f = ready(true).apply { failSettings = true }
        val history = FakeHistory("/search?q=film&facet=tv")
        try {
            setContent { Box(Modifier.width(width)) { WebApp(f.runtime, f.catalog, f.generation, f.settings, f.recent, history) } }
            val field = onNodeWithTag("header-search-input")
            val saved = history.saved
            field.assertIsNotEnabled().assertInput("film")
            onNodeWithText("Search is unavailable until saved settings are loaded. Your URL query and facet are unchanged.").assertExists()
            onNodeWithTag("search-input").assertDoesNotExist()
            field.performTouchInput { click() }
            field.assertIsNotFocused()
            // Disabled platform semantics must not accept a visible draft or a stale IME submission.
            field.fetchSemanticsNode().config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString("arrival"))
            field.fetchSemanticsNode().config.getOrNull(SemanticsActions.PerformImeAction)?.action?.invoke()
            field.assertInput("film")
            assertEquals("/search?q=film&facet=tv", history.url)
            assertEquals(saved, history.saved)
            assertEquals(0, f.edits)

            runOnIdle { f.runtime.start() }
            waitUntil { f.runtime.storageFailure.value == BrowserStorageFailure.Blocked }
            field.assertIsNotEnabled().assertInput("film")
            field.fetchSemanticsNode().config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString("arrival"))
            field.fetchSemanticsNode().config.getOrNull(SemanticsActions.PerformImeAction)?.action?.invoke()
            field.performKeyInput { pressKey(Key.Enter) }
            mainClock.advanceTimeBy(600)
            field.assertInput("film")
            assertEquals("/search?q=film&facet=tv", history.url)
            assertEquals(saved, history.saved)
            assertEquals(0, f.catalogReads)
            assertEquals(0, f.edits)
            assertNull(f.recentValue)

            runOnIdle { f.failSettings = false }
            onNodeWithText("Retry").performClick()
            waitUntil { f.runtime.settingsHolder.state.value != null }
            field.assertIsEnabled().assertInput("film")
            onNodeWithTag("search-input").assertInput("film")
            waitUntil { f.reads.any { it.path == "/api/tmdb/search/tv" && it.query == "film" } }
            assertEquals("/search?q=film&facet=tv", history.url)
            assertEquals(0, f.edits)

            field.performTextReplacement("arrival")
            field.assertInput("arrival")
            field.performImeAction()
            waitUntil { f.edits == 1 && f.reads.any { it.path == "/api/tmdb/search/tv" && it.query == "arrival" } }
            assertEquals("/search?q=arrival&facet=tv", history.url)
            assertEquals("[\"arrival\"]", f.recentValue)
            assertTrue(f.reads.none { it.path == "/api/tmdb/search/multi" })
            assertEquals(1, f.edits)
        } finally { f.close() }
    }

    @Test fun browserUrlTypingCaretReloadAndBackForwardUseSameEntryReplacementContract() = runComposeUiTest {
        val snapshot = captureSearchHistory()
        val f = ready(true)
        var fonts: WebSearchFonts? = null
        var showing by mutableStateOf(false)
        val history = BrowserHistory()
        setContent { if (showing) { fonts?.Load(); WebApp(f.runtime, f.catalog, f.generation, f.settings, f.recent, history) } }
        try {
            fonts = WebSearchFonts()
            installSearchHistory()
            f.runtime.start()
            runOnIdle { showing = true }
            val field = onNodeWithTag("search-input")
            field.performClick().performTextInputSelection(TextRange(2))
            field.performTextInput("X")
            field.assertIsFocused().assertInput("fiXlm")
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
            assertEquals("/search?q=fiXlm&facet=movie", history.url)
            val restored = WebNavigation(history)
            assertEquals(WebRoute.Search("fiXlm", "movie"), restored.entry.value.route)
            assertEquals("fiXlm", restored.entry.value.getString("productSearch.query"))
            restored.close()
            onNodeWithText("My Library").performClick()
            assertEquals("/library?tab=watchlist", history.url)
            runOnIdle { traverseSearchHistory(-1) }
            // Native history traversal completes on the browser event loop, not the Compose test clock.
            withContext(Dispatchers.Default) { delay(100) }
            waitUntil { history.url == "/search?q=fiXlm&facet=movie" }
            onNodeWithTag("search-input").assertInput("fiXlm")
            runOnIdle { traverseSearchHistory(1) }
            withContext(Dispatchers.Default) { delay(100) }
            waitUntil { history.url == "/library?tab=watchlist" }
            onNodeWithTag("search-input").assertDoesNotExist()
            assertEquals(0, f.edits)
            fonts.verifyLoaded { mainClock.advanceTimeByFrame(); waitForIdle() }
            waitForIdle()
            fonts.assertLoaded()
        } finally {
            runOnIdle { showing = false }
            waitForIdle()
            f.close()
            try { fonts?.close() } finally { restoreSearchHistory(snapshot) }
        }
    }

    @Test fun mainTypingKeepsFocusCaretAndRouteWhileOnlyExplicitSubmitRecords() = runComposeUiTest {
        val f = ready(true)
        val history = FakeHistory("/search?q=film&facet=movie")
        try {
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, f.recent, history) }
            onNodeWithTag("search-movie:7").assertExists()
            onNodeWithText("Movies · 1 loaded").assertExists()
            onNodeWithText("People · unknown").assertExists()
            val field = onNodeWithTag("search-input")
            field.performClick().performTextInputSelection(TextRange(2))
            field.performTextInput("X")
            field.assertIsFocused().assertInput("fiXlm")
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
            assertEquals("/search?q=fiXlm&facet=movie", history.url)
            field.performTextInput("Y")
            field.assertIsFocused().assertInput("fiXYlm")
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(4)))
            field.performKeyInput { pressKey(Key.Slash) }
            field.assertIsFocused()
            field.performTextInput("/")
            field.assertInput("fiXY/lm")
            assertEquals(0, f.edits)
            field.performImeAction()
            waitUntil { f.edits == 1 }
            assertEquals("[\"fiXY/lm\"]", f.recentValue)
            assertEquals(0, history.pushes)
        } finally { f.close() }
    }

    @Test fun headerInSearchKeepsSelectionFacetAndSlashWithoutSecondBindingDelay() = runComposeUiTest {
        val f = ready(true)
        val history = FakeHistory("/search?q=film&facet=tv")
        try {
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, f.recent, history) }
            val field = onNodeWithTag("header-search-input")
            field.performClick().performTextInputSelection(TextRange(1))
            field.performTextInput("Z")
            assertEquals("/search?q=fZilm&facet=tv", history.url)
            field.assertIsFocused().assertInput("fZilm")
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2)))
            field.performKeyInput { pressKey(Key.Slash) }
            field.assertIsFocused()
            field.performTextInput("/")
            assertEquals("/search?q=fZ%2Film&facet=tv", history.url)
            onNodeWithTag("search-input").assertInput("fZ/ilm")
            assertEquals(0, f.edits)
            field.performImeAction()
            waitUntil { f.edits == 1 }
        } finally { f.close() }
    }

    @Test fun headerOutsideSearchTransitionsWithoutWritingButEnterCommitsOnce() = runComposeUiTest {
        val f = ready(true)
        val history = FakeHistory("/library?tab=watchlist")
        try {
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, f.recent, history) }
            onNodeWithTag("header-search-input").performClick().performTextInput("arrival")
            waitUntil { history.url == "/search?q=arrival" }
            onNodeWithTag("search-input").assertInput("arrival")
            assertEquals(0, f.edits)
            runOnIdle { history.back() }
            onNodeWithTag("header-search-input").performTextReplacement("explicit")
            onNodeWithTag("header-search-input").performImeAction()
            waitUntil { f.edits == 1 }
            assertEquals("/search?q=explicit", history.url)
            assertEquals("[\"explicit\"]", f.recentValue)
        } finally { f.close() }
    }

    @Test fun realFacetsUseActualEndpointsAndPersonAndTitleRowsUseActualIds() = runComposeUiTest {
        val f = ready(true)
        val history = FakeHistory("/search?q=film")
        try {
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, f.recent, history) }
            onNodeWithTag("search-movie:7").assertExists()
            assertTrue(f.reads.any { it.path == "/api/tmdb/search/multi" })
            onNodeWithTag("search-facet-tv").performClick()
            onNodeWithTag("search-tv:7").assertExists()
            onNodeWithTag("search-facet-movie").performClick()
            onNodeWithTag("search-movie:7").assertExists()
            onNodeWithTag("search-facet-person").performClick()
            onNodeWithTag("search-person:42").assertExists().performClick()
            assertEquals("/person/42", history.url)
            assertTrue(f.reads.any { it.path == "/api/tmdb/search/person" })
            assertEquals(0, f.edits)
            runOnIdle { history.back() }
            onNodeWithTag("search-input").assertInput("film")
            onNodeWithTag("search-facet-movie").performClick()
            onNodeWithTag("search-movie:7").performClick()
            assertEquals("/movie/7", history.url)
        } finally { f.close() }
    }

    @Test fun blankShowsRealRecentAndTrendingTitlesAndOnlyChosenIntentOrClearWrites() = runComposeUiTest {
        val f = ready(true).apply { recentValue = "[\"saved film\"]" }
        val history = FakeHistory("/search")
        try {
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, f.recent, history) }
            onNodeWithText("Trending titles this week").assertExists()
            onNodeWithText("Title suggestions from TMDB, not trending search queries.").assertExists()
            onNodeWithText("All · unknown").assertExists()
            assertTrue(f.reads.any { it.path == "/api/tmdb/trending/all/week" })
            assertEquals(0, f.edits)
            onNodeWithText("saved film").performClick()
            waitUntil { f.edits == 1 }
            assertEquals("/search?q=saved%20film", history.url)
            onNodeWithText("Clear", substring = false).performClick()
            onNodeWithText("Clear recent searches").performClick()
            waitUntil { f.edits == 2 && f.recentValue == null }
            onNodeWithText("No saved recent searches.").assertExists()
        } finally { f.close() }
    }

    @Test fun urlValidOverBudgetRawInputStaysEditableAndNeverRequestsOrRecords() = runComposeUiTest {
        val f = ready(true)
        val query = " " + "a".repeat(201) + " "
        val history = FakeHistory(WebRoute.Search(query, "movie").url())
        try {
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, f.recent, history) }
            onNodeWithTag("search-input").assertInput(query)
            onNodeWithText("Search is limited to 200 characters", substring = true).assertExists()
            onNodeWithTag("search-input").performImeAction()
            assertEquals(0, f.catalogReads); assertEquals(0, f.edits)
            onNodeWithTag("search-input").performTextReplacement("corrected")
            waitUntil { f.reads.any { it.query == "corrected" } }
            onNodeWithTag("search-movie:7").assertExists()
            assertEquals("/search?q=corrected&facet=movie", history.url)
            assertEquals(0, f.edits)
        } finally { f.close() }
    }

    @Test fun unrepresentableMainAndHeaderDraftsPreserveHistoryAndHideOldResultsUntilCorrectionOrPop() = runComposeUiTest {
        val f = ready(true)
        val history = FakeHistory("/search?q=valid")
        try {
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, f.recent, history) }
            onNodeWithTag("search-movie:7").assertExists()
            val reads = f.catalogReads
            val saved = history.saved
            onNodeWithTag("search-input").performTextReplacement("界".repeat(1000))
            onNodeWithTag("search-input").assertInput("界".repeat(1000))
            onNodeWithTag("search-results").assertDoesNotExist()
            onNodeWithTag("search-facet-person").assertDoesNotExist()
            onNodeWithTag("search-input").performImeAction()
            assertEquals("/search?q=valid", history.url)
            assertEquals(saved, history.saved)
            assertEquals(reads, f.catalogReads); assertEquals(0, f.edits)
            onNodeWithTag("header-search-input").performTextReplacement("x\u0000y")
            onNodeWithTag("search-input").assertInput("x\u0000y")
            onNodeWithTag("header-search-input").performImeAction()
            assertEquals("/search?q=valid", history.url)
            onNodeWithText("Clear", substring = false).performClick()
            assertEquals("/search?q=", history.url)
            onNodeWithText("Trending titles this week").assertExists()
            onNodeWithTag("search-input").performTextReplacement("bad\u0000draft")
            onNodeWithText("My Library").performClick()
            runOnIdle { history.back() }
            onNodeWithTag("search-input").assertInput("")
            onNodeWithTag("search-results").assertExists()
            assertEquals(0, f.edits)
        } finally { f.close() }
    }

    @Test fun outsideHeaderUnrepresentableDraftDoesNotNavigateOrSubmit() = runComposeUiTest {
        val f = ready(true)
        val history = FakeHistory("/library?tab=watchlist")
        try {
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, f.recent, history) }
            onNodeWithTag("header-search-input").performTextReplacement("界".repeat(1000))
            onNodeWithText(SEARCH_URL_ERROR).assertExists()
            onNodeWithTag("header-search-input").performImeAction()
            assertEquals("/library?tab=watchlist", history.url)
            assertEquals(0, f.edits); assertEquals(0, f.catalogReads)
            onNodeWithTag("header-search-input").performTextReplacement("corrected")
            waitUntil { history.url == "/search?q=corrected" }
            assertEquals(0, f.edits)
        } finally { f.close() }
    }

    @Test fun storageRecoveryResubscribesRuntimeSettingsAndRecentWithoutWriteReplay() = runComposeUiTest {
        val f = ready(true)
        val history = FakeHistory("/search?q=film&facet=movie")
        try {
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, f.recent, history) }
            onNodeWithTag("search-movie:7").assertExists()
            runOnIdle { f.failWrite = true }
            onNodeWithTag("search-input").performImeAction()
            waitUntil { f.edits == 1 }
            onNodeWithText("Recent-search action failed.", substring = true).assertExists()
            runOnIdle { f.failSettings = true; f.storageRevision.value++ }
            waitUntil { f.activeSettingsReaders == 0 }
            onNodeWithTag("search-settings-recovery").assertExists()
            onNodeWithTag("search-recent-recovery").assertExists()
            val subscriptions = f.settingsSubscriptions
            val reads = f.catalogReads
            runOnIdle { f.failSettings = false; f.failWrite = false }
            onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("search-storage-recovery"))).performClick()
            waitUntil { f.runtime.storageFailure.value == null && f.activeSettingsReaders >= 4 }
            assertTrue(f.settingsSubscriptions > subscriptions)
            assertEquals(reads, f.catalogReads)
            assertEquals(1, f.edits)
            assertNull(f.recentValue)
            onNodeWithTag("search-movie:7").assertExists()
            onNodeWithTag("search-settings-recovery").assertDoesNotExist()
            onNodeWithTag("search-recent-recovery").assertDoesNotExist()
            runOnIdle { f.language = "es-ES"; f.storageRevision.value++ }
            waitUntil { f.reads.any { it.language == "es-ES" && it.path == "/api/tmdb/search/movie" } }
        } finally { f.close() }
    }

    @Test fun corruptRecentIsNotEmptySuccessAndExplicitClearAloneRepairsOwnedKey() = runComposeUiTest {
        val f = ready(true).apply { recentValue = "corrupt fixture" }
        val history = FakeHistory("/search")
        try {
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, f.recent, history) }
            onNodeWithTag("search-recent-recovery").assertExists()
            onNodeWithText("Recent searches are unknown until saved data is read.").assertExists()
            onNodeWithText("No saved recent searches.").assertDoesNotExist()
            onNode(hasText("Retry") and hasAnyAncestor(hasTestTag("search-recent-recovery"))).performClick()
            assertEquals(0, f.edits)
            assertEquals("corrupt fixture", f.recentValue)
            onNodeWithText("Clear recent searches").performClick()
            waitUntil { f.edits == 1 && f.recentValue == null }
            onNodeWithText("No saved recent searches.").assertExists()
            assertEquals("en-US", f.language)
        } finally { f.close() }
    }

    @Test fun retainedPageFailureRequiresExplicitRetryAndBackRestoresBoundedPages() = runComposeUiTest {
        val f = ready(true).apply { paged = true; failNextPage = true }
        val history = FakeHistory("/search?q=film&facet=movie")
        try {
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, f.recent, history) }
            onNodeWithTag("search-results").performScrollToNode(hasText("Load more"))
            onNodeWithText("Load more").performClick()
            onNodeWithText("Could not load results. Loaded results are retained.").assertExists()
            assertEquals(1, f.reads.count { it.page == "2" })
            onNodeWithTag("search-movie:7").assertExists()
            onNodeWithText("Load more").assertDoesNotExist()
            runOnIdle { f.failNextPage = false }
            onNodeWithText("Retry").performClick()
            onNodeWithTag("search-movie:8").assertExists()
            onNodeWithText("Movies · 2 loaded").assertExists()
            waitUntil { history.saved.contains("\"search.page\":\"2\"") }
            onNodeWithTag("search-movie:8").performClick()
            runOnIdle { history.back() }
            onNodeWithTag("search-movie:8").assertExists()
            assertEquals(2, f.reads.count { it.page == "2" }) // second-page cache is a real previous read
            assertEquals(0, f.edits)
        } finally { f.close() }
    }

    @Test fun ownerHasSingle300msDebounceStableIdentityAndDisposalCancelsReaders() = runTest {
        val f = ready(true)
        val history = FakeHistory("/search?q=initial")
        val nav = WebNavigation(history)
        val owner = WebSearchOwner(nav.entry.value, f.catalog, f.generation, f.settings, f.recent, backgroundScope, nav::navigate, {})
        try {
            runCurrent()
            val entry = owner.entry
            val reads = f.catalogReads
            owner.change("changed")
            assertSame(entry, nav.entry.value)
            assertEquals("/search?q=changed", history.url)
            runCurrent(); advanceTimeBy(299); runCurrent()
            assertEquals(reads, f.catalogReads)
            advanceTimeBy(1); runCurrent()
            assertEquals(reads + 1, f.catalogReads)
            owner.change(" changed "); runCurrent(); advanceTimeBy(600); runCurrent()
            assertEquals(reads + 1, f.catalogReads)
            owner.facet(SearchFacet.Person); runCurrent()
            assertEquals(reads + 2, f.catalogReads)
            assertEquals(0, f.edits)
            owner.dispose(); runCurrent()
            assertEquals(0, f.activeSettingsReaders)
            owner.change("disposed"); owner.submit(); owner.holder.retrySettings(); owner.holder.retryRecentRead()
            runCurrent()
            assertEquals(reads + 2, f.catalogReads); assertEquals(0, f.edits)
            assertEquals("/search?q=%20changed%20&facet=person", history.url)
        } finally { owner.dispose(); nav.close(); f.close() }
    }

    @Test fun leavingSearchCancelsPendingReadsAndPopRestoresUrlWithoutRecording() = runComposeUiTest {
        val f = ready(true).apply { gate = CompletableDeferred() }
        val history = FakeHistory("/search?q=pending&facet=person")
        try {
            f.runtime.start()
            setContent { WebApp(f.runtime, f.catalog, f.generation, f.settings, f.recent, history) }
            waitUntil { f.catalogReads > 0 }
            onNodeWithText("My Library").performClick()
            waitUntil { f.canceledReads > 0 }
            val reads = f.catalogReads
            runOnIdle { f.gate!!.complete(Unit); f.runtime.retryStorage() }
            assertEquals(reads, f.catalogReads)
            assertEquals("/library?tab=watchlist", history.url)
            runOnIdle { history.back() }
            onNodeWithTag("search-input").assertInput("pending")
            onNodeWithTag("search-person:42").assertExists()
            runOnIdle { history.forward() }
            assertEquals("/library?tab=watchlist", history.url)
            assertEquals(0, f.edits)
        } finally { f.close() }
    }
}

@JsFun("() => ({state:history.state, url:location.href})")
private external fun captureSearchHistory(): kotlin.js.JsAny
@JsFun("snapshot => history.replaceState(snapshot.state, '', snapshot.url)")
private external fun restoreSearchHistory(snapshot: kotlin.js.JsAny)
@JsFun("() => history.replaceState({}, '', '/search?q=film&facet=movie')")
private external fun installSearchHistory()
@JsFun("delta => history.go(delta)")
private external fun traverseSearchHistory(delta: Int)
