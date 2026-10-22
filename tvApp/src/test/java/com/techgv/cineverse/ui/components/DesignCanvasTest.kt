package com.techgv.cineverse.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec dp are 1280-wide design units (§6): the canvas density scales them to the real window width. */
class DesignCanvasTest {
    @Test fun `design density scales dp to the window width`() {
        assertEquals(1.0f, designDensity(1280f), 0.0001f)
        assertEquals(1.5f, designDensity(1920f), 0.0001f)
        assertEquals(3.0f, designDensity(3840f), 0.0001f)
    }
}
