package com.techgv.cineverse.ui.mobile

import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import kotlin.test.*

class MobileSharedElementKeysTest {
    @Test fun keysAreTheCardsSectionPlusExactMediaIdentity() {
        assertEquals("poster:home:trending:movie:7", mobilePosterSharedKey(mobileSharedOriginId("home:trending", MediaRef(MediaType.Movie, 7))))
        assertEquals("title:search:tv:9", mobileTitleSharedKey(mobileSharedOriginId("search", MediaRef(MediaType.Tv, 9))))
    }

    @Test fun mediaTypeRoleAndSectionKeepKeysApart() {
        val movie = MediaRef(MediaType.Movie, 7)
        assertNotEquals(mobileSharedOriginId("search", movie), mobileSharedOriginId("search", MediaRef(MediaType.Tv, 7)))
        assertNotEquals(mobilePosterSharedKey(mobileSharedOriginId("search", movie)), mobileTitleSharedKey(mobileSharedOriginId("search", movie)))
        // The same title in two Home rows must never share a key.
        assertNotEquals(mobileSharedOriginId("home:popularMovies", movie), mobileSharedOriginId("home:popular-ten", movie))
    }
}
