@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import com.techgv.cineverse.ui.resources.Res
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.compose.resources.MissingResourceException
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.impl.use
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.w3c.fetch.Response
import kotlin.js.JsAny
import kotlin.js.Promise

internal val WebFallbackFonts = CvFonts(FontFamily.Default, FontFamily.Default)

internal data class WebBundledFontAsset(val path: String, val weight: FontWeight)

// Keep the same family ordering and seven weights as the common/native resource theme.
internal val WebBundledFontAssets = listOf(
    WebBundledFontAsset("font/bricolage_grotesque_medium.ttf", FontWeight.Medium),
    WebBundledFontAsset("font/bricolage_grotesque_bold.ttf", FontWeight.Bold),
    WebBundledFontAsset("font/bricolage_grotesque_extrabold.ttf", FontWeight.ExtraBold),
    WebBundledFontAsset("font/inter_regular.ttf", FontWeight.Normal),
    WebBundledFontAsset("font/inter_medium.ttf", FontWeight.Medium),
    WebBundledFontAsset("font/inter_semibold.ttf", FontWeight.SemiBold),
    WebBundledFontAsset("font/inter_bold.ttf", FontWeight.Bold),
)

internal class WebFontAssetReadException : Exception()

internal external interface WebFontRequest : JsAny {
    val promise: Promise<Response>
    fun abort()
}

private external interface WebFontBytes : JsAny {
    val bytes: Int8Array?
}

/** Public resource mapping is shared with native/Search; only this owned font request is abortable. */
internal suspend fun readWebBundledFontBytes(
    path: String,
    start: (String) -> WebFontRequest = ::startWebFontRequest,
): ByteArray {
    val uri = Res.getUri(path)
    currentCoroutineContext().ensureActive()
    val request = start(uri)
    var complete = false
    try {
        val result = readWebFontResponse(request.promise).await()
        currentCoroutineContext().ensureActive()
        val bytes = result.bytes ?: throw WebFontAssetReadException()
        val copy = ByteArray(bytes.length) { bytes[it] }
        complete = true
        return copy
    } finally {
        if (!complete) request.abort()
    }
}

@JsFun("""url => {
 const controller = new AbortController();
 return {promise: fetch(url, {signal: controller.signal}), abort: () => controller.abort()};
}""")
private external fun startWebFontRequest(url: String): WebFontRequest

// Normalize only native fetch/HTTP/body failures before Promise.await erases their JS error type.
@JsFun("""promise => {
 const failed = error => {
  if (error instanceof TypeError || (error instanceof DOMException && error.name === 'AbortError')) return {bytes: null};
  throw error;
 };
 return promise.then(
  response => response.ok
   ? response.arrayBuffer().then(buffer => ({bytes: new Int8Array(buffer)}), failed)
   : ({bytes: null}),
  failed
 );
}""")
private external fun readWebFontResponse(promise: Promise<Response>): Promise<WebFontBytes>

/** No resource composable/preload: every read and decode belongs to this cancellable load. */
internal suspend fun loadWebBundledFonts(read: suspend (String) -> ByteArray = { readWebBundledFontBytes(it) }): CvFonts? {
    val fonts = WebBundledFontAssets.map { asset ->
        currentCoroutineContext().ensureActive()
        val bytes = try {
            read(asset.path)
        } catch (_: MissingResourceException) {
            currentCoroutineContext().ensureActive()
            return null
        } catch (_: WebFontAssetReadException) {
            currentCoroutineContext().ensureActive()
            return null
        }
        currentCoroutineContext().ensureActive()
        val valid = Data.makeFromBytes(bytes).use { data ->
            FontMgr.default.makeFromData(data)?.use { true } ?: false
        }
        if (!valid) return null
        Font(identity = "cineverse.bundled/${asset.path}", data = bytes, weight = asset.weight)
    }
    currentCoroutineContext().ensureActive()
    return CvFonts(FontFamily(fonts.take(3)), FontFamily(fonts.drop(3)))
}

/** Content mounts immediately and stays mounted when typography changes; no automatic retry. */
@Composable
internal fun ProvideWebBundledFonts(
    load: suspend () -> CvFonts? = { loadWebBundledFonts() },
    content: @Composable () -> Unit,
) {
    var fonts by remember { mutableStateOf(WebFallbackFonts) }
    LaunchedEffect(Unit) {
        val loaded = load()
        currentCoroutineContext().ensureActive()
        if (loaded != null) fonts = loaded
    }
    CompositionLocalProvider(LocalCvFontOverride provides fonts, content = content)
}
