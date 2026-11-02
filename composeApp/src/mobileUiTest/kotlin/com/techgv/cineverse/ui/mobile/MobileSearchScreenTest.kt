package com.techgv.cineverse.ui.mobile

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.SavedStateHandle
import com.techgv.cineverse.data.tmdb.SearchFacet
import com.techgv.cineverse.domain.*
import com.techgv.cineverse.presentation.*
import com.techgv.cineverse.ui.setThemedContent
import kotlin.test.*

/** Compile on Android/iOS; execution requires a separately granted native UI runtime. */
@OptIn(ExperimentalTestApi::class)
class MobileSearchScreenTest {
    @Test fun fieldImeAndClearOnlyExplicitSearchRecordsAndRecentChipChoosesSearch() = runComposeUiTest {
        lateinit var f: MobileFixture
        setContent { MobileTestRoot(onReady = { _, fixture -> f = fixture }) }
        onNode(hasText("Search") and hasClickAction()).performClick()
        onNodeWithText("Trending titles · This week").assertExists()
        onNodeWithTag("search-input").performTextInput("fixture")
        waitForIdle()
        runOnIdle { assertEquals(0, f.store.edits) }
        onNodeWithTag("search-input").performImeAction()
        onNodeWithTag("search-input").assertIsNotFocused()
        waitForIdle()
        runOnIdle { assertEquals(1, f.store.edits) }
        onNodeWithContentDescription("Clear search").performClick()
        onNodeWithTag("mobile-search").performScrollToNode(hasText("Recent searches"))
        onNodeWithText("fixture").performClick()
        onNodeWithTag("search-input").assertTextContains("fixture")
        runOnIdle { assertEquals(2, f.store.edits) }
        onNodeWithContentDescription("Clear search").performClick()
        onNodeWithText("Clear all").performClick()
        onNodeWithText("Cancel").performClick() // Dismissing the confirmation writes nothing.
        runOnIdle { assertEquals(2, f.store.edits) }
        onNodeWithText("Clear all").performClick()
        onNodeWithText("Clear").performClick()
        onNodeWithText("No recent searches.").assertExists()
        onNodeWithText("Clear all").assertDoesNotExist()
        runOnIdle { assertEquals(3, f.store.edits) }
    }

    @Test fun realTypedFacetsNavigateTvAndPersonIdentifiersWithoutWritingRecents() = runComposeUiTest {
        lateinit var f: MobileFixture
        setContent { MobileTestRoot(onReady = { _, fixture -> f = fixture }) }
        onNode(hasText("Search") and hasClickAction()).performClick()
        onNodeWithTag("search-input").performTextInput("fixture")
        onNodeWithText("TV").performClick()
        onNodeWithTag("mobile-search").performScrollToNode(hasText("search-Tv-fixture-en-US-false 1"))
        onNodeWithText("search-Tv-fixture-en-US-false 1").performClick()
        onNodeWithTag("mobile-detail").assertExists()
        onNodeWithText("TV detail title 101").assertIsDisplayed()
        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("People").performClick()
        onNodeWithTag("mobile-search").performScrollToNode(hasText("search-Person-fixture-en-US-false 1"))
        onNodeWithText("search-Person-fixture-en-US-false 1").performClick()
        onNodeWithTag("mobile-person").assertExists()
        onNodeWithText("Person 101 en-US").assertIsDisplayed()
        onNodeWithContentDescription("Back").performClick()
        runOnIdle { assertEquals(0, f.store.edits) }
    }

