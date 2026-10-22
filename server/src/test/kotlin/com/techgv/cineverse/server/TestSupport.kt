package com.techgv.cineverse.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals

const val READ_TOKEN = "READ-TOKEN-SENTINEL-7f3a9c"
const val ORIGIN = "https://cine.example"

fun testConfig(
    cookieSecure: Boolean = true,
    authPerMinute: Int = 1_000,
    mutationPerMinute: Int = 1_000,
    staticDir: String? = null,
    corsOrigin: String? = null,
    trustProxy: Boolean = false,
    accountReadsPerMinute: Int = 60,
    upstreamConcurrency: Int = 32,
) = ServerConfig(
    readToken = READ_TOKEN, publicOrigin = ORIGIN, cookieSecure = cookieSecure, staticDir = staticDir,
    corsOrigin = corsOrigin, trustProxy = trustProxy,
    authRequestsPerMinute = authPerMinute, mutationRequestsPerMinute = mutationPerMinute,
    accountReadsPerMinute = accountReadsPerMinute, upstreamConcurrency = upstreamConcurrency,
)

class TestClock(start: Long = 1_000_000L) {
    private val now = AtomicLong(start)

    /** Runs on every server clock read (lets a test see that a request reached its session lookup). */
    @Volatile var onRead: (() -> Unit)? = null

    fun now() = now.get().also { onRead?.invoke() }
    fun advanceMinutes(minutes: Long) = now.addAndGet(minutes * 60_000)
}

/** What the fake TMDB received. */
class Recorded(val method: HttpMethod, val url: Url, val authorization: String?, val body: String?, val cookie: String?) {
    val path: String get() = url.encodedPath
    val json: JsonObject get() = Json.parseToJsonElement(body!!).jsonObject
}

class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

fun tmdbError(status: Int, statusCode: Int) = Reply(status, """{"success":false,"status_code":$statusCode,"status_message":"secret-free upstream text"}""")

/**
 * Fake api.themoviedb.org. Two accounts: "A" (alice) and "B" (bob). Credentials are derived from the account so
 * tests can see which account's credentials reached TMDB. [overrides] (key "METHOD /path") win over defaults.
 */
class FakeTmdb {
    val requests = CopyOnWriteArrayList<Recorded>()
    val overrides = mutableMapOf<String, (Recorded) -> Reply>()
    private val approvals = mutableMapOf<String, String>()
    private val tokens = AtomicInteger()
    private val counter = AtomicInteger()

    fun approve(requestToken: String, account: String) { approvals[requestToken] = account }
    fun calls(method: HttpMethod, path: String) = requests.count { it.method == method && it.path == path }
    fun last(method: HttpMethod, path: String) = requests.last { it.method == method && it.path == path }

    val engine = MockEngine { request -> handle(request) }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val body = (request.body as? TextContent)?.text ?: (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
        val recorded = Recorded(request.method, request.url, request.headers[HttpHeaders.Authorization], body, request.headers[HttpHeaders.Cookie])
        requests += recorded
        val reply = overrides["${request.method.value} ${recorded.path}"]?.invoke(recorded) ?: default(recorded)
        val headers = (reply.headers + (HttpHeaders.ContentType to "application/json")).mapValues { listOf(it.value) }
        return respond(reply.body, HttpStatusCode.fromValue(reply.status), headersOf(*headers.toList().toTypedArray()))
    }

    private fun accountOfSessionId(id: String?) = id?.removePrefix("SID-")
    private fun accountOfBearer(auth: String?) = auth?.removePrefix("Bearer UAT-")

