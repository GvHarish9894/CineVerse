package com.techgv.cineverse.ui.mobile

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Detail → Person → Back returns to the exact Detail scroll position, through the real app NavHost. */
@OptIn(ExperimentalTestApi::class)
class MobileDetailBackScrollTest {
    @Test fun backFromPersonRestoresDetailScroll() = backFromPersonRestoresDetailScroll(reduceMotion = true)
    @Test fun backFromPersonRestoresDetailScrollWithSharedElements() = backFromPersonRestoresDetailScroll(reduceMotion = false)

    @Test fun backFromTopCastRestoresOverviewScrollWithSharedElements() = runComposeUiTest {
        setContent { MobileTestRoot(configureFixture = { it.api.richDetail = true }, reduceMotion = false) }
        openDetail()
        val list = onNodeWithTag("mobile-detail")
        list.performScrollToNode(hasText("Top billed cast"))
        list.performTouchInput { swipeUp(startY = bottom - 10f, endY = bottom - 200f) }
        waitForIdle()
        val circle = onNode(hasText("Cast member 1") and hasClickAction())
        val before = circle.fetchSemanticsNode().boundsInRoot.top
        circle.performClick()
        onNodeWithTag("mobile-person").assertExists()
        onNodeWithContentDescription("Back").performClick()
        onNodeWithTag("mobile-detail").assertExists()
        waitForIdle()
        val after = onNode(hasText("Cast member 1") and hasClickAction()).fetchSemanticsNode().boundsInRoot.top
        assertTrue(abs(after - before) < 1f, "Overview scroll moved on Back: top cast $before → $after")
    }

    /** On a device the return re-verifies over the network; the retained Detail must hold its position meanwhile. */
    @Test fun positionHoldsWhileTheReturnReverifiesAndAfter() = runComposeUiTest {
        lateinit var fixture: MobileFixture
        setContent { MobileTestRoot(configureFixture = { it.api.richDetail = true; fixture = it }, reduceMotion = false) }
        openDetail()
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-segments"))
        onNodeWithText("Cast", substring = false).performClick()
        val row = scrollToDeepestCastRow()
        val before = onNodeWithTag(row).fetchSemanticsNode().boundsInRoot.top
        onNodeWithTag(row).performClick()
        onNodeWithTag("mobile-person").assertExists()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        runOnIdle { fixture.api.detailGate = gate }
        onNodeWithContentDescription("Back").performClick()
        onNodeWithTag("mobile-detail").assertExists()
        waitForIdle()
        val held = onNodeWithTag(row).fetchSemanticsNode().boundsInRoot.top
        assertTrue(abs(held - before) < 1f, "Detail lost its position while re-verifying: $before → $held")
        runOnIdle { gate.complete(Unit) }
        waitForIdle()
        val after = onNodeWithTag(row).fetchSemanticsNode().boundsInRoot.top
        assertTrue(abs(after - before) < 1f, "Detail moved after re-verification: $before → $after")
    }

    /** Scrolls Detail's Cast segment to its end and returns a row well clear of the floating tab bar. */
    /** The returning Detail is already at its position on its first frame, not at the top and jumping later. */
    @Test fun returningDetailIsInPlaceFromItsFirstFrame() = runComposeUiTest {
        setContent { MobileTestRoot(configureFixture = { it.api.richDetail = true }, reduceMotion = false) }
        openDetail()
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-segments"))
        onNodeWithText("Cast", substring = false).performClick()
        val row = scrollToDeepestCastRow()
        val before = onNodeWithTag(row).fetchSemanticsNode().boundsInRoot.top
        onNodeWithTag(row).performClick()
        onNodeWithTag("mobile-person").assertExists()
        mainClock.autoAdvance = false
        onNodeWithContentDescription("Back").performClick()
        val tops = mutableListOf<Float?>()
        repeat(30) {
            mainClock.advanceTimeByFrame()
            tops += onAllNodes(hasTestTag(row)).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
        }
        assertTrue(tops.filterNotNull().isNotEmpty() && tops.filterNotNull().all { abs(it - before) < 1f },
            "returning Detail was not in place from its first frame: before=$before frames=$tops")
    }

    private fun ComposeUiTest.scrollToDeepestCastRow(): String {
        val list = onNodeWithTag("mobile-detail")
        val rows = list.fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount
        list.performScrollToIndex(rows - 1)
        onNodeWithTag("detail-person-1001").assertDoesNotExist() // Deep in the list: the first row is scrolled out of composition.
        val limit = list.fetchSemanticsNode().boundsInRoot.bottom - 300f
        return onAllNodes(SemanticsMatcher("detail person row") { node ->
            node.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith("detail-person-") == true
        }).fetchSemanticsNodes().last { it.boundsInRoot.top in 0f..limit }.config[SemanticsProperties.TestTag]
    }

    private fun ComposeUiTest.openDetail() {
        onNode(hasText("Search") and hasClickAction()).performClick()
        onNodeWithTag("search-input").performTextInput("fixture")
        onNodeWithText("Movies").performClick() // A facet change fetches immediately (typing is debounced).
        onNodeWithTag("mobile-search").performScrollToNode(hasText("search-Movie-fixture-en-US-false 1"))
        onNodeWithText("search-Movie-fixture-en-US-false 1").performClick()
    }

    private fun backFromPersonRestoresDetailScroll(reduceMotion: Boolean) = runComposeUiTest {
        setContent { MobileTestRoot(configureFixture = { it.api.richDetail = true }, reduceMotion = reduceMotion) }
        openDetail()
        onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("detail-segments"))
        onNodeWithText("Cast", substring = false).performClick()
        val deepest = scrollToDeepestCastRow()
        val before = onNodeWithTag(deepest).fetchSemanticsNode().boundsInRoot.top
        onNodeWithTag(deepest).performClick()
        onNodeWithTag("mobile-person").assertExists()
        onNodeWithContentDescription("Back").performClick()
        onNodeWithTag("mobile-detail").assertExists()
        waitForIdle()
        onNodeWithTag(deepest).assertIsDisplayed()
        val after = onNodeWithTag(deepest).fetchSemanticsNode().boundsInRoot.top
        assertTrue(abs(after - before) < 1f, "Detail scroll moved on Back: row $deepest top $before → $after")
    }
}
