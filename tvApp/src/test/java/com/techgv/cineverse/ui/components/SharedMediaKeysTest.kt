package com.techgv.cineverse.ui.components

import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.presentation.HomeCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A Home card and the Detail hero of the same title must produce the same shared-element key for the image the card showed. */
class SharedMediaKeysTest {
    private val movie = MediaRef(MediaType.Movie, 42)
    private val series = MediaRef(MediaType.Tv, 42)

    @Test
    fun posterCard_sharesTheDetailHeroPoster() {
        assertEquals("poster:movie:42", homeCardSharedKey(movie, HomeCard.Poster))
        assertEquals(homeCardSharedKey(movie, HomeCard.Poster), sharedPosterKey(movie))
    }

    @Test
    fun landscapeCard_sharesTheDetailBackdrop() {
        assertEquals("backdrop:tv:42", homeCardSharedKey(series, HomeCard.Landscape))
        assertEquals(homeCardSharedKey(series, HomeCard.Landscape), sharedBackdropKey(series))
    }

    @Test
    fun keys_differByMediaTypeAndImage() {
        assertNotEquals(sharedPosterKey(movie), sharedPosterKey(series))
        assertNotEquals(sharedPosterKey(movie), sharedBackdropKey(movie))
    }
}
