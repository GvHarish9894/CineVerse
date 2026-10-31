package com.techgv.cineverse.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.ReviewCard
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ReviewCardTest {
    private val url = "https://msbreviews.com/movie-reviews/verity-spoiler-free-review"
    private val long = "Gorgeous, strange and warm. ".repeat(12) + "Full review: $url."

    @Test fun overflowingReviewTogglesReadMoreAndItsUrlIsAReachableLinkThroughTheMergedCard() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setThemedContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened += uri } }) {
                ReviewCard("Manuel São Bento", "", 8.0, long, Modifier.width(320.dp).testTag("review"))
            }
        }
        onNodeWithText("Read more").performClick()
        onNodeWithText("Read less").performClick()
        onNodeWithText("Read more").performClick()
        // The card merges its text for screen readers; the link must still be its own clickable node.
        val link = onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("review")) and !hasText("Read less"))
        link.assertCountEquals(1)
        link[0].performClick()
        runOnIdle { assertEquals(listOf(url), opened) }
    }

    @Test fun reviewThatFitsHasNoToggle() = runComposeUiTest {
        setThemedContent(ShellStyle.Web) { ReviewCard("Ana", "", null, "Short and sweet.", maxLines = Int.MAX_VALUE) }
        onNodeWithText("Short and sweet.").assertExists()
        onNodeWithText("Read more").assertDoesNotExist()
    }
}
