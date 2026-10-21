package com.techgv.cineverse.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.min

/** §1.3 focus position: stable keys (never indices) plus the last known index as fallback. */
@Serializable
data class SavedFocus(val panelKey: String, val rowKey: String, val itemKey: String, val index: Int)

/** Joins keys so that ("a/b", "c") and ("a", "b/c") stay distinct. */
private fun scopedKey(vararg parts: String) = parts.joinToString("\u001F")

/**
 * Focus memory of one back-stack entry, stored in its ViewModel's [SavedStateHandle] so it survives
 * push/pop and process death: the last focused item of the screen plus the last focused item of every
 * (panel, row). Screen scalars (query, tab, …) go into the same handle directly.
 */
class FocusMemory(private val handle: SavedStateHandle) {
    /** The screen's last focused item. */
    val saved: SavedFocus? get() = handle.get<String>(FOCUS)?.let { Json.decodeFromString<SavedFocus>(it) }

    /** The last focused item of one row of one panel. */
    fun savedIn(panelKey: String, rowKey: String): SavedFocus? =
        handle.get<String>(ROW + scopedKey(panelKey, rowKey))?.let { Json.decodeFromString<SavedFocus>(it) }

    fun save(focus: SavedFocus) {
        val encoded = Json.encodeToString(focus)
        handle[FOCUS] = encoded
        handle[ROW + scopedKey(focus.panelKey, focus.rowKey)] = encoded
    }

    fun scroll(listKey: String): IntArray = handle.get<IntArray>("$SCROLL$listKey") ?: intArrayOf(0, 0)

    fun saveScroll(listKey: String, index: Int, offset: Int) {
        handle["$SCROLL$listKey"] = intArrayOf(index, offset)
    }

    private companion object {
        const val FOCUS = "focusMemory.focus"
        const val ROW = "focusMemory.row."
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

/** A [LazyGridState] whose scroll position is kept in [memory] under [gridKey]. */
@Composable
fun rememberSavedLazyGridState(memory: FocusMemory, gridKey: String): LazyGridState {
    val state = remember(memory, gridKey) {
        val (index, offset) = memory.scroll(gridKey)
        LazyGridState(index, offset)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> memory.saveScroll(gridKey, index, offset) }
    }
    return state
}

/**
 * One focusable row (or grid) as the restorer sees it: [itemKeys] in display order. A grid is one row
 * whose [gridState] scrolls it. [lazyIndices]: the lazy-item index of each item key when the list / grid also
 * has non-focusable items (headers, status rows); null when item i is lazy item i.
 */
class FocusRow(
    val panelKey: String,
    val rowKey: String,
    val itemKeys: List<String>,
    val listState: LazyListState? = null,
    val gridState: LazyGridState? = null,
    val lazyIndices: List<Int>? = null,
)

/**
 * Records focus into [memory] and restores it (§1.3): row into view → await composition → focus the
 * saved item key → else item at `min(savedIndex, lastIndex)` → else first item of the first non-empty row.
 */
class FocusRestorer(private val memory: FocusMemory) {
    /**
     * The screen's focus (row, item key and index) when this restorer was created (screen entry): default
     * focus that lands before the first [restore] (e.g. the window regaining focus after a pop) must not
     * replace the item to restore. Cleared by that first [restore]; later ones read [memory].
     */
    private var savedAtEntry: SavedFocus? = memory.saved

    private val requesters = HashMap<String, FocusRequester>()

    /** Attached [rememberFocus] nodes per item key: only their requesters can take focus right now. */
    private val attached = HashMap<String, Int>()

    /** Rows (panel, row) whose container currently holds focus. */
    private val focusedRows = HashSet<String>()

    /** Requesters are namespaced by panel: equal row / item keys in two panels never collide. */
    internal fun requester(panelKey: String, rowKey: String, itemKey: String) =
        requesters.getOrPut(scopedKey(panelKey, rowKey, itemKey)) { FocusRequester() }

    internal fun onAttached(key: String) {
        attached.merge(key, 1, Int::plus)
    }

    internal fun onDetached(key: String) {
        attached.computeIfPresent(key) { _, count -> (count - 1).takeIf { it > 0 } }
    }

    /** The item's requester while its node is attached (composed, not a disposed / reusable lazy item), else null. */
    internal fun attachedRequester(panelKey: String, rowKey: String, itemKey: String): FocusRequester? {
        val key = scopedKey(panelKey, rowKey, itemKey)
        return requesters[key]?.takeIf { key in attached }
    }

    /** Registers the item as attached while its modifier node is attached; lazy items leaving composition detach. */
    fun Modifier.rememberFocus(panelKey: String, rowKey: String, itemKey: String, index: Int): Modifier =
        focusRequester(requester(panelKey, rowKey, itemKey))
            .then(AttachTrackerElement(this@FocusRestorer, scopedKey(panelKey, rowKey, itemKey)))
            .onFocusChanged { if (it.isFocused) memory.save(SavedFocus(panelKey, rowKey, itemKey, index)) }

    /**
     * Put on the row's container (e.g. the `LazyRow`): focus entering the row from elsewhere (D-pad from
     * another row) lands on that row's remembered item while it is attached, else the default target.
     */
    fun Modifier.restoreRowFocus(panelKey: String, rowKey: String): Modifier =
        restoreRowFocus(FocusRow(panelKey, rowKey, emptyList()), scope = null)

