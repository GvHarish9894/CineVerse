package com.techgv.cineverse.ui.mobile

import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import kotlin.test.*

class MobileExternalActionsTest {
    @Test fun onlyElevenCharacterYouTubeKeysAreAccepted() {
        assertTrue(isYouTubeKey("aB3_dE5-gH7"))
        listOf("", "fake-video", "aB3_dE5-gH7x", "aB3 dE5-gH7", "../x?a=1&b2", "aB3_dE5/gH7").forEach {
            assertFalse(isYouTubeKey(it), it)
        }
    }

    @Test fun shareTargetsAreCanonicalUrls() {
        assertEquals("https://www.youtube.com/watch?v=aB3_dE5-gH7", youTubeWatchUrl("aB3_dE5-gH7"))
        assertEquals("https://www.themoviedb.org/movie/101", MediaRef(MediaType.Movie, 101).tmdbUrl())
        assertEquals("https://www.themoviedb.org/tv/7", MediaRef(MediaType.Tv, 7).tmdbUrl())
    }
}
