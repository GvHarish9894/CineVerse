package com.techgv.cineverse.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.techgv.cineverse.CineVerseApp
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.MediaItemDto
import com.techgv.cineverse.data.tmdb.PageDto
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TmdbRepository
import com.techgv.cineverse.ui.components.KEYBOARD_ROWS
import com.techgv.cineverse.ui.components.KeyboardKey
import com.techgv.cineverse.ui.nav.NavRail
import com.techgv.cineverse.ui.nav.RailItem
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/**
 * §1.2 Search exception: LEFT from results column 0 → keyboard, LEFT from keyboard column 0 → rail.
 * §7.7: after every Clear, focus moves to the first result once the new results settled.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SearchDpadTest {
    @get:Rule
    val compose = createComposeRule()

    /** trending → "trend-n", search/multi → "<query>-n"; poster-less movies, so no image requests. */
    private val api = Proxy.newProxyInstance(TmdbApi::class.java.classLoader, arrayOf(TmdbApi::class.java)) { _, method, args ->
        val label = when (method.name) {
            "trending" -> "trend"
            "searchMulti" -> args[0] as String
            else -> throw UnsupportedOperationException("TmdbApi.${method.name} not faked")
        }
        PageDto(1, (0 until 8).map { MediaItemDto(id = it + 1, mediaType = "movie", title = "$label-$it") }, totalPages = 1)
    } as TmdbApi

    private val keyDescriptions = KEYBOARD_ROWS.flatten().map {
        when (it) {
            is KeyboardKey.Char -> it.char.toString()
            KeyboardKey.Delete -> "Delete"
            else -> it.label
        }
    }
    private val keyboardKeyFocused = isFocused() and keyDescriptions.map { hasContentDescription(it) }.reduce(SemanticsMatcher::or)
    private val railSearchFocused = isFocused() and hasContentDescription(RailItem.Search.label)

    private fun setUpSearch() {
        val app = GenerationCounter()
        val settings = MutableStateFlow(Settings())
        val vm = SearchViewModel(TmdbRepository(api, app, settings = { settings.value }), app, settings, SavedStateHandle())
        val container = ApplicationProvider.getApplicationContext<CineVerseApp>().container
        compose.setContent {
            Row(Modifier.fillMaxSize()) {
                NavRail(current = RailItem.Search, onSelect = {}, modifier = Modifier.testTag("rail"))
                Box(Modifier.weight(1f)) { SearchScreen(container, onOpen = {}, vm = vm) }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("trend-0", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun isShown(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun pressOnFocused(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun leftFromFirstResultGoesToKeyboardThenLeftFromKeyboardColumnZeroGoesToRail() {
        setUpSearch()
        compose.onNodeWithContentDescription("trend-0", substring = true).requestFocus()
        compose.waitForIdle()

        pressOnFocused(Key.DirectionLeft)
        assertTrue("LEFT from results column 0 must land on the keyboard", isShown(keyboardKeyFocused))

        // At most 5 more LEFTs cross a 6-key row; every step stays in the keyboard until column 0 is left.
        repeat(6) {
            if (isShown(railSearchFocused)) return@repeat
            assertTrue("focus left the keyboard before reaching the rail", isShown(keyboardKeyFocused))
            pressOnFocused(Key.DirectionLeft)
        }
        assertTrue("LEFT from keyboard column 0 must open the rail on the current item", isShown(railSearchFocused))
    }

    @Test
    fun everyClearMovesFocusToTheFirstResult() {
        setUpSearch()
        val firstTrending = isFocused() and hasContentDescription("trend-0", substring = true)
        repeat(2) {
            compose.onNodeWithContentDescription("x").requestFocus()
            pressOnFocused(Key.DirectionCenter)
            compose.waitUntil(5_000) { isShown(hasContentDescription("x-0", substring = true)) }
            compose.onNodeWithContentDescription("Clear").requestFocus()
            pressOnFocused(Key.DirectionCenter)
            compose.waitUntil(5_000) { isShown(firstTrending) }
        }
    }
}
