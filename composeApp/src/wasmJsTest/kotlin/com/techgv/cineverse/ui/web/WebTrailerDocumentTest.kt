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
        waitUntil { webDocumentNode("iframe[data-cv-trailer]") != null && hasNamedModal("Official Trailer") }
        runOnIdle {
            val iframe = webDocumentNode("iframe[data-cv-trailer]")!!
            assertTrue(iframe.getAttribute("title")!!.contains("Official Trailer"))
            assertTrue(webDocumentNode("[role=dialog]")!!.getAttribute("aria-owns")!!.split(" ").contains(iframe.id))
            assertTrue(sameDomRoot(iframe, webDocumentNode("[role=dialog]")!!))
            assertNull(webDocumentNode("[aria-hidden=true] [data-cv-trailer-before], [aria-hidden=true] [data-cv-trailer-after]"))
        }
        onNodeWithContentDescription("Close").performKeyInput { pressKey(Key.Tab) }
        runOnIdle { assertEquals(webDocumentNode("iframe[data-cv-trailer]"), webActiveElement()) }
        // Simulates the browser reaching the boundary AFTER native player controls; does not invent player key handling.
        runOnIdle { (webDocumentNode("[data-cv-trailer-after]") as HTMLElement).focus() }
        onNodeWithText("Teaser One · Teaser").assertIsFocused()
        onNodeWithText("Teaser One · Teaser").performKeyInput { pressKey(Key.Tab) }
        onNodeWithContentDescription("Close").assertIsFocused()
        onNodeWithContentDescription("Close").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithText("Teaser One · Teaser").assertIsFocused()
        onNodeWithText("Teaser One · Teaser").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        runOnIdle { assertEquals(webDocumentNode("iframe[data-cv-trailer]"), webActiveElement()) }
        runOnIdle { (webDocumentNode("[data-cv-trailer-before]") as HTMLElement).focus() }
        onNodeWithContentDescription("Close").assertIsFocused()
        onNodeWithContentDescription("Close").performClick()
        runOnIdle {
            assertNull(webDocumentNode("iframe[data-cv-trailer]"))
            assertNull(webDocumentNode("[data-cv-trailer-before], [data-cv-trailer-after]"))
        }
        waitUntil { !hasNamedModal("Official Trailer") }
    }

    @Test fun oneVideoBothBoundariesReturnToCloseAndDisposalRemovesAllOwnedDom() = runComposeUiTest {
        var open by mutableStateOf(true)
        setThemedContent(ShellStyle.Web, AppearanceSettings(reduceMotion = true)) {
            if (open) WebTrailerDialog(listOf(WebTrailerHostFake.TRAILER), WebTrailerHostFake.TRAILER,
                false, { open = false }, remember { FocusRequester() }, host)
        }
        waitUntil { webDocumentNode("iframe[data-cv-trailer]") != null }
        onNodeWithContentDescription("Close").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        runOnIdle { assertEquals(webDocumentNode("iframe[data-cv-trailer]"), webActiveElement()) }
        runOnIdle { (webDocumentNode("[data-cv-trailer-after]") as HTMLElement).focus() }
        onNodeWithContentDescription("Close").assertIsFocused()
        runOnIdle { open = false }
        runOnIdle { assertNull(webDocumentNode("[data-cv-trailer], [data-cv-trailer-before], [data-cv-trailer-after]")) }
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

/** Only the owned top-level document/shadow roots, never the remote iframe document. */
@JsFun("""(selector) => {
    function find(root) {
        const match=root.querySelector(selector); if(match) return match;
        for(const n of root.querySelectorAll('*')) if(n.shadowRoot) { const found=find(n.shadowRoot); if(found) return found; }
        return null;
    }
    return find(document);
}""")
internal external fun webDocumentNode(selector: String): HTMLElement?
@JsFun("""() => {
    let active=document.activeElement;
    while(active && active.shadowRoot && active.shadowRoot.activeElement) active=active.shadowRoot.activeElement;
    return active;
}""")
internal external fun webActiveElement(): HTMLElement?

@JsFun("(a,b) => a.getRootNode() === b.getRootNode()")
private external fun sameDomRoot(a: HTMLElement, b: HTMLElement): Boolean
