package com.techgv.cineverse.ui.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MobileDetailSegmentScrollTest {
    @Test fun freshSegmentOpensAtTheStickyBar() {
        assertEquals(MobileDetailScroll(7, 0), segmentOpenScroll(MobileDetailScroll(0, 0), barIndex = 7))
    }

    @Test fun segmentScrolledDeeperKeepsItsOwnPosition() {
        assertNull(segmentOpenScroll(MobileDetailScroll(9, 12), barIndex = 7))
        assertNull(segmentOpenScroll(MobileDetailScroll(7, 0), barIndex = 7))
    }

    @Test fun noVisibleBarMeansNoChange() {
        assertNull(segmentOpenScroll(MobileDetailScroll(0, 0), barIndex = null))
    }
}
