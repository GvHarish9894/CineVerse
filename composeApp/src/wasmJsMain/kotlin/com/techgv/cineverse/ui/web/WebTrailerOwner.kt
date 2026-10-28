package com.techgv.cineverse.ui.web

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.techgv.cineverse.domain.Video

private val YOUTUBE_KEY = Regex("[A-Za-z0-9_-]{11}")

/**
 * Trailer-only playback of YouTube [Video]s (the domain type is YouTube-only). Keys are validated before any DOM work;
 * the owner holds at most one frame, created on the first [place] and removed by [close]. Keys stay out of saved state.
 */
@Stable
internal class WebTrailerOwner(
    videos: List<Video>, initial: Video, private val autoplay: Boolean, private val host: WebTrailerHost,
    private val beforeFrame: () -> Unit = {}, private val afterFrame: () -> Unit = {},
) {
    val videos: List<Video> = videos.filter { it.key.matches(YOUTUBE_KEY) }.distinctBy { it.key }

    /** Null when the input was invalid/unlisted or after [close]: nothing may be shown or embedded. */
    var current: Video? by mutableStateOf(this.videos.firstOrNull { it.key == initial.key })
        private set
    private var frame: WebTrailerFrame? = null
    var ownedElementId: String? by mutableStateOf(null)
        private set

    fun focusFrame(): Boolean = frame?.focus() ?: false

    /** [slot] in CSS px; creates the frame once, then only moves it. */
    fun place(slot: Rect) {
        val video = current ?: return
        frame?.place(slot) ?: run {
            frame = host.create(src(video), title(video), slot).also { it.setFocusBoundaries(beforeFrame, afterFrame) }
            ownedElementId = frame?.ownedElementId
        }
    }

    fun select(video: Video) {
        if (current == null || video == current || video !in videos) return
        current = video
        frame?.load(src(video), title(video))
    }

    fun close() {
        frame?.remove()
        frame = null
        ownedElementId = null
        current = null
    }

    private fun src(video: Video) = "https://www.youtube-nocookie.com/embed/${video.key}?autoplay=${if (autoplay) 1 else 0}&rel=0"
    private fun title(video: Video) = "YouTube video player: ${video.name}"
}