    @Test fun tabAndPushedRouteRetainQueryFacetScrollAndRootReselectReturnsToTop() = runComposeUiTest {
        setContent { MobileTestRoot() }
        onNode(hasText("Search") and hasClickAction()).performClick()
        onNodeWithTag("search-input").performTextInput("fixture")
        onNodeWithText("Movies").performClick()
        onNodeWithTag("mobile-search").performScrollToNode(hasText("search-Movie-fixture-en-US-false 10"))
        onNodeWithText("search-Movie-fixture-en-US-false 10").performClick()
        onNodeWithTag("mobile-detail").assertExists()
        onNodeWithText("Detail title 110").assertIsDisplayed()
        onNode(hasText("Home") and hasClickAction()).performClick()
        onNode(hasText("Search") and hasClickAction()).performClick()
        onNodeWithText("Detail title 110").assertIsDisplayed()
        onNode(hasText("Search") and hasClickAction()).performClick() // Pop entry, not a new owner.
        onNodeWithText("search-Movie-fixture-en-US-false 10").assertIsDisplayed()
        onNodeWithTag("search-input").assertTextContains("fixture")
        onNode(hasText("Home") and hasClickAction()).performClick()
        onNode(hasText("Search") and hasClickAction()).performClick()
        onNodeWithText("search-Movie-fixture-en-US-false 10").assertIsDisplayed()
        onNode(hasText("Search") and hasClickAction()).performClick()
        onNodeWithText("search-Movie-fixture-en-US-false 1").assertIsDisplayed()
    }

