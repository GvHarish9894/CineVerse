@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, org.jetbrains.compose.resources.ExperimentalResourceApi::class)

package com.techgv.cineverse.ui.web

import androidx.compose.runtime.Composable
import com.techgv.cineverse.ui.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.configureWebResources
import org.jetbrains.compose.resources.preloadFont
import kotlin.test.*

/** Dedicated Karma-process fixture: default reader uses location, not document.baseURI. */
internal class WebSearchFonts {
    private val names = listOf("bricolage_grotesque_medium", "bricolage_grotesque_bold", "bricolage_grotesque_extrabold", "inter_regular", "inter_medium", "inter_semibold", "inter_bold")
    private val paths = names.map { "font/$it.ttf" }
    private val probe = "files/search-font-mapping-probe"
    private val served = Json.decodeFromString<List<String>>(searchFontAssets())
    private val trace: kotlin.js.JsAny
    private var loaded = 0

    init {
        verifyDefault()
        assertEquals(7, served.toSet().size)
        assertTrue(searchFontsSameOrigin(searchFontAssets()))
        trace = traceSearchFonts()
        configureWebResources { resourcePathMapping { path ->
            val index = paths.indexOf(path.removePrefix("composeResources/com.techgv.cineverse.ui.resources/"))
            if (index >= 0) served[index] else "./$path"
        } }
    }

    @Composable fun Load() {
        val values = listOf(
            preloadFont(Res.font.bricolage_grotesque_medium).value,
            preloadFont(Res.font.bricolage_grotesque_bold).value,
            preloadFont(Res.font.bricolage_grotesque_extrabold).value,
            preloadFont(Res.font.inter_regular).value,
            preloadFont(Res.font.inter_medium).value,
            preloadFont(Res.font.inter_semibold).value,
            preloadFont(Res.font.inter_bold).value,
        )
        loaded = values.count { it != null }
    }

    suspend fun verifyLoaded(settle: () -> Unit) = withContext(Dispatchers.Default) {
        withTimeout(5000) {
            while (loaded != 7 || !searchFontReadsComplete(trace, searchFontAssets())) {
                delay(10)
                settle()
            }
        }
        assertTrue(searchFontReadsValid(trace, searchFontAssets()), searchFontReadSummary(trace))
        println("Search font fixture: " + searchFontReadSummary(trace))
    }

    fun assertLoaded() { assertEquals(7, loaded, "Compose font loading did not complete: " + searchFontReadSummary(trace)) }

    fun close() {
        try {
            paths.forEachIndexed { index, path -> assertEquals(served[index], Res.getUri(path)) }
            assertEquals(searchDefaultFontUri(probe), Res.getUri(probe))
            // shortcut: public API cannot capture a mapping function; only the verified pinned default in this dedicated process is restored.
            configureWebResources { resourcePathMapping { "./$it" } }
            verifyDefault()
        } finally { stopSearchFontTrace(trace) }
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
@JsFun("""() => {
 const trace = {original:window.fetch, reads:[], errors:[]};
 trace.error = event => trace.errors.push(event.type);
 window.addEventListener('error',trace.error);
 window.addEventListener('unhandledrejection',trace.error);
 trace.wrapper = async function(...args) {
  const url = new URL(typeof args[0] === 'string' ? args[0] : args[0].url, location.href).href;
  if (!url.endsWith('.ttf')) return trace.original.apply(this,args);
  const read = {url, done:false}; trace.reads.push(read);
  try {
   const response = await trace.original.apply(this,args);
   const bytes = new Uint8Array(await response.clone().arrayBuffer());
   read.status=response.status; read.size=bytes.length;
   read.font=bytes.length>4 && ((bytes[0]===0 && bytes[1]===1 && bytes[2]===0 && bytes[3]===0) || String.fromCharCode(...bytes.slice(0,4))==='OTTO');
   return response;
  } catch (error) { read.failed=true; throw error; }
  finally { read.done=true; }
 };
 window.fetch=trace.wrapper; return trace;
}""")
private external fun traceSearchFonts(): kotlin.js.JsAny
@JsFun("(trace, assets) => JSON.parse(assets).every(url => trace.reads.some(read => read.url===url && read.done)) && trace.reads.every(read => read.done)")
private external fun searchFontReadsComplete(trace: kotlin.js.JsAny, assets: String): Boolean
@JsFun("(trace, assets) => trace.errors.length === 0 && trace.reads.length >= 7 && trace.reads.every(read => JSON.parse(assets).includes(read.url) && read.done && !read.failed && read.status===200 && read.size>4 && read.font)")
private external fun searchFontReadsValid(trace: kotlin.js.JsAny, assets: String): Boolean
@JsFun("trace => JSON.stringify({requests:trace.reads.length, loaderErrors:trace.errors.length, successfulFonts:trace.reads.filter(r=>r.done && !r.failed && r.status===200 && r.font).length, failed:trace.reads.filter(r=>r.failed || r.status!==200 || !r.font).length, bytes:trace.reads.map(r=>r.size)})")
private external fun searchFontReadSummary(trace: kotlin.js.JsAny): String
@JsFun("trace => { if(window.fetch!==trace.wrapper) throw Error('Unexpected fetch replacement'); window.fetch=trace.original; window.removeEventListener('error',trace.error); window.removeEventListener('unhandledrejection',trace.error); }")
private external fun stopSearchFontTrace(trace: kotlin.js.JsAny)
