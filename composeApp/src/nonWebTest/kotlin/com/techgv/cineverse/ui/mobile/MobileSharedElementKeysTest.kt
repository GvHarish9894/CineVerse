package com.techgv.cineverse.ui.mobile

import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import kotlin.test.*

class MobileSharedElementKeysTest {
    @Test fun keysAreTheExactMediaIdentity() {
        assertEquals("poster:movie:7", mobilePosterSharedKey(MediaRef(MediaType.Movie, 7)))
        assertEquals("title:tv:9", mobileTitleSharedKey(MediaRef(MediaType.Tv, 9)))
    }

    @Test fun mediaTypeAndRoleKeepKeysApart() {
        assertNotEquals(mobilePosterSharedKey(MediaRef(MediaType.Movie, 7)), mobilePosterSharedKey(MediaRef(MediaType.Tv, 7)))
        assertNotEquals(mobilePosterSharedKey(MediaRef(MediaType.Movie, 7)), mobileTitleSharedKey(MediaRef(MediaType.Movie, 7)))
    }
}
