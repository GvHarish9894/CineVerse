@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, androidx.compose.ui.test.ExperimentalTestApi::class)

package com.techgv.cineverse.ui.theme

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.LoadedFont
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.v2.runComposeUiTest
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.web.WebSearchFonts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.MissingResourceException
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.impl.use
import kotlin.js.JsAny
import kotlin.js.JsException
import kotlin.js.toJsReference
import kotlin.test.*

class WebBundledFontsTest {
    @Test fun networkRejectionKeepsFallbackAvailable() = runTest {
        var reads = 0
        assertNull(loadWebBundledFonts { path ->
            reads++
            readWebBundledFontBytes(path) { webFontRequestFixture("network") }
        })
        assertEquals(1, reads)
        assertEquals(FontFamily.Default, WebFallbackFonts.display)
        assertEquals(FontFamily.Default, WebFallbackFonts.body)
    }

    @Test fun native404AndBodyReadRejectionsBecomeTypedAssetFailures() = runTest {
        for (kind in listOf("404", "body")) {
            val request = webFontRequestFixture(kind)
            assertFailsWith<WebFontAssetReadException> {
                readWebBundledFontBytes(WebBundledFontAssets.first().path) { request }
            }
            assertTrue(webFontRequestAborted(request))
        }
    }

    @Test fun successfulNativeReadPreservesBytesWithoutAbortingTheConsumedBody() = runTest {
        val request = webFontRequestFixture("bytes")
        assertContentEquals(byteArrayOf(0, 127, -128, -1),
            readWebBundledFontBytes(WebBundledFontAssets.first().path) { request })
        assertFalse(webFontRequestAborted(request))
    }

    @Test fun nativeHtmlResponseIsRejectedByTheDecoder() = runComposeUiTest {
        assertNull(loadWebBundledFonts { path ->
            readWebBundledFontBytes(path) { webFontRequestFixture("html") }
        })
    }

    @Test fun cancelingAPendingNativePromiseAbortsOnlyItsOwnedRequest() = runTest {
        val request = webFontRequestFixture("pending")
        var canceled = false
        val load = launch {
            try {
                loadWebBundledFonts { path -> readWebBundledFontBytes(path) { request } }
            } catch (_: CancellationException) { canceled = true }
        }
        yield()
        load.cancelAndJoin()
        assertTrue(canceled)
        assertTrue(webFontRequestAborted(request))
    }

    @Test fun missingResourceDoesNotEscapeOrReadAgain() = runTest {
        var reads = 0
        assertNull(loadWebBundledFonts { reads++; throw MissingResourceException(it) })
        assertEquals(1, reads)
    }

    @Test fun malformedAndEmptyBytesDoNotBecomePublishedFonts() = runComposeUiTest {
        for (bytes in listOf(byteArrayOf(), "<html>not a font</html>".encodeToByteArray(), byteArrayOf(0, 1, 0, 0))) {
            var reads = 0
            assertNull(loadWebBundledFonts { reads++; bytes })
            assertEquals(1, reads)
        }
    }

    @Test fun cancellationAndProgrammerErrorsAreNotConvertedToFallbackSuccess() = runTest {
        val cancellation = CancellationException("font owner left")
        assertSame(cancellation, assertFailsWith<CancellationException> { loadWebBundledFonts { throw cancellation } })
        val bug = IllegalStateException("not a resource failure")
        assertSame(bug, assertFailsWith<IllegalStateException> { loadWebBundledFonts { throw bug } })
        val plain = Exception("Non-Kotlin exception: deliberately a Kotlin programmer error")
        assertSame(plain, assertFailsWith<Exception> {
            loadWebBundledFonts { path -> readWebBundledFontBytes(path) { throw plain } }
        })
        assertSame(plain, assertFailsWith<Exception> {
            loadWebBundledFonts { path -> readWebBundledFontBytes(path) { programmerRejectedFontRequest(plain.toJsReference()) } }
        })
        assertFailsWith<JsException> { loadWebBundledFonts { throwWebFontProgrammerError(); error("unreachable") } }
    }

    @Test fun suspendedReadIsCanceledWithoutReadingTheNextAsset() = runTest {
        var reads = 0
        var canceled = false
        val load = launch {
            loadWebBundledFonts {
                reads++
                try { awaitCancellation() } finally { canceled = true }
            }
        }
        yield()
        load.cancelAndJoin()
        assertTrue(canceled)
        assertEquals(1, reads)
    }

    @Test fun readReturningAfterCancellationCannotBecomeFallbackSuccess() = runTest {
        var canceled = false
        var returned = false
        val load = launch {
            try {
                loadWebBundledFonts { currentCoroutineContext().cancel(); byteArrayOf() }
                returned = true
            } catch (_: CancellationException) { canceled = true }
        }
        load.join()
        assertTrue(canceled)
        assertFalse(returned)
    }

