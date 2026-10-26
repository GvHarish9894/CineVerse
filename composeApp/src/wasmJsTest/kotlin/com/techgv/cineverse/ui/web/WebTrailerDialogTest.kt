@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.domain.Video
import com.techgv.cineverse.ui.adaptive.*
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.web.WebTrailerHostFake.Companion.CLIP
import com.techgv.cineverse.ui.web.WebTrailerHostFake.Companion.TEASER
import com.techgv.cineverse.ui.web.WebTrailerHostFake.Companion.TRAILER
import com.techgv.cineverse.ui.web.WebTrailerHostFake.Companion.src
import kotlin.test.*

class WebTrailerDialogTest {
    private val all = listOf(TRAILER, TEASER, CLIP)

    /** Trigger + dialog shown while [open]; the trigger logs when it regains focus into the host's event log. */
    @Composable
    private fun Harness(host: WebTrailerHostFake, open: MutableState<Boolean>, videos: List<Video> = all, initial: Video = TRAILER) {
        val trigger = remember { FocusRequester() }
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
        Column {
            PillButton("Before trailer", {})
            PillButton("Play trailer", { open.value = true },
                Modifier.focusRequester(trigger).onFocusChanged { if (it.isFocused && open.value.not()) host.events += "trigger-focused" })
            PillButton("After trailer", {})
        }
        if (open.value) WebTrailerDialog(videos, initial, autoplay = false, onClose = { open.value = false }, restoreFocus = trigger, host = host)
    }

    /** Opens from the trigger, then parks the page's own focus off it (the dialog is a separate window) so only restoreFocus can return it. */
    private fun ComposeUiTest.openAndParkPageFocus(host: WebTrailerHostFake) {
        onNodeWithText("Play trailer").requestFocus()
        onNodeWithText("Play trailer").performKeyInput { pressKey(Key.Enter) }
        onNodeWithContentDescription("Close").assertIsFocused()
        waitUntil { host.creates == 1 }
        onNodeWithText("After trailer").requestFocus()
        waitForIdle()
        onNodeWithText("Play trailer").assertIsNotFocused()
    }

