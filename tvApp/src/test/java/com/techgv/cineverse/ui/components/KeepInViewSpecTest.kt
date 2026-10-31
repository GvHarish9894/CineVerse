package com.techgv.cineverse.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class KeepInViewSpecTest {
    private val spec = KeepInViewSpec(insetPx = 48f)
    private val container = 1000f

    @Test fun visibleCardDoesNotScrollTheRow() {
        assertEquals(0f, spec.calculateScrollDistance(offset = 300f, size = 200f, containerSize = container))
        assertEquals(0f, spec.calculateScrollDistance(offset = 48f, size = 200f, containerSize = container))
        assertEquals(0f, spec.calculateScrollDistance(offset = 752f, size = 200f, containerSize = container))
    }

    @Test fun cardPastTheEndScrollsJustEnoughToShowItWithInset() {
        assertEquals(100f, spec.calculateScrollDistance(offset = 852f, size = 200f, containerSize = container))
    }

    @Test fun cardBeforeTheStartScrollsBackJustEnough() {
        assertEquals(-148f, spec.calculateScrollDistance(offset = -100f, size = 200f, containerSize = container))
    }

    @Test fun oversizedCardAlignsItsStart() {
        assertEquals(152f, spec.calculateScrollDistance(offset = 200f, size = 950f, containerSize = container))
    }
}
