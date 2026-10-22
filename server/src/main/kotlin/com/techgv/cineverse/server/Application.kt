package com.techgv.cineverse.server

import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(CIO, port = port, module = Application::module).start(wait = true)
}

fun Application.module() {
    // The TMDB token lives only in the server environment (never in the wasm bundle); the proxy uses it from P6.
    if (System.getenv("TMDB_READ_ACCESS_TOKEN").isNullOrBlank()) {
        log.warn("TMDB_READ_ACCESS_TOKEN is not set; TMDB proxy calls will fail")
    }
    routing {
        get("/healthz") { call.respondText("ok") }
    }
}
