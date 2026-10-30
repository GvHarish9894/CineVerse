package com.techgv.cineverse.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.ArtPalette
import com.techgv.cineverse.ui.components.CvIcons
import com.techgv.cineverse.ui.components.EpisodeCard
import com.techgv.cineverse.ui.components.GlassTopBar
import com.techgv.cineverse.ui.components.RoundIconButton
import kotlin.test.Test
import kotlin.test.assertTrue

/** Ported from the L3 review probes (ReviewLayoutTest): long titles keep clear of buttons and keep readable width. */
@OptIn(ExperimentalTestApi::class)
class LayoutTest {
    private val longTitle = "An intentionally very long movie title for this header"

    private fun ComposeUiTest.assertTopBarTitleClearOfButtons(fontScale: Float) {
        setThemedContent(ShellStyle.Mobile) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Column(Modifier.width(390.dp)) {
                    GlassTopBar(
                        true,
                        title = longTitle,
                        navigation = { RoundIconButton(CvIcons.Close, "Back", {}) },
                        actions = {
                            RoundIconButton(CvIcons.Plus, "Share", {})
                            RoundIconButton(CvIcons.Check, "Favourite", {})
                        },
                    )
                }
            }
        }
        val text = onNodeWithText(longTitle, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(text.width > 0f, "title has no width: $text")
        for (button in listOf("Back", "Share", "Favourite")) {
            val bounds = onNodeWithContentDescription(button).fetchSemanticsNode().boundsInRoot
            assertTrue(!text.overlaps(bounds), "Title overlaps $button at font scale $fontScale: title=$text $button=$bounds")
        }
    }

    @Test
    fun longTopBarTitleDoesNotOverlapTwoActions() = runComposeUiTest { assertTopBarTitleClearOfButtons(1f) }

    @Test
    fun longTopBarTitleDoesNotOverlapTwoActionsAt130Percent() = runComposeUiTest { assertTopBarTitleClearOfButtons(1.3f) }

    @Test
    fun narrowWebEpisodeHasRoomForItsTitle() = runComposeUiTest {
        val name = "This is a long episode name that needs enough width to remain readable"
        setThemedContent(ShellStyle.Web) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
                Column(Modifier.width(358.dp)) {
                    EpisodeCard("S1 E1", name, "2026 · 42m · 8.1", "Episode overview", ArtPalette(Color.Blue, Color.Magenta), {})
                }
            }
        }
        val title = onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val widthDp = title.width / density.density
        // The kit's phone web episode wraps the art above its text, so the title gets the card's full inner width.
        assertTrue(widthDp >= 180f, "Episode title is squeezed beside fixed-width art: ${widthDp}dp ($title)")
    }
}
