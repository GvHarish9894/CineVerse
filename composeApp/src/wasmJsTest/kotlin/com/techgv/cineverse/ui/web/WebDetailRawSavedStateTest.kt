package com.techgv.cineverse.ui.web

import kotlin.test.*

/** Secure expectations on the inherited root seam. Keep red if its String decoder loses primitive types. */
class WebDetailRawSavedStateTest {
    @Test fun numericJsonScrollScalarMustRejectAndPreserveRawBeforeDetailCanConstruct() {
        rejectsWrongType("""{"detail.index":2}""")
    }

    @Test fun numericJsonSavedIdentityMustRejectAndPreserveRawBeforeDetailCanConstruct() {
        rejectsWrongType("""{"detail.id":7,"detail.media":"movie"}""")
    }

    @Test fun booleanJsonSavedIdentityMustRejectAndPreserveRawBeforeDetailCanConstruct() {
        rejectsWrongType("""{"detail.id":"7","detail.media":true}""")
    }

    private fun rejectsWrongType(raw: String) {
        val history = object : WebHistory {
            override val url = "/movie/7"
            override var saved = raw
            override fun push(url: String, saved: String) { error("No navigation expected") }
            override fun replace(url: String, saved: String) { this.saved = saved }
            override fun listen(onPop: () -> Unit): () -> Unit = {}
        }
        val navigation = WebNavigation(history)
        try {
            assertEquals(WebRoute.NotFound, navigation.entry.value.route, "Present non-string detail scalars must reject at the raw history boundary")
            assertTrue(history.saved == raw, "Rejected raw history must remain byte-for-byte unchanged")
        } finally { navigation.close() }
    }
}