    @Test fun invalidInitialOrEmptyListNeverShowsDialogOrCreatesNode() = runComposeUiTest {
        val host = WebTrailerHostFake()
        var case by mutableStateOf(0)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            when (case) {
                0 -> WebTrailerDialog(emptyList(), TRAILER, false, {}, remember { FocusRequester() }, host)
                1 -> WebTrailerDialog(listOf(Video("not-a-key", "Bad", "Trailer", true)), Video("not-a-key", "Bad", "Trailer", true), false, {}, remember { FocusRequester() }, host)
                else -> WebTrailerDialog(listOf(TEASER), TRAILER, false, {}, remember { FocusRequester() }, host)
            }
        }
        repeat(3) {
            runOnIdle { case = it }
            onNodeWithTag("web-trailer-dialog").assertDoesNotExist()
        }
        assertEquals(0, host.creates)
    }

    @Test fun openCreatesOneNoCookieEmbedAndRecompositionDoesNotDuplicate() = runComposeUiTest {
        val host = WebTrailerHostFake()
        var tick by mutableStateOf(0)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Text("Tick $tick")
            WebTrailerDialog(all, TRAILER, autoplay = true, onClose = {}, restoreFocus = remember { FocusRequester() }, host = host)
        }
        onNodeWithTag("web-trailer-dialog").assertExists()
        waitUntil { host.creates == 1 }
        repeat(3) { runOnIdle { tick++ } }
        runOnIdle {
            assertEquals(1, host.creates)
            assertEquals(1, host.live)
            assertEquals(listOf(src(TRAILER.key, autoplay = 1)), host.srcs)
            val slot = assertNotNull(host.lastSlot)
            assertEquals(16f / 9f, slot.width / slot.height, 0.05f)
            // Wider than any 560dp dialog's content (560 - 2 × 36 padding): the player uses the 1000dp kit width.
            assertTrue(slot.width > 488f, "Trailer slot is ${slot.width}dp wide")
        }
        onNodeWithText(TRAILER.key, substring = true).assertDoesNotExist()
        onNodeWithContentDescription("Play").assertDoesNotExist()
        onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    @Test fun moreVideosSwitchesEmbedWithoutSecondNode() = runComposeUiTest {
        val host = WebTrailerHostFake()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            WebTrailerDialog(all, TRAILER, autoplay = false, onClose = {}, restoreFocus = remember { FocusRequester() }, host = host)
        }
        waitUntil { host.creates == 1 }
        onNodeWithText("Official Trailer · Trailer").assertDoesNotExist()
        onNodeWithTag("web-trailer-more-videos").performScrollToNode(hasText("Teaser One · Teaser"))
        onNodeWithText("Teaser One · Teaser").performClick()
        onNodeWithText("Official Trailer · Trailer").assertExists()
        onNodeWithText("Teaser One · Teaser").assertDoesNotExist()
        runOnIdle {
            assertEquals(1, host.creates)
            assertEquals(listOf("create", "load"), host.events)
            assertEquals(listOf(src(TRAILER.key), src(TEASER.key)), host.srcs)
        }
    }

    @Test fun escapeRemovesNodeThenReturnsFocusToTrigger() = runComposeUiTest {
        val host = WebTrailerHostFake()
        val open = mutableStateOf(false)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) { Harness(host, open) }
        openAndParkPageFocus(host)
        onNodeWithContentDescription("Close").requestFocus()
        onNodeWithContentDescription("Close").assertIsFocused()
        onNodeWithContentDescription("Close").performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag("web-trailer-dialog").assertDoesNotExist()
        onNodeWithText("Play trailer").assertIsFocused()
        runOnIdle {
            assertEquals(0, host.live)
            assertEquals(listOf("remove", "trigger-focused"), host.events.takeLast(2))
        }
    }

    @Test fun closeButtonRemovesNodeThenReturnsFocusToTrigger() = runComposeUiTest {
        val host = WebTrailerHostFake()
        val open = mutableStateOf(false)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) { Harness(host, open) }
        openAndParkPageFocus(host)
        onNodeWithContentDescription("Close").performClick()
        onNodeWithTag("web-trailer-dialog").assertDoesNotExist()
        onNodeWithText("Play trailer").assertIsFocused()
        runOnIdle { assertEquals(listOf("remove", "trigger-focused"), host.events.takeLast(2)) }
    }

    @Test fun disposeOnRouteChangeRemovesNodeThenReturnsFocusToTrigger() = runComposeUiTest {
        val host = WebTrailerHostFake()
        val open = mutableStateOf(false)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) { Harness(host, open) }
        openAndParkPageFocus(host)
        runOnIdle { open.value = false } // The route left without calling onClose.
        onNodeWithTag("web-trailer-dialog").assertDoesNotExist()
        onNodeWithText("Play trailer").assertIsFocused()
        runOnIdle {
            assertEquals(1, host.removes)
            assertEquals(listOf("remove", "trigger-focused"), host.events.takeLast(2))
        }
    }

    @Test fun keyboardTrapKeepsSixTabCyclesInsideTrailerDialog() = runComposeUiTest {
        val host = WebTrailerHostFake()
        val open = mutableStateOf(false)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) { Harness(host, open) }
        onNodeWithText("Play trailer").requestFocus()
        onNodeWithText("Play trailer").performKeyInput { pressKey(Key.Enter) }
        onNodeWithContentDescription("Close").assertIsFocused()
        val focusedInDialog = isFocused() and hasAnyAncestor(isDialog())
        repeat(6) {
            onNode(focusedInDialog).performKeyInput { pressKey(Key.Tab) }
            onNode(focusedInDialog).assertExists()
            onNodeWithText("Before trailer").assertIsNotFocused()
            onNodeWithText("After trailer").assertIsNotFocused()
        }
        runOnIdle { assertEquals(1, host.creates) }
    }

    @Test fun phoneAndDesktopKeepPlayerSlotAndMoreVideosReachable() = runComposeUiTest {
        val host = WebTrailerHostFake()
        var width by mutableStateOf(WindowClass.Phone)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            CompositionLocalProvider(LocalWindowClass provides width) {
                WebTrailerDialog(all, TRAILER, autoplay = false, onClose = {}, restoreFocus = remember { FocusRequester() }, host = host)
            }
        }
        listOf(WindowClass.Phone, WindowClass.Desktop).forEach { windowClass ->
            runOnIdle { width = windowClass }
            onNodeWithTag("web-trailer-player-slot").assertIsDisplayed()
            onNodeWithTag("web-trailer-more-videos").performScrollToNode(hasText("Opening Clip · Clip"))
            onNodeWithText("Opening Clip · Clip").assertIsDisplayed()
        }
        runOnIdle { assertEquals(1, host.creates) }
    }
}
