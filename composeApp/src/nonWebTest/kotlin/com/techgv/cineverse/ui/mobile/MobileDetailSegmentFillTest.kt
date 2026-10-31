package com.techgv.cineverse.ui.mobile

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MobileDetailSegmentFillTest {
    private data class Item(override val index: Int, override val key: Any, override val offset: Int, override val size: Int) : LazyListItemInfo

    /** Viewport 1000px with 100px bottom padding and 20px spacing; [items] are (key, offset, size) from index 0. */
    private fun layout(vararg items: Triple<String, Int, Int>, first: Int = 0) = object : LazyListLayoutInfo {
        override val visibleItemsInfo = items.mapIndexed { i, (key, offset, size) -> Item(first + i, key, offset, size) }
        override val viewportStartOffset = 0
        override val viewportEndOffset = 1000
        override val totalItemsCount = 99
        override val viewportSize = IntSize(400, 1000)
        override val afterContentPadding = 100
        override val mainAxisItemSpacing = 20
    }

    @Test fun shortSegmentUnderAPinnedBarIsToppedUpToTheViewport() {
        // bar 60 + gap 20 + review 200 + gap 20 + fill + padding 100 must reach 1000.
        val info = layout(Triple("segments", 0, 60), Triple("review:1", 80, 200), Triple("segment-fill", 300, 1000), first = 2)
        assertEquals(600, segmentFill(info, null))
        assertEquals(600, segmentFill(info, 600))
    }

    @Test fun fromThePageTopTheFillStillLetsTheBarPin() {
        val top = layout(Triple("header", 0, 300), Triple("summary", 320, 100), Triple("segments", 440, 60),
            Triple("review:1", 520, 100), Triple("segment-fill", 640, 1000))
        assertEquals(700, segmentFill(top, null))
        assertEquals(700, segmentFill(top, 800))
    }

    @Test fun withItemsScrolledOffItKeepsThePositionReachableButNeverGrowsWhileScrolling() {
        // cast:1 (index 3) is above the viewport under the pinned bar; the fill starts at 320.
        val deep = Item(4, "cast:2", 100, 200)
        val info = object : LazyListLayoutInfo by layout() {
            override val visibleItemsInfo = listOf(Item(2, "segments", 0, 60), deep, Item(5, "segment-fill", 320, 1000))
        }
        assertEquals(580, segmentFill(info, null))
        assertEquals(400, segmentFill(info, 400))
        assertEquals(580, segmentFill(info, 700))
    }

    @Test fun segmentThatFillsTheViewportNeedsNoFill() {
        val pinnedLong = layout(Triple("segments", 0, 60), Triple("cast:1", 80, 500), Triple("cast:2", 600, 500), first = 2)
        assertEquals(0, segmentFill(pinnedLong, null))
        assertEquals(0, segmentFill(pinnedLong, 700))
    }

    @Test fun undecidableLayoutsKeepTheCurrentValue() {
        assertNull(segmentFill(layout(Triple("header", 0, 900)), null))
        assertEquals(300, segmentFill(layout(Triple("header", 0, 900), Triple("summary", 920, 200)), 300))
        // Bar visible but not pinned and the fill below the fold.
        assertEquals(300, segmentFill(layout(Triple("summary", 0, 700), Triple("segments", 720, 60), Triple("cast:1", 800, 400), first = 1), 300))
    }
}
