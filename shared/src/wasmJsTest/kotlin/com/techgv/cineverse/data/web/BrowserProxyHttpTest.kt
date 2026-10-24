package com.techgv.cineverse.data.web

import io.ktor.client.request.get
import io.ktor.http.HttpMethod
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private fun installFetchProbe(): Unit = js("""{
    globalThis.cvOriginalFetch = globalThis.fetch;
    globalThis.cvFetchCalls = [];
    globalThis.fetch = function(url, options) {
        globalThis.cvFetchCalls.push({url: String(url), credentials: options.credentials, mode: options.mode, redirect: options.redirect, csrf: new Headers(options.headers).get('X-CV-CSRF')});
        return Promise.resolve(new Response('{}', {status: 200, headers: {'Content-Type': 'application/json'}}));
    };
}""")
private fun restoreFetch(): Unit = js("""{ globalThis.fetch = globalThis.cvOriginalFetch; delete globalThis.cvOriginalFetch; delete globalThis.cvFetchCalls; }""")
private fun probeCount(): Int = js("globalThis.cvFetchCalls.length")
private fun probeField(index: Int, field: String): String = js("String(globalThis.cvFetchCalls[index][field])")

class BrowserProxyHttpTest {
    @Test fun actualWasmEngineIncludesCookiesAndRejectsCrossOriginBeforeFetch() = runTest {
        installFetchProbe()
        val client = browserProxyHttpClient()
        try {
            client.get("/api/session")
            assertEquals(window.location.origin + "/api/session", probeField(0, "url"))
            assertEquals("include", probeField(0, "credentials"))
            assertEquals("same-origin", probeField(0, "mode"))
            assertEquals("error", probeField(0, "redirect"))
            assertFailsWith<IllegalArgumentException> { client.get("https://other.invalid/api/session") }
            assertFailsWith<IllegalArgumentException> { client.get("/api/account/movie/rated?session_id=SECRET") }
            assertEquals(1, probeCount())
            document.cookie = "cv_csrf=first; Path=/; SameSite=Strict"
            document.cookie = "other_cookie=ignored; Path=/"
            val http = WebProxyHttp(client, ::browserCsrfCookie)
            http.call("/api/auth/cancel", method = HttpMethod.Post)
            document.cookie = "cv_csrf=second; Path=/; SameSite=Strict"
            http.call("/api/auth/logout", method = HttpMethod.Post)
            assertEquals("first", probeField(1, "csrf")); assertEquals("second", probeField(2, "csrf"))
            document.cookie = "cv_csrf=bad%0Avalue; Path=/"
            assertNull(browserCsrfCookie())
        } finally {
            client.close()
            document.cookie = "cv_csrf=; Max-Age=0; Path=/"
            document.cookie = "other_cookie=; Max-Age=0; Path=/"
            restoreFetch()
        }
    }
}