    @Test fun editorSelectionIsRetainedAcrossBottomTabNavigation() = runComposeUiTest {
        setContent { MobileTestRoot() }
        onNode(hasText("Search") and hasClickAction()).performClick()
        onNodeWithTag("search-input").performTextInput("editable fixture")
        onNodeWithTag("search-input").performTextInputSelection(androidx.compose.ui.text.TextRange(3, 7))
        onNode(hasText("Home") and hasClickAction()).performClick()
        onNode(hasText("Search") and hasClickAction()).performClick()
        onNodeWithTag("search-input").assertTextContains("editable fixture")
        // Leaving the tab blurs the field; Compose CoreTextField deselect-on-blur collapses (3,7) to a caret at its
        // end (7). A fresh TextFieldValue(query) has its caret at 0, so 7 proves the editor value itself was retained.
        onNodeWithTag("search-input").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange, androidx.compose.ui.text.TextRange(7, 7)))
    }

    @Test fun overBudgetRawTextIsEditableAndMalformedStateRejectsWithoutDefaultFetch() = runComposeUiTest {
        val invalid = mutableStateOf(false)
        lateinit var f: MobileFixture
        lateinit var saved: SavedStateHandle
        setThemedContent {
            key(invalid.value) {
                val scope = rememberCoroutineScope()
                val fixture = remember { MobileFixture(scope) }
                val handle = remember { SavedStateHandle(if (invalid.value) mapOf("productSearch.facet" to 42)
                    else mapOf("productSearch.query" to "x".repeat(201))) }
                val model = remember { MobileSearchViewModel(fixture.graph, handle, scope) }
                SideEffect { f = fixture; saved = handle }
                DisposableEffect(Unit) { onDispose { fixture.graph.close() } }
                MobileSearchScreen(fixture.graph, model, 0, MobileNavigator {}, onInvalidBack = {})
            }
        }
        onNodeWithTag("search-input").assertTextContains("x".repeat(201))
        onNodeWithTag("search-input-error").assertIsDisplayed()
        runOnIdle { assertTrue(f.api.calls.isEmpty()) }
        onNodeWithTag("search-input").performTextReplacement("fixed")
        onNodeWithTag("search-input-error").assertDoesNotExist()
        runOnIdle { invalid.value = true }
        onNodeWithText("Invalid Search state. Go back to leave it unchanged.").assertIsDisplayed()
        onNodeWithText("Back").assertHasClickAction()
        runOnIdle {
            assertTrue(f.api.calls.isEmpty())
            assertEquals(42, saved.get<Any>("productSearch.facet"))
        }
    }

    @Test fun facetsShowNoCountsAndPageErrorNeverViewportRetries() = runComposeUiTest {
        val title = Title(MediaRef(MediaType.Tv, 1399), "Fixture title", "", null, null, null, 0.0, 0, emptyList(), MediaType.Tv)
        var more = 0
        var retries = 0
        var destination: MobileDestination? = null
        setThemedContent {
            SearchContentFixture(ProductSearchUiState("query", SearchFacet.Tv,
                SectionState.Content(PagedItems(listOf(Hero.OfTitle(title)), 1, true, moreError = SectionErrorKind.Offline)), settingsKnown = true),
                navigator = MobileNavigator { destination = it }, onMore = { more++ }, onRetry = { retries++ })
        }
        onNode(hasText("TV") and isSelectable()).assertIsSelected() // The row's "TV" subtitle also matches plain text.
        onNodeWithText("loaded", substring = true).assertDoesNotExist()
        onNodeWithText("All").assertExists()
        onNodeWithText("Movies").assertExists()
        onNodeWithText("People").assertExists()
        onNodeWithText("Fixture title").performClick()
        assertEquals(MobileDestination.Detail(title.ref, title.posterPath, title.title), destination)
        onNodeWithTag("mobile-search").performScrollToNode(hasText("Retry"))
        assertEquals(0, more)
        onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
        assertEquals(0, more)
    }

    @Test fun recentReadFailureAndMutationFailureAreSeparateAndRetryOnlyReads() = runComposeUiTest {
        var reads = 0
        var submits = 0
        setThemedContent {
            SearchContentFixture(ProductSearchUiState("", SearchFacet.Multi, SectionState.Empty,
                settingsKnown = true, recentQueries = null, recentReadError = SectionErrorKind.Generic,
                recentMutation = RecentMutationStatus.Failed), onRecentRetry = { reads++ }, onSubmit = { submits++ })
        }
        onNodeWithText("Couldn't update recent searches.").assertExists()
        onNodeWithTag("search-recent-recovery").onChildren().filter(hasText("Retry")).onFirst().performClick()
        assertEquals(1, reads)
        assertEquals(0, submits)
        onNodeWithText("No recent searches.").assertDoesNotExist() // Unknown is not an observed empty list.
    }

    @Test fun settingsUnknownLoadingEmptyAndSuccessfulWriteDoNotInventObservedHistory() = runComposeUiTest {
        val state = mutableStateOf(ProductSearchUiState("", SearchFacet.Multi, settingsError = SectionErrorKind.Generic))
        var retries = 0
        setThemedContent { SearchContentFixture(state.value, onSettingsRetry = { retries++ }) }
        onNodeWithText("Saved preferences unavailable; content preferences are unknown.").assertExists()
        onNodeWithTag("search-settings-recovery").onChildren().filter(hasText("Retry")).onFirst().performClick()
        assertEquals(1, retries)
        runOnIdle { state.value = state.value.copy(settingsKnown = true, settingsError = null, results = SectionState.Loading) }
        // Full-page skeleton: how many rows compose depends on the test window, so assert presence, not a count.
        assertTrue(onAllNodesWithContentDescription("Loading").fetchSemanticsNodes().isNotEmpty())
        runOnIdle { state.value = state.value.copy(query = "fixture", results = SectionState.Empty, recentMutation = RecentMutationStatus.Succeeded) }
        onNodeWithText("No results. Try another title or name.").assertExists()
        onNodeWithText("Recent-search write completed.").assertDoesNotExist() // Successful writes are silent.
        onNodeWithContentDescription("Clear search").performClick()
    }
}

@Composable
private fun SearchContentFixture(
    state: ProductSearchUiState,
    navigator: MobileNavigator = MobileNavigator {},
    onMore: () -> Unit = {},
    onRetry: () -> Unit = {},
    onRecentRetry: () -> Unit = {},
    onSettingsRetry: () -> Unit = {},
    onSubmit: () -> Unit = {},
) = MobileSearchContent(state, "", navigator, {}, {}, onSubmit, {}, {}, onRecentRetry, onSettingsRetry, onRetry, onMore)
