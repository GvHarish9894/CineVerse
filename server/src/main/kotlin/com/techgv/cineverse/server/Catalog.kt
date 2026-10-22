package com.techgv.cineverse.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Cache class of a catalog endpoint (spec §3.4); [maxAge] is what browsers may cache. */
enum class CacheTtl(val ttl: Duration, val maxAge: Duration) {
    List(10.minutes, 10.minutes),
    Detail(1.hours, 1.hours),
    // Process lifetime on the server; browsers revalidate daily.
    Static(Duration.INFINITE, 1.days),
}

private typealias Check = (String) -> Boolean

private fun matches(regex: String): Check = Regex(regex).let { r -> { r.matches(it) } }
private fun oneOf(vararg values: String): Check = { it in values }
private fun appendOf(vararg values: String): Check = { value ->
    val parts = value.split(',')
    parts.size <= values.size && parts.toSet().size == parts.size && parts.all { it in values }
}

private val language = "language" to matches("[a-z]{2}(-[A-Z]{2})?")
private val region = "region" to matches("[A-Z]{2}")
private val page = "page" to { v: String -> v.toIntOrNull() in 1..500 }
private val timezone = "timezone" to matches("[A-Za-z0-9_+\\-]{1,32}(/[A-Za-z0-9_+\\-]{1,32}){0,2}")
private val includeAdult = "include_adult" to oneOf("true", "false")
private val query = "query" to { v: String -> v.isNotBlank() && v.length <= 200 }
private val withGenres = "with_genres" to matches("\\d{1,6}([,|]\\d{1,6}){0,19}")
private val voteCountGte = "vote_count.gte" to matches("\\d{1,6}")

private const val ID = "[1-9]\\d{0,9}"

private class CatalogRule(path: String, val ttl: CacheTtl, vararg params: Pair<String, Check>) {
    val path = Regex(path)
    val params = params.toMap()
}

/** Exact v3 GET paths and query params the web app may use (spec §3.5/§3.2, web kit). Everything else is 404. */
private val catalogRules = listOf(
    CatalogRule("configuration", CacheTtl.Static),
    CatalogRule("genre/(movie|tv)/list", CacheTtl.Static, language),
    CatalogRule("trending/all/(day|week)", CacheTtl.List, language, page),
    CatalogRule("movie/(popular|now_playing|upcoming|top_rated)", CacheTtl.List, language, region, page),
    CatalogRule("tv/(popular|top_rated)", CacheTtl.List, language, page),
    CatalogRule("tv/(airing_today|on_the_air)", CacheTtl.List, language, timezone, page),
    CatalogRule("person/popular", CacheTtl.List, language, page),
    CatalogRule(
        "discover/movie", CacheTtl.List, language, region, page, withGenres, voteCountGte,
        "sort_by" to oneOf("popularity.desc", "vote_average.desc", "primary_release_date.desc", "title.asc"),
    ),
    CatalogRule(
        "discover/tv", CacheTtl.List, language, page, withGenres, voteCountGte,
        "sort_by" to oneOf("popularity.desc", "vote_average.desc", "first_air_date.desc", "name.asc"),
    ),
    CatalogRule("search/multi", CacheTtl.List, query, language, includeAdult, page),
    CatalogRule(
        "movie/$ID", CacheTtl.Detail, language,
        "append_to_response" to appendOf("credits", "videos", "recommendations", "reviews", "release_dates", "keywords"),
    ),
    CatalogRule(
        "tv/$ID", CacheTtl.Detail, language,
        "append_to_response" to appendOf(
            "aggregate_credits", "credits", "videos", "recommendations", "reviews", "content_ratings", "keywords",
        ),
    ),
    CatalogRule("(movie|tv)/$ID/videos", CacheTtl.Detail, language),
    CatalogRule("(movie|tv)/$ID/keywords", CacheTtl.Detail),
    CatalogRule("tv/$ID/season/\\d{1,3}", CacheTtl.Detail, language),
    CatalogRule("person/$ID", CacheTtl.Detail, language, "append_to_response" to appendOf("combined_credits")),
)

