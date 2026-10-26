package com.techgv.cineverse.ui.web

import androidx.compose.ui.geometry.Rect
import kotlinx.browser.document
import org.w3c.dom.Element
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
}

/** Supply explicitly at the root. Appends one fixed-position iframe to document.body per [create]. */
object WebTrailerDocumentHost : WebTrailerHost {
    override fun create(src: String, title: String, slot: Rect): WebTrailerFrame {
        val iframe = document.createElement("iframe")
        // No sandbox: YouTube embeds break under it.
        iframe.setAttribute("allow", "autoplay; encrypted-media; picture-in-picture")
        iframe.setAttribute("allowfullscreen", "")
        iframe.setAttribute("referrerpolicy", "strict-origin-when-cross-origin")
        return DocumentTrailerFrame(iframe).apply {
            place(slot)
            load(src, title)
            document.body!!.appendChild(iframe)
        }
    }
}

private class DocumentTrailerFrame(private val iframe: Element) : WebTrailerFrame {
    override fun load(src: String, title: String) {
        iframe.setAttribute("title", title)
        iframe.setAttribute("src", src)
    }

    // shortcut: viewport-fixed position assumes the canvas fills the viewport from (0,0) (index.html body, margin 0).
    override fun place(slot: Rect) {
        iframe.setAttribute("style", "position:fixed;border:0;margin:0;z-index:10;" +
            "left:${slot.left.roundToInt()}px;top:${slot.top.roundToInt()}px;" +
            "width:${slot.width.roundToInt()}px;height:${slot.height.roundToInt()}px")
    }

    override fun remove() = iframe.remove()
}