    /**
     * Item key a D-pad entry into [row] targets: its remembered item, else the first item (prototype `rowMem`,
     * not the geometric nearest). A programmatic requestFocus ([FocusDirection.Enter]) keeps its own target (null).
     */
    internal fun entryKey(row: FocusRow, direction: FocusDirection): String? =
        if (direction == FocusDirection.Enter) null
        else memory.savedIn(row.panelKey, row.rowKey)?.itemKey ?: row.itemKeys.firstOrNull()

    /**
     * [restoreRowFocus] that also handles a remembered item that is not attached (scrolled off, or removed:
     * `min(savedIndex, lastIndex)`): the default target takes focus first, then [scope] scrolls [row]'s
     * list / grid state to the entry item, awaits its composition and focuses it while the row still has
     * focus. [scope] must be a composition scope (`rememberCoroutineScope`): it waits for frames.
     */
    fun Modifier.restoreRowFocus(row: FocusRow, scope: CoroutineScope?): Modifier {
        val rowId = scopedKey(row.panelKey, row.rowKey)
        return onFocusChanged { if (it.hasFocus) focusedRows += rowId else focusedRows -= rowId }
            .focusProperties {
                onEnter = {
                    // A programmatic requestFocus (direction Enter) keeps its own target; only D-pad entries are redirected.
                    val saved = memory.savedIn(row.panelKey, row.rowKey)?.takeIf { requestedFocusDirection != FocusDirection.Enter }
                    val target = entryKey(row, requestedFocusDirection)?.let { attachedRequester(row.panelKey, row.rowKey, it) }
                    when {
                        target != null -> target.tryFocus() // false: the default target keeps focus
                        saved != null && scope != null && row.itemKeys.isNotEmpty() ->
                            scope.launch { focusIn(row, entryIndex(row)) { rowId in focusedRows } }
                    }
                }
            }
            .focusGroup()
    }

    /** Index to focus inside [row]: its remembered item key, else `min(savedIndex, lastIndex)`, else 0. */
    internal fun entryIndex(row: FocusRow): Int = memory.savedIn(row.panelKey, row.rowKey)?.let { indexIn(row, it) } ?: 0

    private fun indexIn(row: FocusRow, saved: SavedFocus): Int =
        row.itemKeys.indexOf(saved.itemKey).takeIf { it >= 0 } ?: min(saved.index, row.itemKeys.lastIndex).coerceAtLeast(0)

    /** (row index, item index) that [restore] focuses, or null when every row is empty. */
    internal fun restoreTarget(rows: List<FocusRow>): Pair<Int, Int>? {
        val saved = savedAtEntry ?: memory.saved
        val savedRow = saved?.let { s -> rows.indexOfFirst { it.panelKey == s.panelKey && it.rowKey == s.rowKey } } ?: -1
        if (saved != null && savedRow >= 0 && rows[savedRow].itemKeys.isNotEmpty()) return savedRow to indexIn(rows[savedRow], saved)
        val firstRow = rows.indexOfFirst { it.itemKeys.isNotEmpty() }
        return if (firstRow >= 0) firstRow to 0 else null
    }

    /**
     * Call once the current generation's data is composed. [outerList] is the vertical list whose item
     * `i` is `rows[i]`, if any.
     */
    suspend fun restore(rows: List<FocusRow>, outerList: LazyListState? = null): Boolean {
        val target = restoreTarget(rows)
        savedAtEntry = null
        val (rowIndex, itemIndex) = target ?: return false
        outerList?.bringIntoView(rowIndex)
        return focusIn(rows[rowIndex], itemIndex)
    }

    /** Item into view → await composition → focus it; gives up when [wanted] turns false. */
    suspend fun focusIn(row: FocusRow, itemIndex: Int, wanted: () -> Boolean = { true }): Boolean {
        val lazyIndex = row.lazyIndices?.get(itemIndex) ?: itemIndex
        row.listState?.bringIntoView(lazyIndex)
        row.gridState?.let { grid -> if (grid.layoutInfo.visibleItemsInfo.none { it.index == lazyIndex }) grid.scrollToItem(lazyIndex) }
        val target = requester(row.panelKey, row.rowKey, row.itemKeys[itemIndex])
        repeat(FRAMES_TO_WAIT) {
            withFrameNanos { }
            if (!wanted()) return false
            if (target.tryFocus()) return true
        }
        return false
    }

    private fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus() }.getOrDefault(false)

    private suspend fun LazyListState.bringIntoView(index: Int) {
        if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
    }

    private companion object {
        /** requestFocus() fails until the item is composed and attached; give it a few frames. */
        const val FRAMES_TO_WAIT = 5
    }
}

private data class AttachTrackerElement(val restorer: FocusRestorer, val key: String) :
    ModifierNodeElement<AttachTrackerNode>() {
    override fun create() = AttachTrackerNode(restorer, key)
    override fun update(node: AttachTrackerNode) = node.update(restorer, key)
    override fun InspectorInfo.inspectableProperties() {
        name = "rememberFocus"
        properties["key"] = key
    }
}

private class AttachTrackerNode(private var restorer: FocusRestorer, private var key: String) : Modifier.Node() {
    override fun onAttach() = restorer.onAttached(key)
    override fun onDetach() = restorer.onDetached(key)

    fun update(newRestorer: FocusRestorer, newKey: String) {
        if (isAttached) restorer.onDetached(key)
        restorer = newRestorer
        key = newKey
        if (isAttached) restorer.onAttached(key)
    }
}
