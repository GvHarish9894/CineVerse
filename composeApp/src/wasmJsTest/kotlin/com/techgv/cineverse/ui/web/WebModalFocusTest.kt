@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.techgv.cineverse.di.WebSessionState
import org.w3c.dom.HTMLElement
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.CvDialog
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebModalFocusTest {
    @Test fun closeReturnsToActualTriggerRatherThanHeaderFallback() = runComposeUiTest {
        val fallback = FocusRequester()
        val returns = WebModalFocus(fallback)
        var open by mutableStateOf(false)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            CompositionLocalProvider(LocalWebModalFocus provides returns) {
                Column {
                    PillButton("Header fallback", {}, Modifier.focusRequester(fallback))
                    val trigger = rememberWebModalTrigger(WebModalKind.Account)
                    PillButton("Actual account trigger", { trigger.open { open = true } }, trigger.modifier)
                }
                if (open) CvDialog({ open = false }, "Account", onRestoreFocus = returns.restorer(WebModalKind.Account)) {}
            }
        }
        onNodeWithText("Actual account trigger").performClick()
        onNodeWithContentDescription("Close").performClick()
        onNodeWithText("Actual account trigger").assertIsFocused()
        onNodeWithText("Header fallback").assertIsNotFocused()
    }

    @Test fun removedTriggerUsesFallbackAndOtherDialogCaptureCannotRetargetReturn() = runComposeUiTest {
        val fallback = FocusRequester()
        val returns = WebModalFocus(fallback)
        var open by mutableStateOf(false)
        var showTrigger by mutableStateOf(true)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            CompositionLocalProvider(LocalWebModalFocus provides returns) {
                Column {
                    PillButton("Header fallback", {}, Modifier.focusRequester(fallback))
                    if (showTrigger) {
                        val trigger = rememberWebModalTrigger(WebModalKind.Trailer)
                        PillButton("Trailer trigger", { trigger.open { open = true } }, trigger.modifier)
                    }
                }
                if (open) CvDialog({ open = false }, "Trailer", onRestoreFocus = returns.restorer(WebModalKind.Trailer)) {}
            }
        }
        onNodeWithText("Trailer trigger").performClick()
        runOnIdle { showTrigger = false }
        onNodeWithContentDescription("Close").performClick()
        onNodeWithText("Header fallback").assertIsFocused()
    }

    @Test fun callbackRecompositionDoesNotRestoreUntilDialogLeaves() = runComposeUiTest {
        var open by mutableStateOf(true)
        var tick by mutableStateOf(0)
        val returned = mutableListOf<Int>()
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            val revision = tick
            if (open) CvDialog({ open = false }, "Dialog $tick", onRestoreFocus = { returned += revision }) {}
        }
        runOnIdle { tick++ }
        runOnIdle { assertEquals(emptyList(), returned) }
        onNodeWithContentDescription("Close").performClick()
        runOnIdle { assertEquals(listOf(1), returned) }
    }

    @Test fun liveSemanticsOwnModalAttributesAndReplacementCannotLeaveBackgroundInert() = runComposeUiTest {
        var modal by mutableStateOf<Int?>(0)
        var actions = 0
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            Box {
                PillButton("Background action", { actions++ }, Modifier.testTag("modal-background"))
                modal?.let { generation -> key(generation) {
                    CvDialog({ modal = null }, "Modal $generation") { PillButton("Real dialog action", {}) }
                } }
            }
        }
        waitUntil { hasNamedModal("Modal 0") && webDocumentNode("#modal-background")?.let(::webNodeHidden) == true }
        onNodeWithText("Real dialog action").assertExists()
        runOnIdle {
            assertTrue(webDocumentNode("[role=dialog] [aria-label=Close]") != null)
            assertTrue(webDocumentNode("[role=dialog] [role=button]") != null)
            // The installed listener can rewrite role during a sync; annotation must repair the same live node.
            webDocumentNode("[role=dialog]")!!.removeAttribute("role")
        }
        waitUntil { hasNamedModal("Modal 0") }
        runOnIdle { modal = 1 }
        waitUntil { hasNamedModal("Modal 1") && !hasNamedModal("Modal 0") }
        runOnIdle { assertTrue(webNodeHidden(webDocumentNode("#modal-background")!!)) }
        onNodeWithContentDescription("Close").performClick()
        waitUntil { !hasNamedModal("Modal 1") && webDocumentNode("#modal-background")?.let { !webNodeHidden(it) } == true }
        onNodeWithText("Background action").performClick()
        runOnIdle { assertEquals(1, actions) }
    }

    @Test fun removingFocusedAsyncActionKeepsKeyboardFocusInsideTheSameModal() = runComposeUiTest {
        var action by mutableStateOf(true)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            CvDialog({}, "Async modal") {
                if (action) PillButton("Temporary action", { action = false })
            }
        }
        onNodeWithContentDescription("Close").performKeyInput { pressKey(Key.Tab) }
        onNodeWithText("Temporary action").assertIsFocused().performClick()
        onNodeWithContentDescription("Close").assertIsFocused()
    }

    @Test fun headerSearchTabHasOneActionableSignInStopAndReverseReturnsToInput() = runComposeUiTest {
        var signIns = 0
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            val home = remember { FocusRequester() }
            Box(Modifier.width(1100.dp)) {
                WebHeader(WebRoute.Home, WebSessionState.Ready(null), remember { FocusRequester() }, { _, _ -> },
                    signIn = { signIns++ }, homeFocus = home)
            }
        }
        onNodeWithTag("header-search-input").performClick().performKeyInput { pressKey(Key.Tab) }
        onNodeWithText("Sign in").assertIsFocused()
        runOnIdle { assertEquals("CANVAS", webActiveElement()?.tagName) }
        onNodeWithText("Sign in").performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(1, signIns) }
        onNodeWithText("Sign in").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag("header-search-input").assertIsFocused()
    }

    @Test fun narrowHeaderLogoUsesItsRealNamedClickableAndCanvasEntrySelectsIt() = runComposeUiTest {
        var homes = 0
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            val home = remember { FocusRequester() }
            Box(Modifier.width(390.dp)) {
                WebHeader(WebRoute.Home, WebSessionState.Ready(null), remember { FocusRequester() }, { _, _ -> homes++ }, homeFocus = home)
            }
        }
        onNodeWithContentDescription("CineVerse home").assertHasClickAction()
        waitUntil { webDocumentNode("#web-header") != null }
        runOnIdle { enterHeaderCanvas(webDocumentNode("canvas")!!) }
        onNodeWithContentDescription("CineVerse home").assertIsFocused()
        onNodeWithContentDescription("CineVerse home").performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(1, homes) }
    }

}

@JsFun("""(node) => {
    for(let n=node;n;n=n.parentElement) if(n.hasAttribute('inert') || n.getAttribute('aria-hidden')==='true') return true;
    return false;
}""")
private external fun webNodeHidden(node: HTMLElement): Boolean

/** Focus-entry event sequence only; native browser Tab traversal remains a production-document gate. */
@JsFun("""(canvas) => {
    canvas.blur();
    document.body.dispatchEvent(new KeyboardEvent('keydown',{key:'Tab',bubbles:true,composed:true}));
    canvas.focus();
}""")
private external fun enterHeaderCanvas(canvas: HTMLElement)
