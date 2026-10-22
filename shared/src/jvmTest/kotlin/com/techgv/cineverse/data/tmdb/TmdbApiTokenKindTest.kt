package com.techgv.cineverse.data.tmdb

import kotlin.test.Test
import kotlin.test.assertEquals

class TmdbApiTokenKindTest {
    /** §3.1: only these two endpoints run with the user access token; everything else uses the read token. */
    @Test
    fun onlyTheUserTokenEndpointsTakeAUserBearer() {
        val tagged = TmdbApi::class.java.methods
            .filter { m -> !java.lang.reflect.Modifier.isStatic(m.modifiers) } // skip Kotlin `$default` bridges
            .filter { m -> m.parameterTypes.any { it == UserBearer::class.java } }
            .map { it.name }
            .toSet()
        assertEquals(setOf("accountList", "deleteAccessToken"), tagged)
    }
}