    private fun default(r: Recorded): Reply {
        val path = r.path
        return when {
            r.method == HttpMethod.Post && path == "/4/auth/request_token" ->
                Reply(200, """{"success":true,"status_code":1,"request_token":"RT-${tokens.incrementAndGet()}"}""")
            r.method == HttpMethod.Post && path == "/4/auth/access_token" -> {
                val account = approvals[r.json["request_token"]!!.jsonPrimitive.content] ?: return tmdbError(422, 41)
                Reply(200, """{"success":true,"access_token":"UAT-$account","account_id":"AOID-$account"}""")
            }
            r.method == HttpMethod.Post && path == "/3/authentication/session/convert/4" ->
                Reply(200, """{"success":true,"session_id":"SID-${r.json["access_token"]!!.jsonPrimitive.content.removePrefix("UAT-")}"}""")
            r.method == HttpMethod.Get && path == "/3/account" -> when (accountOfSessionId(r.url.parameters["session_id"])) {
                "A" -> Reply(200, """{"id":101,"username":"alice","name":"Alice"}""")
                "B" -> Reply(200, """{"id":202,"username":"bob","name":""}""")
                else -> tmdbError(401, 3)
            }
            r.method == HttpMethod.Delete -> Reply(200, """{"success":true,"status_code":13}""")
            path.startsWith("/4/account/") -> {
                val owner = accountOfBearer(r.authorization)
                if (!path.startsWith("/4/account/AOID-$owner/")) tmdbError(401, 36)
                else Reply(200, """{"page":1,"results":[{"id":1,"owner":"$owner"}],"total_results":1}""")
            }
            path.endsWith("/account_states") ->
                Reply(200, """{"id":550,"owner":"${accountOfSessionId(r.url.parameters["session_id"])}","watchlist":true}""")
            r.method == HttpMethod.Post -> Reply(201, """{"success":true,"status_code":1}""")
            else -> Reply(200, """{"path":"$path","n":${counter.incrementAndGet()}}""")
        }
    }
}

fun serverTest(
    fake: FakeTmdb = FakeTmdb(),
    config: ServerConfig = testConfig(),
    clock: TestClock = TestClock(),
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application { cineVerse(config, fake.engine, clock::now) }
    block()
}

/** A browser with its own cookie jar; non-GET requests carry Origin + the CSRF double-submit header by default. */
class Browser(private val client: HttpClient) {
    val cookies = linkedMapOf<String, String>()
    var lastSetCookies = emptyList<io.ktor.http.Cookie>()

    suspend fun send(
        method: HttpMethod,
        path: String,
        json: String? = null,
        origin: String? = ORIGIN,
        csrf: String? = null,
        headers: Map<String, String> = emptyMap(),
        session: AuthSession? = null,
        content: OutgoingContent? = null,
    ): HttpResponse {
        val response = client.request(path) {
            this.method = method
            if (cookies.isNotEmpty()) header(HttpHeaders.Cookie, cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (method != HttpMethod.Get) {
                origin?.let { header(HttpHeaders.Origin, it) }
                (csrf ?: cookies[CSRF_COOKIE])?.let { header(CSRF_HEADER, it) }
            }
            session?.let {
                header(EXPECTED_GENERATION_HEADER, it.sessionGeneration.toString())
                header(EXPECTED_ACCOUNT_HEADER, it.accountObjectId!!)
            }
            headers.forEach { (k, v) -> header(k, v) }
            if (json != null) {
                contentType(ContentType.Application.Json)
                setBody(json)
            }
            content?.let(::setBody)
        }
        lastSetCookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty().map(::parseServerSetCookieHeader)
        lastSetCookies.forEach { if (it.maxAge == 0) cookies.remove(it.name) else cookies[it.name] = it.value }
        return response
    }

    suspend fun get(path: String, headers: Map<String, String> = emptyMap()) = send(HttpMethod.Get, path, headers = headers)

    suspend fun session(): AuthSession = Json.decodeFromString(get("/api/session").bodyAsText())

    /** Starts an attempt; returns (attemptId, requestToken). */
    suspend fun start(): Pair<String, String> {
        if (CSRF_COOKIE !in cookies) get("/api/session")
        val response = send(HttpMethod.Post, "/api/auth/start")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val requestToken = Url(json["approveUrl"]!!.jsonPrimitive.content).parameters["request_token"]!!
        return json["attemptId"]!!.jsonPrimitive.content to requestToken
    }

    suspend fun callback(attemptId: String) = get("/api/auth/callback?attempt=$attemptId")

    suspend fun signIn(fake: FakeTmdb, account: String): AuthSession {
        val (attemptId, requestToken) = start()
        fake.approve(requestToken, account)
        val response = callback(attemptId)
        assertEquals(HttpStatusCode.Found, response.status, response.bodyAsText())
        return session()
    }
}

fun ApplicationTestBuilder.browser() = Browser(createClient { followRedirects = false })
