package com.techgv.cineverse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.ActionTile
import com.techgv.cineverse.ui.components.ArtPalette
import com.techgv.cineverse.ui.components.Chip
import com.techgv.cineverse.ui.components.CineVerseTabs
import com.techgv.cineverse.ui.components.CvIcons
import com.techgv.cineverse.ui.components.FactTile
import com.techgv.cineverse.ui.components.FloatingTabBar
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.components.PosterCard
import com.techgv.cineverse.ui.components.SegmentedControl
import com.techgv.cineverse.ui.components.SettingRow
import com.techgv.cineverse.ui.components.SettingTrailing
import kotlin.test.Test
import kotlin.test.assertTrue

/** Dynamic Type / browser zoom up to 1.3: fixed-size primitives grow instead of clipping their text. */
@OptIn(ExperimentalTestApi::class)
class FontScaleTest {
    private fun SemanticsNode.allNodes(): List<SemanticsNode> = listOf(this) + children.flatMap { it.allNodes() }

    @Test
    fun textStaysInsideItsContainersAt130Percent() = runComposeUiTest {
        setThemedContent(ShellStyle.Mobile) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                Column(Modifier.width(390.dp)) {
                    PillButton("Watchlist", {}, icon = CvIcons.Plus)
                    Row { Chip("Now playing", true, {}); Chip("Top rated", false, {}) }
                    SegmentedControl(listOf("Movies", "TV shows", "People"), 0, {}, Modifier.fillMaxWidth())
                    Row { ActionTile("Watchlist", CvIcons.Plus, {}, Modifier.weight(1f)); ActionTile("Trailer", CvIcons.Play, {}, Modifier.weight(1f)) }
                    Row { FactTile("Runtime", "2h 8m", Modifier.weight(1f)); FactTile("Status", "Released", Modifier.weight(1f)) }
                    SettingRow("Reduce motion", "No zooms, slides or fades", CvIcons.Motion, Color.Cyan, SettingTrailing.Toggle(false) {})
                    PosterCard("Neon Tide", "2026 · ★ 8.1", ArtPalette(Color.Magenta, Color.Blue), {})
                    Box(Modifier.fillMaxWidth().height(140.dp)) { FloatingTabBar(CineVerseTabs, 0, {}) }
                }
            }
        }
        val nodes = onRoot(useUnmergedTree = true).fetchSemanticsNode().allNodes()
        val texts = nodes.filter { SemanticsProperties.Text in it.config }
        assertTrue(texts.size >= 20, "expected the primitives' texts, found ${texts.size}")
        for (node in texts) {
            val label = node.config[SemanticsProperties.Text].joinToString { it.text }
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            // Taller than its box, or cut to fewer lines / ellipsized: the text itself is clipped.
            assertTrue(layouts.none { it.didOverflowHeight }, "'$label' overflows its own text box at 1.3×")
            var ancestor = node.parent
            while (ancestor != null) {
                val outer: Rect = ancestor.boundsInRoot
                val inner = node.boundsInRoot
                assertTrue(
                    inner.top >= outer.top - 0.5f && inner.bottom <= outer.bottom + 0.5f,
                    "'$label' ($inner) is clipped vertically by an ancestor ($outer)",
                )
                ancestor = ancestor.parent
            }
        }
    }
}
