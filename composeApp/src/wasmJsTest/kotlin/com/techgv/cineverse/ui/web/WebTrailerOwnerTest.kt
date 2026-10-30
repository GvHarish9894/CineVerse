package com.techgv.cineverse.ui.web

import androidx.compose.ui.geometry.Rect
import com.techgv.cineverse.domain.Video
import com.techgv.cineverse.ui.web.WebTrailerHostFake.Companion.CLIP
import com.techgv.cineverse.ui.web.WebTrailerHostFake.Companion.TEASER
import com.techgv.cineverse.ui.web.WebTrailerHostFake.Companion.TRAILER
import com.techgv.cineverse.ui.web.WebTrailerHostFake.Companion.src
import kotlin.test.*

class WebTrailerOwnerTest {
    private val slot = Rect(10f, 20f, 330f, 200f)

    @Test fun invalidOrUnlistedInitialNeverCreatesFrame() {
        val invalid = listOf(
            Video("short", "Short", "Trailer", true),
            Video("aB_09-xYz12x", "Twelve", "Trailer", true),
            Video("../x?a=1&b2", "Path", "Trailer", true),
            Video("aB_09 xYz12", "Space", "Trailer", true),
        )
        invalid.forEach { video ->
            val host = WebTrailerHostFake()
            val owner = WebTrailerOwner(listOf(video, TRAILER), video, autoplay = true, host)
            assertNull(owner.current)
            owner.place(slot)
            owner.select(TRAILER)
            assertEquals(0, host.creates)
        }
        val host = WebTrailerHostFake()
        val unlisted = WebTrailerOwner(listOf(TEASER), TRAILER, autoplay = false, host)
        unlisted.place(slot)
        assertNull(unlisted.current)
        assertEquals(0, host.creates)
    }

    @Test fun emptyListNeverOpens() {
        val host = WebTrailerHostFake()
        val owner = WebTrailerOwner(emptyList(), TRAILER, autoplay = false, host)
        owner.place(slot)
        assertNull(owner.current)
        assertTrue(owner.videos.isEmpty())
        assertEquals(0, host.creates)
    }

    @Test fun placeCreatesOneNoCookieFrameWithAutoplayFromPreference() {
        val off = WebTrailerHostFake()
        WebTrailerOwner(listOf(TRAILER), TRAILER, autoplay = false, off).apply { place(slot); place(slot.translate(0f, 40f)) }
        assertEquals(1, off.creates)
        assertEquals(listOf(src(TRAILER.key, autoplay = 0)), off.srcs)
        assertEquals(slot.translate(0f, 40f), off.lastSlot)
        assertTrue(off.titles.single().contains("Official Trailer"))

        val on = WebTrailerHostFake()
        WebTrailerOwner(listOf(TRAILER), TRAILER, autoplay = true, on).place(slot)
        assertEquals(listOf(src(TRAILER.key, autoplay = 1)), on.srcs)
    }

    @Test fun selectSwitchesSrcOnSameFrameAndIgnoresUnlistedVideos() {
        val host = WebTrailerHostFake()
        val owner = WebTrailerOwner(listOf(TRAILER, TEASER, TRAILER.copy(name = "Duplicate key")), TRAILER, autoplay = false, host)
        assertEquals(listOf(TRAILER, TEASER), owner.videos)
        owner.place(slot)
        owner.select(TEASER)
        owner.select(CLIP)
        owner.select(Video("bad", "Bad", "Trailer", true))
        assertEquals(TEASER, owner.current)
        assertEquals(1, host.creates)
        assertEquals(listOf("create", "load"), host.events)
        assertEquals(listOf(src(TRAILER.key), src(TEASER.key)), host.srcs)
    }

    @Test fun closeRemovesFrameOnceAndLaterCallsDoNotRecreate() {
        val host = WebTrailerHostFake()
        val owner = WebTrailerOwner(listOf(TRAILER, TEASER), TRAILER, autoplay = false, host)
        owner.place(slot)
        owner.close()
        owner.close()
        owner.place(slot)
        owner.select(TEASER)
        assertNull(owner.current)
        assertEquals(listOf("create", "remove"), host.events)
        assertEquals(0, host.live)
    }
}
