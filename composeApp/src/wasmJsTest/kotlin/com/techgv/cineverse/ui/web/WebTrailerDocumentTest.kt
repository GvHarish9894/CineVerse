@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui.web

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.setThemedContent
import com.techgv.cineverse.ui.theme.AppearanceSettings
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.*

/** Actual dialog + document-host path; about:blank substitutes ONLY the remote player, never the component/guards.
 * Native cross-origin player traversal and the production-document AX tree still require CDP + Safari execution.
 */
class WebTrailerDocumentTest {
    private val host = object : WebTrailerHost {
        override fun create(src: String, title: String, slot: androidx.compose.ui.geometry.Rect) =
            WebTrailerDocumentHost.create("about:blank", title, slot)
    }

    @Test fun forwardTabEntersOwnedFrameAndAfterGuardReturnsToMoreVideos() = runComposeUiTest {
        var open by mutableStateOf(true)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            if (open) WebTrailerDialog(listOf(WebTrailerHostFake.TRAILER, WebTrailerHostFake.TEASER), WebTrailerHostFake.TRAILER,
                false, { open = false }, remember { FocusRequester() }, host)
        }
        waitUntil { document.querySelector("iframe[data-cv-trailer]") != null && hasNamedModal("Official Trailer") }
        onNodeWithContentDescription("Close").performKeyInput { pressKey(Key.Tab) }
        runOnIdle { assertEquals(document.querySelector("iframe[data-cv-trailer]"), document.activeElement) }
        // Simulates the browser reaching the boundary AFTER native player controls; does not invent player key handling.
        runOnIdle { (document.querySelector("[data-cv-trailer-after]") as HTMLElement).focus() }
        onNodeWithText("Teaser One · Teaser").assertIsFocused()
        onNodeWithText("Teaser One · Teaser").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        runOnIdle { assertEquals(document.querySelector("iframe[data-cv-trailer]"), document.activeElement) }
        runOnIdle { (document.querySelector("[data-cv-trailer-before]") as HTMLElement).focus() }
        onNodeWithContentDescription("Close").assertIsFocused()
        onNodeWithContentDescription("Close").performClick()
        runOnIdle {
            assertNull(document.querySelector("iframe[data-cv-trailer]"))
            assertNull(document.querySelector("[data-cv-trailer-before], [data-cv-trailer-after]"))
        }
        waitUntil { !hasNamedModal("Official Trailer") }
    }

    @Test fun oneVideoBothBoundariesReturnToCloseAndDisposalRemovesAllOwnedDom() = runComposeUiTest {
        var open by mutableStateOf(true)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            if (open) WebTrailerDialog(listOf(WebTrailerHostFake.TRAILER), WebTrailerHostFake.TRAILER,
                false, { open = false }, remember { FocusRequester() }, host)
        }
        waitUntil { document.querySelector("iframe[data-cv-trailer]") != null }
        onNodeWithContentDescription("Close").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        runOnIdle { assertEquals(document.querySelector("iframe[data-cv-trailer]"), document.activeElement) }
        runOnIdle { (document.querySelector("[data-cv-trailer-after]") as HTMLElement).focus() }
        onNodeWithContentDescription("Close").assertIsFocused()
        runOnIdle { open = false }
        runOnIdle { assertNull(document.querySelector("[data-cv-trailer], [data-cv-trailer-before], [data-cv-trailer-after]")) }
    }
}

@JsFun("""(name) => {
    function find(root) {
        for (const n of root.querySelectorAll('*')) {
            if (n.getAttribute('role') === 'dialog' && n.getAttribute('aria-modal') === 'true' && n.getAttribute('aria-label') === name) return true;
            if (n.shadowRoot && find(n.shadowRoot)) return true;
        }
        return false;
    }
    return find(document);
}""")
internal external fun hasNamedModal(name: String): Boolean