    @Test fun actualBundledBytesPreserveEveryFamilyAndWeight() = runComposeUiTest {
        val mapping = WebSearchFonts()
        var fonts: CvFonts? = null
        val reads = mutableListOf<String>()
        try {
            setContent {
                ProvideWebBundledFonts(load = { loadWebBundledFonts { path ->
                    reads += path
                    readWebBundledFontBytes(path)
                } }) {
                    CineVerseTheme(ShellStyle.Web) {
                        val selected = LocalCvFonts.current
                        SideEffect { fonts = selected }
                        Text("Bundled body", style = bodyStyle(16.sp))
                        Text("Bundled display", style = displayStyle(24.sp))
                    }
                }
            }
            waitUntil(timeoutMillis = 10_000) { fonts?.body is FontListFontFamily }
            waitForIdle()
            val loaded = assertNotNull(fonts)
            assertEquals(listOf(500, 700, 800), (loaded.display as FontListFontFamily).fonts.map { it.weight.weight })
            assertEquals(listOf(400, 500, 600, 700), (loaded.body as FontListFontFamily).fonts.map { it.weight.weight })
            val actual = (loaded.display as FontListFontFamily).fonts + (loaded.body as FontListFontFamily).fonts
            assertTrue(actual.all { it is LoadedFont && it.data.isNotEmpty() })
            assertEquals(listOf("Bricolage Grotesque", "Bricolage Grotesque", "Bricolage Grotesque", "Inter", "Inter", "Inter", "Inter"), actual.map { font ->
                Data.makeFromBytes((font as LoadedFont).data).use { data ->
                    assertNotNull(FontMgr.default.makeFromData(data)).use { it.familyName }
                }
            })
            assertEquals(WebBundledFontAssets.map { it.path }, reads)
            assertEquals(7, reads.toSet().size)
        } finally { mapping.close() }
    }

    @Test fun failureOfTheLastAssetDoesNotPublishAPartialFamily() = runComposeUiTest {
        val mapping = WebSearchFonts()
        var finished = false
        var result: CvFonts? = WebFallbackFonts
        var selected: CvFonts? = null
        val reads = mutableListOf<String>()
        try {
            setContent {
                ProvideWebBundledFonts(load = {
                    result = loadWebBundledFonts { path ->
                        reads += path
                        if (path == WebBundledFontAssets.last().path) throw MissingResourceException(path)
                        readWebBundledFontBytes(path)
                    }
                    finished = true
                    result
                }) {
                    val fonts = LocalCvFontOverride.current
                    SideEffect { selected = fonts }
                }
            }
            waitUntil(timeoutMillis = 10_000) { finished }
            assertNull(result)
            assertSame(WebFallbackFonts, selected)
            assertEquals(WebBundledFontAssets.map { it.path }, reads)
        } finally { mapping.close() }
    }

    @Test fun defaultNullHookLeavesTheUnprovidedBundledThemePathIntact() = runComposeUiTest {
        val mapping = WebSearchFonts()
        var defaultIsNull = false
        var nativeStyleWeights = emptyList<FontWeight>()
        try {
            setContent {
                val override = LocalCvFontOverride.current
                SideEffect { defaultIsNull = override == null }
                mapping.Load()
                CineVerseTheme(ShellStyle.Mobile) {
                    val fonts = LocalCvFonts.current
                    SideEffect { nativeStyleWeights = (fonts.display as FontListFontFamily).fonts.map { it.weight } }
                }
            }
            mapping.verifyLoaded { waitForIdle() }
            assertTrue(defaultIsNull)
            assertEquals(listOf(FontWeight.Medium, FontWeight.Bold, FontWeight.ExtraBold), nativeStyleWeights)
        } finally { mapping.close() }
    }
}

// Native Promise/Response/ReadableStream failures, no network or global fetch replacement.
@JsFun("""kind => {
 const controller = new AbortController();
 let promise;
 if (kind === 'network') promise = Promise.reject(new TypeError('owned font fetch reset'));
 else if (kind === '404') promise = Promise.resolve(new Response('missing', {status:404}));
 else if (kind === 'body') promise = Promise.resolve(new Response(new ReadableStream({
  start(stream) { stream.error(new TypeError('owned font body reset')); }
 })));
 else if (kind === 'html') promise = Promise.resolve(new Response('<html>not a font</html>'));
 else if (kind === 'bytes') promise = Promise.resolve(new Response(new Uint8Array([0,127,128,255])));
 else if (kind === 'pending') promise = new Promise((resolve, reject) => {
  controller.signal.addEventListener('abort', () => reject(new DOMException('owned request canceled', 'AbortError')), {once:true});
 });
 else throw new Error('Unknown font fixture');
 return {promise, abort: () => controller.abort(), get aborted() { return controller.signal.aborted; }};
}""")
internal external fun webFontRequestFixture(kind: String): WebFontRequest

@JsFun("request => request.aborted")
private external fun webFontRequestAborted(request: WebFontRequest): Boolean

@JsFun("error => ({promise: Promise.reject(error), abort: () => {}})")
private external fun programmerRejectedFontRequest(error: JsAny): WebFontRequest

@JsFun("() => { throw new TypeError('owned font programmer error'); }")
private external fun throwWebFontProgrammerError()
