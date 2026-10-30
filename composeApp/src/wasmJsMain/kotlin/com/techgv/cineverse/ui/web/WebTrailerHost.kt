@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui.web

import androidx.compose.ui.geometry.Rect
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.math.roundToInt

/**
 * DOM boundary for the trailer embed: the Compose canvas cannot host an iframe, so it lives in an owned HTML node
 * above the canvas. Slots are CSS px relative to the viewport. Only [WebTrailerOwner] calls this.
 */
interface WebTrailerHost {
    fun create(src: String, title: String, slot: Rect): WebTrailerFrame
}

interface WebTrailerFrame {
    fun load(src: String, title: String)
    fun place(slot: Rect)
    fun remove()
    val ownedElementId: String? get() = null
    fun focus(): Boolean = false
    fun setFocusBoundaries(before: () -> Unit, after: () -> Unit) {}
}

/** Supply explicitly at the root. Appends one fixed-position iframe to the canvas DOM root per [create]. */
object WebTrailerDocumentHost : WebTrailerHost {
    override fun create(src: String, title: String, slot: Rect): WebTrailerFrame {
        val iframe = document.createElement("iframe") as HTMLElement
        iframe.setAttribute("data-cv-trailer", "")
        // No sandbox: YouTube embeds break under it.
        iframe.setAttribute("allow", "autoplay; encrypted-media; picture-in-picture")
        iframe.setAttribute("allowfullscreen", "")
        iframe.setAttribute("referrerpolicy", "strict-origin-when-cross-origin")
        return DocumentTrailerFrame(iframe).apply {
            place(slot)
            load(src, title)
            attach()
        }
    }
}

private var nextTrailerFrame = 0
private class DocumentTrailerFrame(private val iframe: HTMLElement) : WebTrailerFrame {
    private val id = "cv-trailer-${++nextTrailerFrame}"
    private val before = guard("before")
    private val after = guard("after")
    private var live = true
    override val ownedElementId: String = "$id-before $id $id-after"

    init { iframe.id = id }

    private fun guard(boundary: String): HTMLElement = (document.createElement("span") as HTMLElement).apply {
        id = "${this@DocumentTrailerFrame.id}-$boundary"
        tabIndex = 0
        setAttribute("data-cv-trailer-$boundary", "")
        // Focus-routing sentinels only: not duplicate player controls or aria-hidden focusable descendants.
        style.cssText = "position:fixed;width:1px;height:1px;overflow:hidden;z-index:10;"
    }

    fun attach() {
        attachTrailer(before, iframe, after)
    }

    override fun focus(): Boolean {
        if (!live) return false
        iframe.focus()
        return isTrailerFocused(iframe)
    }

    override fun setFocusBoundaries(before: () -> Unit, after: () -> Unit) {
        this.before.onfocus = { if (live) before(); Unit }
        this.after.onfocus = { if (live) after(); Unit }
    }

    override fun load(src: String, title: String) {
        iframe.setAttribute("title", title)
        iframe.setAttribute("src", src)
    }

    // shortcut: viewport-fixed position assumes the canvas fills the viewport from (0,0) (index.html body, margin 0).
    override fun place(slot: Rect) {
        before.style.left = "${slot.left.roundToInt()}px"
        before.style.top = "${slot.top.roundToInt()}px"
        after.style.left = "${slot.right.roundToInt()}px"
        after.style.top = "${slot.bottom.roundToInt()}px"
        iframe.setAttribute("style", "position:fixed;border:0;margin:0;z-index:10;" +
            "left:${slot.left.roundToInt()}px;top:${slot.top.roundToInt()}px;" +
            "width:${slot.width.roundToInt()}px;height:${slot.height.roundToInt()}px")
    }

    override fun remove() {
        if (!live) return
        live = false
        before.onfocus = null
        after.onfocus = null
        before.remove()
        iframe.remove()
        after.remove()
    }
}

// ID references in aria-owns cannot cross a shadow boundary; share the real modal semantics node's DOM root.
@JsFun("""(before,iframe,after) => {
    function find(root) {
        const canvas=root.querySelector('canvas'); if(canvas) return canvas;
        for(const n of root.querySelectorAll('*')) if(n.shadowRoot) { const found=find(n.shadowRoot); if(found) return found; }
        return null;
    }
    const canvas=find(document.getElementById('cineverse-root') || document.body);
    const root=canvas && canvas.getRootNode();
    const parent=root && root.nodeType===11 ? root : document.body;
    parent.appendChild(before); parent.appendChild(iframe); parent.appendChild(after);
}""")
private external fun attachTrailer(before: HTMLElement, iframe: HTMLElement, after: HTMLElement)
@JsFun("(iframe) => iframe.getRootNode().activeElement === iframe")
private external fun isTrailerFocused(iframe: HTMLElement): Boolean
