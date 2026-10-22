package com.techgv.cineverse.data.tmdb

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher

/**
 * TMDB behind a Ktor [MockEngine] with the real [tmdbHttpClient] and [KtorTmdbApi]: [handler] answers each request
 * (default: `{}` with 200); every request is recorded in [requests]. Pass the test dispatcher to keep calls on
 * virtual time.
 */
class MockTmdb(
    dispatcher: CoroutineDispatcher? = null,
    readToken: String = "read-token",
    configure: HttpClientConfig<*>.() -> Unit = {},
    var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { ok("{}") },
) {
    val requests = mutableListOf<HttpRequestData>()
    val engine = MockEngine(
        MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
                requests += request
                handler(request)
            }
        },
    )
    val client = tmdbHttpClient(engine, Transport.Direct(ReadToken(readToken)), configure)
    val api = KtorTmdbApi(client)
    val last: HttpRequestData get() = requests.last()
}

fun MockRequestHandleScope.ok(body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg headers: Pair<String, String>) =
    respond(body, status, headersOf(*(headers.map { it.first to listOf(it.second) } + (HttpHeaders.ContentType to listOf("application/json"))).toTypedArray()))

/** `/3/movie/popular?language=en-US&page=1`: path and query of a recorded request. */
val HttpRequestData.pathAndQuery: String get() = url.encodedPath + (url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")

val HttpRequestData.bodyText: String get() = (body as? TextContent)?.text ?: (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

val HttpRequestData.authorization: String? get() = headers[HttpHeaders.Authorization]

fun Url.query(name: String): String? = parameters[name]
