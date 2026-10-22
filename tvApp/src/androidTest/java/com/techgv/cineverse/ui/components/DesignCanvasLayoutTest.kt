package com.techgv.cineverse.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** §6 units: the root canvas reads its container's real pixel width; font scale and nested (Dialog) canvases. */
@RunWith(AndroidJUnit4::class)
class DesignCanvasLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    /** Lays [content] out in exactly [widthPx]×[heightPx] pixels, whatever the device. */
    private fun Modifier.pixels(widthPx: Int, heightPx: Int) = layout { measurable, _ ->
        val placeable = measurable.measure(Constraints.fixed(widthPx, heightPx))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    private fun measure(widthPx: Int, fontScale: Float = 1f, inner: @Composable (record: @Composable () -> Unit) -> Unit = { it() }): Pair<Float, Float> {
        var widthDp = 0f
        var scale = 0f
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Box(Modifier.pixels(widthPx, widthPx * 9 / 16)) {
                    DesignCanvas {
                        inner {
                            BoxWithConstraints {
                                widthDp = maxWidth.value
                                scale = LocalDensity.current.fontScale
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        return widthDp to scale
    }

    @Test fun container1920pxIs1280DesignUnits() = assertEquals(1280f, measure(1920).first, 0.5f)

    @Test fun container1280pxIs1280DesignUnits() = assertEquals(1280f, measure(1280).first, 0.5f)

    @Test fun container3840pxIs1280DesignUnits() = assertEquals(1280f, measure(3840).first, 0.5f)

    @Test fun userFontScaleIsPreserved() = assertEquals(1.3f, measure(1920, fontScale = 1.3f).second, 0.001f)

    /** A Dialog root re-provides the system density; its DesignCanvas must reuse the outer canvas density. */
    @Test fun nestedCanvasReusesTheOuterCanvasDensity() {
        val (widthDp, _) = measure(1920) { record ->
            CompositionLocalProvider(LocalDensity provides Density(2.75f)) {
                Box(Modifier.pixels(600, 400)) { DesignCanvas { record() } }
            }
        }
        assertEquals(600f / 1.5f, widthDp, 0.5f)
    }
}
