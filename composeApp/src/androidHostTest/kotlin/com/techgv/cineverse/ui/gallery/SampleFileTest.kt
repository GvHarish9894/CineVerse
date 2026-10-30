package com.techgv.cineverse.ui.gallery

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The gallery's bundled sample file (design/sample-tmdb.json) parses completely into preview models. */
class SampleFileTest {
    @Test
    fun designSampleParses() {
        val samples = parseGallerySamples(File("../design/sample-tmdb.json").readText())
        assertEquals(20, samples.movies.size)
        assertEquals(10, samples.tv.size)
        assertEquals(16, samples.people.size)
        assertTrue(samples.movies.all { it.name.isNotBlank() })
    }
}
