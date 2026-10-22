package com.techgv.cineverse.server

import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** Server configuration; [fromEnv] is the only production source. Token values never appear in [toString]. */
class ServerConfig(
    val readToken: String,
    /** Public origin of this server (`scheme://host[:port]`), which also serves the web bundle and `/api`. */
    val publicOrigin: String,
    val port: Int = 8080,
    val cookieSecure: Boolean = true,
    val sessionTtl: Duration = 30.days,
    /** Directory with the wasm bundle served under `/`; null → API only. */
    val staticDir: String? = null,
    /** Extra browser origin allowed via CORS (local web dev server); null → same-origin only, no CORS. */
    val corsOrigin: String? = null,
    /** Honour `X-Forwarded-*` from a TLS-terminating reverse proxy (client IP for rate limits). */
    val trustProxy: Boolean = false,
    val attemptTtl: Duration = 15.minutes,
    val authRequestsPerMinute: Int = 20,
    val mutationRequestsPerMinute: Int = 60,
) {
    init {
        require(readToken.isNotBlank()) { "TMDB_READ_ACCESS_TOKEN is required" }
        requireOrigin(publicOrigin, "PUBLIC_ORIGIN")
        corsOrigin?.let { requireOrigin(it, "CORS_ORIGIN") }
        require(cookieSecure || URI(publicOrigin).host in localHosts) {
            "COOKIE_SECURE=false is only allowed for a localhost PUBLIC_ORIGIN"
        }
        require(sessionTtl.isPositive()) { "SESSION_TTL must be positive" }
    }

    /** Origins whose requests may change state (CSRF Origin check). */
    val allowedOrigins: Set<String> = setOfNotNull(publicOrigin, corsOrigin)

    override fun toString() = "ServerConfig(publicOrigin=$publicOrigin, port=$port, cookieSecure=$cookieSecure, " +
        "sessionTtl=$sessionTtl, staticDir=$staticDir, corsOrigin=$corsOrigin, trustProxy=$trustProxy)"

    companion object {
        private val localHosts = setOf("localhost", "127.0.0.1", "[::1]")

        private fun requireOrigin(value: String, name: String) {
            val uri = runCatching { URI(value) }.getOrNull()
            require(
                uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() &&
                    uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null,
            ) { "$name must be an origin like https://cineverse.example (no path or trailing slash)" }
        }

        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val port = env["PORT"]?.toIntOrNull() ?: 8080
            return ServerConfig(
                readToken = env["TMDB_READ_ACCESS_TOKEN"].orEmpty(),
                publicOrigin = env["PUBLIC_ORIGIN"] ?: "http://localhost:$port",
                port = port,
                cookieSecure = env["COOKIE_SECURE"]?.toBooleanStrict() ?: true,
                sessionTtl = env["SESSION_TTL"]?.let(Duration::parse) ?: 30.days,
                staticDir = env["STATIC_DIR"]?.takeIf { it.isNotBlank() },
                corsOrigin = env["CORS_ORIGIN"]?.takeIf { it.isNotBlank() },
                trustProxy = env["TRUST_PROXY"]?.toBooleanStrict() ?: false,
            )
        }
    }
}
