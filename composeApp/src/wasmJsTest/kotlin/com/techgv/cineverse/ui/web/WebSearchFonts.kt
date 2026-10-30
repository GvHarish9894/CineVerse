@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, org.jetbrains.compose.resources.ExperimentalResourceApi::class)

package com.techgv.cineverse.ui.web

import com.techgv.cineverse.ui.resources.Res
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.configureWebResources
import kotlin.test.*

/** Dedicated Karma-process fixture: default reader uses location, not document.baseURI. */
internal class WebSearchFonts {
    private val names = listOf("bricolage_grotesque_medium", "bricolage_grotesque_bold", "bricolage_grotesque_extrabold", "inter_regular", "inter_medium", "inter_semibold", "inter_bold")
    private val paths = names.map { "font/$it.ttf" }
    private val probe = "files/search-font-mapping-probe"
    private val served = Json.decodeFromString<List<String>>(searchFontAssets())

    init {
        verifyDefault()
        assertEquals(7, served.toSet().size)
        assertTrue(searchFontsSameOrigin(searchFontAssets()))
        configureWebResources { resourcePathMapping { path ->
            val index = paths.indexOf(path.removePrefix("composeResources/com.techgv.cineverse.ui.resources/"))
            if (index >= 0) served[index] else "./$path"
        } }
        verifyMapped()
    }

    fun close() {
        try {
            verifyMapped()
        } finally {
            // shortcut: public API cannot capture a mapping function; only the verified pinned default in this dedicated process is restored.
            configureWebResources { resourcePathMapping { "./$it" } }
            verifyDefault()
        }
    }

    private fun verifyMapped() {
        paths.forEachIndexed { index, path -> assertEquals(served[index], Res.getUri(path)) }
        assertEquals(searchDefaultFontUri(probe), Res.getUri(probe))
    }

    private fun verifyDefault() {
        (paths + probe).forEach { assertEquals(searchDefaultFontUri(it), Res.getUri(it), "Unexpected prior resource mapping") }
    }
}

// Static test-only imports make existing real font files reachable to Karma's normal webpack asset serving.
@JsFun("""() => JSON.stringify([
 new URL('./composeResources/com.techgv.cineverse.ui.resources/font/bricolage_grotesque_medium.ttf', import.meta.url).href,
 new URL('./composeResources/com.techgv.cineverse.ui.resources/font/bricolage_grotesque_bold.ttf', import.meta.url).href,
 new URL('./composeResources/com.techgv.cineverse.ui.resources/font/bricolage_grotesque_extrabold.ttf', import.meta.url).href,
 new URL('./composeResources/com.techgv.cineverse.ui.resources/font/inter_regular.ttf', import.meta.url).href,
 new URL('./composeResources/com.techgv.cineverse.ui.resources/font/inter_medium.ttf', import.meta.url).href,
 new URL('./composeResources/com.techgv.cineverse.ui.resources/font/inter_semibold.ttf', import.meta.url).href,
 new URL('./composeResources/com.techgv.cineverse.ui.resources/font/inter_bold.ttf', import.meta.url).href
])""")
private external fun searchFontAssets(): String
@JsFun("assets => !!window.__karma__ && JSON.parse(assets).every(url => new URL(url).origin === location.origin)")
private external fun searchFontsSameOrigin(assets: String): Boolean
@JsFun("path => location.origin + location.pathname + './composeResources/com.techgv.cineverse.ui.resources/' + path")
private external fun searchDefaultFontUri(path: String): String
