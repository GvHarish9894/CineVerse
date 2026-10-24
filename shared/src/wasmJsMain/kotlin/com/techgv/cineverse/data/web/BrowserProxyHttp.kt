package com.techgv.cineverse.data.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpSendPipeline
import io.ktor.http.encodedPath
import io.ktor.http.Url
import kotlinx.browser.document
import kotlinx.browser.window

/** Dedicated same-origin proxy client. Never reuse the native bearer client or the image client. */
fun browserProxyHttpClient(): HttpClient {
    val origin = Url(window.location.origin)
    return HttpClient(Js) {
        expectSuccess = false
        followRedirects = false
        defaultRequest { url(window.location.origin) }
        engine {
            configureRequest {
                credentials = "include".toJsString()
                mode = "same-origin".toJsString()
                redirect = "error".toJsString()
                cache = "no-store".toJsString()
            }
        }
    }.also { client ->
        client.sendPipeline.intercept(HttpSendPipeline.State) {
            val url = context.url
            require(url.protocol == origin.protocol && url.host == origin.host && url.port == origin.port &&
                url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.encodedPath.startsWith("/api/")) { "Proxy requests must stay same-origin under /api/" }
            require(context.headers["Authorization"] == null && context.headers["Cookie"] == null &&
                url.parameters.names().none { it.lowercase() in setOf("session_id", "api_key", "access_token", "request_token") }) { "Proxy requests cannot contain credentials" }
            proceed()
        }
    }
}

/** Reads only the current double-submit cookie; HttpOnly credentials are neither read nor copied. */
fun browserCsrfCookie(): String? = document.cookie.split(';').map { it.trim() }
    .firstOrNull { it.startsWith("cv_csrf=") }?.substringAfter('=')?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
