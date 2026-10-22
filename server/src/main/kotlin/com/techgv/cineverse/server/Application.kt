package com.techgv.cineverse.server

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File
import kotlin.system.exitProcess

fun main() {
    val config = try {
        ServerConfig.fromEnv()
    } catch (e: IllegalArgumentException) {
        System.err.println("Invalid configuration: ${e.message}")
        exitProcess(1)
    }
    embeddedServer(CIO, port = config.port) { cineVerse(config) }.start(wait = true)
}

/**
 * Same-origin layout: the wasm bundle under `/` (when [ServerConfig.staticDir] is set) and the API under `/api`.
 * [upstreamEngine] and [nowMillis] are replaced in tests (fake TMDB, controllable clock).
 */
fun Application.cineVerse(
    config: ServerConfig,
    upstreamEngine: HttpClientEngine = Java.create(),
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    if (config.trustProxy) install(XForwardedHeaders) { useLastProxy() }
    config.corsOrigin?.takeIf { it != config.publicOrigin }?.let { origin ->
        install(CORS) {
            allowOrigins { it == origin }
            allowCredentials = true
            allowMethod(HttpMethod.Delete)
            listOf("Content-Type", CSRF_HEADER, EXPECTED_GENERATION_HEADER, EXPECTED_ACCOUNT_HEADER).forEach(::allowHeader)
        }
    }
    installSecurity(config)

    val tmdb = Tmdb(tmdbHttpClient(upstreamEngine), config.readToken)
    monitor.subscribe(ApplicationStopped) { tmdb.close() }
    val cache = CatalogCache(maxEntries = 1_000, nowMillis)
    val store = AuthStore(config, nowMillis)
    val cookies = Cookies(config)
    val sessions = SessionLookup(store, cookies)

    routing {
        get("/healthz") { call.respondText("ok") }
        catalogRoutes(tmdb, cache)
        authRoutes(config, tmdb, store, cookies, sessions)
        accountRoutes(config, tmdb, sessions)
        // Any other /api request (path, method) is rejected, never served the web bundle.
        route("/api/{...}") { handle { throw NOT_ALLOWED } }
        config.staticDir?.let { dir -> staticFiles("/", File(dir)) { default("index.html") } }
    }
}
