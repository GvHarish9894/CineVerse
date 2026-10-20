package com.techgv.cineverse.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.SavedStateHandle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.min

/** §1.3 focus position: stable keys (never indices) plus the last known index as fallback. */
@Serializable
data class SavedFocus(val panelKey: String, val rowKey: String, val itemKey: String, val index: Int)

/**
 * Focus memory of one back-stack entry, stored in its ViewModel's [SavedStateHandle] so it survives
 * push/pop and process death. Screen scalars (query, tab, …) go into the same handle directly.
 */
class FocusMemory(private val handle: SavedStateHandle) {
    val saved: SavedFocus? get() = handle.get<String>(FOCUS)?.let { Json.decodeFromString<SavedFocus>(it) }

    fun save(focus: SavedFocus) {
        handle[FOCUS] = Json.encodeToString(focus)
    }

    fun scroll(listKey: String): IntArray = handle.get<IntArray>("$SCROLL$listKey") ?: intArrayOf(0, 0)

    fun saveScroll(listKey: String, index: Int, offset: Int) {
        handle["$SCROLL$listKey"] = intArrayOf(index, offset)
    }

    private companion object {
        const val FOCUS = "focusMemory.focus"
        const val SCROLL = "focusMemory.scroll."
    }
}

/** A [LazyListState] whose scroll position is kept in [memory] under [listKey]. */
@Composable
fun rememberSavedLazyListState(memory: FocusMemory, listKey: String): LazyListState {
    val state = remember(memory, listKey) {
        val (index, offset) = memory.scroll(listKey)
        LazyListState(index, offset)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> memory.saveScroll(listKey, index, offset) }
    }
    return state
}

/** One focusable row as the restorer sees it: [itemKeys] in display order. */
class FocusRow(
    val panelKey: String,
    val rowKey: String,
    val itemKeys: List<String>,
    val listState: LazyListState? = null,
)

/**
 * Records focus into [memory] and restores it (§1.3): row into view → await composition → focus the
 * saved item key → else item at `min(savedIndex, lastIndex)` → else first item of the first row.
 */
class FocusRestorer(private val memory: FocusMemory) {
    private val requesters = HashMap<String, FocusRequester>()

    private fun requester(rowKey: String, itemKey: String) = requesters.getOrPut("$rowKey/$itemKey") { FocusRequester() }

    fun Modifier.rememberFocus(panelKey: String, rowKey: String, itemKey: String, index: Int): Modifier =
        focusRequester(requester(rowKey, itemKey)).onFocusChanged {
            if (it.isFocused) memory.save(SavedFocus(panelKey, rowKey, itemKey, index))
        }

    /**
     * Call once the current generation's data is composed. [outerList] is the vertical list whose item
     * `i` is `rows[i]`, if any.
     */
    suspend fun restore(rows: List<FocusRow>, outerList: LazyListState? = null): Boolean {
        val saved = memory.saved
        val savedRowIndex = saved?.let { s -> rows.indexOfFirst { it.panelKey == s.panelKey && it.rowKey == s.rowKey } } ?: -1
        val rowIndex = if (savedRowIndex >= 0) savedRowIndex else rows.indexOfFirst { it.itemKeys.isNotEmpty() }
        if (rowIndex < 0) return false
        val row = rows[rowIndex]
        if (row.itemKeys.isEmpty()) return false
        val itemIndex = if (savedRowIndex >= 0 && saved != null) {
            row.itemKeys.indexOf(saved.itemKey).takeIf { it >= 0 } ?: min(saved.index, row.itemKeys.lastIndex)
        } else {
            0
        }
        outerList?.bringIntoView(rowIndex)
        row.listState?.bringIntoView(itemIndex)
        val target = requester(row.rowKey, row.itemKeys[itemIndex])
        repeat(FRAMES_TO_WAIT) {
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.getOrDefault(false)) return true
        }
        return false
    }

    private suspend fun LazyListState.bringIntoView(index: Int) {
        if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
    }

    private companion object {
        /** requestFocus() fails until the item is composed and attached; give it a few frames. */
        const val FRAMES_TO_WAIT = 5
    }
}