val NOT_ALLOWED = ApiError(HttpStatusCode.NotFound, "not_allowed")

/**
 * Validates query [parameters] against [allowed]: every name allowed, present once, value passing its check.
 * Returns them sorted by name — the canonical form used both upstream and as the cache key.
 */
fun canonicalQuery(parameters: Parameters, allowed: Map<String, Check>): List<Pair<String, String>> =
    parameters.names().sorted().map { name ->
        val values = parameters.getAll(name).orEmpty()
        val check = allowed[name]
        if (check == null || values.size != 1 || !check(values[0])) throw NOT_ALLOWED
        name to values[0]
    }

/** Validates an account-route query against the same rules as the catalog. */
fun accountListQuery(parameters: Parameters) = canonicalQuery(
    parameters,
    mapOf(language, page, "sort_by" to oneOf("created_at.asc", "created_at.desc")),
)

/** In-memory LRU with per-entry expiry for public catalog bodies only. */
class CatalogCache(private val maxEntries: Int, private val nowMillis: () -> Long) {
    private class Entry(val body: String, val expiresAt: Long)

    // shortcut: bounded by entry count, not bytes (detail bodies ≲ 0.5 MB); bound by size if memory becomes tight.
    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > maxEntries
    }

    @Synchronized
    fun get(key: String): String? {
        val entry = entries[key] ?: return null
        if (nowMillis() >= entry.expiresAt) {
            entries.remove(key)
            return null
        }
        return entry.body
    }

    @Synchronized
    fun put(key: String, body: String, ttl: Duration) {
        val expiresAt = if (ttl.isInfinite()) Long.MAX_VALUE else nowMillis() + ttl.inWholeMilliseconds
        entries[key] = Entry(body, expiresAt)
    }

    private val loading = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** Single flight: concurrent misses of one [key] share one [fetch]; its body is cached for [ttl]. */
    suspend fun load(key: String, ttl: Duration, fetch: suspend () -> String): String {
        val mine = CompletableDeferred<String>()
        loading.putIfAbsent(key, mine)?.let { return it.await() }
        try {
            // Not cancelled with the leading request: the others wait for this result.
            val body = get(key) ?: withContext(NonCancellable) { fetch() }.also { put(key, it, ttl) }
            mine.complete(body)
            return body
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            loading.remove(key, mine)
        }
    }
}

/**
 * `GET /api/tmdb/{v3 path}`: allow-listed public catalog reads with the server's read token. The client's own
 * headers (incl. `Authorization`, cookies) are never forwarded; only canonical allow-listed params are.
 */
fun Route.catalogRoutes(tmdb: Tmdb, cache: CatalogCache, missBudget: ClientBudget) {
    get("/api/tmdb/{path...}") {
        val path = call.parameters.getAll("path").orEmpty().joinToString("/")
        val rule = catalogRules.firstOrNull { it.path.matches(path) } ?: throw NOT_ALLOWED
        val query = canonicalQuery(call.request.queryParameters, rule.params)
        val key = path + "?" + query.joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }
        val body = cache.get(key) ?: run {
            missBudget.consume(call.clientIp())
            cache.load(key, rule.ttl.ttl) {
                // Public, idempotent GET: one retry on a transport failure (writes and auth calls are never retried).
                val upstream = try {
                    tmdb.call(HttpMethod.Get, "3/$path", query)
                } catch (_: IOException) {
                    tmdb.call(HttpMethod.Get, "3/$path", query)
                }
                if (upstream.status != 200) throw classify(TmdbOperation.Catalog, upstream)
                upstream.body
            }
        }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=${rule.ttl.maxAge.inWholeSeconds}")
        call.respondText(body, ContentType.Application.Json)
    }
}
