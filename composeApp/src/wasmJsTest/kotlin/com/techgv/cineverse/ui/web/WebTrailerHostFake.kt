package com.techgv.cineverse.ui.web

import androidx.compose.ui.geometry.Rect
import com.techgv.cineverse.domain.Video

/** Fake-only DOM boundary: records frame create/load/place/remove in [events]; never touches the document or network. */
internal class WebTrailerHostFake : WebTrailerHost {
    val events = mutableListOf<String>()
    val srcs = mutableListOf<String>()
    val titles = mutableListOf<String>()
    var creates = 0
    var removes = 0
    var live = 0
    var lastSlot: Rect? = null

    override fun create(src: String, title: String, slot: Rect): WebTrailerFrame {
        creates++
        live++
        events += "create"
        srcs += src
        titles += title
        lastSlot = slot
        return object : WebTrailerFrame {
            private var removed = false
            override fun load(src: String, title: String) {
                check(!removed) { "load after remove" }
                events += "load"
                srcs += src
                titles += title
            }
            override fun place(slot: Rect) {
                check(!removed) { "place after remove" }
                lastSlot = slot
            }
            override fun remove() {
                check(!removed) { "double remove" }
                removed = true
                removes++
                live--
                events += "remove"
            }
        }
    }

    companion object {
        val TRAILER = Video("aB_09-xYz12", "Official Trailer", "Trailer", official = true)
        val TEASER = Video("Zz-9_yX8wV7", "Teaser One", "Teaser", official = false)
        val CLIP = Video("Q1w2E3r4T5y", "Opening Clip", "Clip", official = false)
        fun src(key: String, autoplay: Int = 0) = "https://www.youtube-nocookie.com/embed/$key?autoplay=$autoplay&rel=0"
    }
}
