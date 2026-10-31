@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.techgv.cineverse.presentation.StateSaver
import com.techgv.cineverse.presentation.BrowseSelection
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.w3c.dom.events.Event

interface WebHistory {
    val url: String
    val saved: String
    fun push(url: String, saved: String)
    fun replace(url: String, saved: String)
    fun listen(onPop: () -> Unit): () -> Unit
}

class BrowserHistory : WebHistory {
    override val url get() = window.location.pathname + window.location.search + window.location.hash
    override val saved get() = historySaved()
    override fun push(url: String, saved: String) = historyWrite(url, saved, false)
    override fun replace(url: String, saved: String) = historyWrite(url, saved, true)
    override fun listen(onPop: () -> Unit): () -> Unit {
        val listener: (Event) -> Unit = { onPop() }
        window.addEventListener("popstate", listener)
        return { window.removeEventListener("popstate", listener) }
    }
}

@JsFun("() => !Object.prototype.hasOwnProperty.call(history.state ?? {}, 'cineverse') ? '' : typeof history.state.cineverse === 'string' && history.state.cineverse.length > 0 ? history.state.cineverse : 'INVALID_HISTORY_STATE'")
private external fun historySaved(): String
@JsFun("(url, saved, replace) => { const state = {...history.state, cineverse:saved}; history[replace ? 'replaceState' : 'pushState'](state, '', url); }")
private external fun historyWrite(url: String, saved: String, replace: Boolean)

class WebEntry internal constructor(route: WebRoute, private val values: MutableMap<String, String>) : StateSaver {
    var route: WebRoute by mutableStateOf(route)
        private set
    internal fun replaceSearch(next: WebRoute.Search, write: (String) -> Unit) {
        val previous = route as WebRoute.Search
        val updated = values.toMutableMap()
        if (previous.query.trim() != next.query.trim() || previous.facet != next.facet) {
            updated.keys.removeAll { it in setOf("search.page", "search.index", "search.offset") }
        }
        next.saveSearchInput(updated)
        // Publish only after history accepted the complete route/scalar transaction.
        write(Json.encodeToString(updated))
        values.clear()
        values.putAll(updated)
        route = next
    }
    internal var changed: () -> Unit = {}
    internal fun encoded() = Json.encodeToString(values)
    override fun getString(key: String): String? = values[key]?.takeUnless { key == "home.trend" && it !in setOf("Day", "Week") }
    override fun getInt(key: String) = values[key]?.toIntOrNull()?.takeIf { it >= 0 }
    override fun getBoolean(key: String) = values[key]?.toBooleanStrictOrNull()
    private fun put(key: String, value: String?) {
        if (values[key] == value) return
        if (value == null) values.remove(key) else values[key] = value
        changed()
    }
    override fun putString(key: String, value: String?) = put(key, value)
    override fun putInt(key: String, value: Int?) = put(key, value?.toString())
    override fun putBoolean(key: String, value: Boolean?) = put(key, value?.toString())
}

/** History owns per-entry scroll/scalars, including across reload; no session or credentials are stored here. */
class WebNavigation(private val history: WebHistory) {
    private fun restored(): WebEntry {
        val route = parseWebRoute(history.url)
        return try {
            if (route is WebRoute.Search && history.saved.isNotEmpty()) {
                val objectState = Json.parseToJsonElement(history.saved) as? JsonObject ?: throw IllegalArgumentException("Invalid Search history")
                require(objectState.values.all { it is JsonPrimitive && it.isString })
            }
            val values = if (history.saved.isEmpty()) mutableMapOf() else Json.decodeFromString<Map<String, String>>(history.saved).toMutableMap()
            require(values.all { (key, value) -> when {
                key == "search.page" -> value.toIntOrNull()?.let { it in 1..500 } == true
                key == "search.index" || key == "search.offset" -> value.toIntOrNull()?.let { it in 0..100000 } == true
                key.endsWith(".index") || key.endsWith(".offset") -> value.toIntOrNull()?.let { it >= 0 } == true
                key == "browse.page" -> value.toIntOrNull()?.let { it in 1..500 } == true
                key == "home.trend" -> value in setOf("Day", "Week")
                key == "home.popularTv" -> value.toBooleanStrictOrNull() != null
                else -> true
            } })
            val entry = WebEntry(route, values)
            if (route is WebRoute.Browse || route == WebRoute.People) {
                val saved = BrowseSelection.restoreFrom(entry)
                require(saved == null || saved == route.browseSelection())
                require(saved != null || values.keys.none { it.startsWith("productBrowse.") })
                if (saved != null) {
                    val canonical = mutableMapOf<String, String>()
                    saved.saveTo(WebEntry(route, canonical))
                    require(values.filterKeys { it.startsWith("productBrowse.") } == canonical)
                }
            }
            if (route is WebRoute.Search) {
                require(values["productSearch.query"] == null || values["productSearch.query"] == route.query)
                require(values["productSearch.facet"] == null || values["productSearch.facet"] == route.searchFacet().name)
                require(values.keys.none { it.startsWith("productSearch.") && it !in setOf("productSearch.query", "productSearch.facet") })
                route.saveSearchInput(values)
            }
            entry
        } catch (_: IllegalArgumentException) {
            // Invalid external state is preserved in history for diagnosis, never reset or defaulted.
            WebEntry(WebRoute.NotFound, mutableMapOf())
        }
    }
    private val current = MutableStateFlow(restored())
    val entry = current.asStateFlow()
    private var closed = false
    private val unlisten: () -> Unit

    init {
        attach(current.value)
        unlisten = history.listen {
            if (!closed) { current.value.changed = {}; current.value = restored(); attach(current.value) }
        }
    }
    private fun attach(entry: WebEntry) {
        entry.changed = { if (!closed && current.value === entry) history.replace(history.url, entry.encoded()) }
        if (entry.route != WebRoute.NotFound) history.replace(entry.route.url(), entry.encoded())
    }
    /** [seed] starts the new entry's scalars (optional display data such as [webDetailSeed]); it never changes the URL. */
    fun navigate(route: WebRoute, replace: Boolean = false, seed: Map<String, String> = emptyMap()) {
        if (closed) return
        val url = route.url()
        val valid = parseWebRoute(url)
        if (valid == current.value.route) return
        if (replace && current.value.route is WebRoute.Search && valid is WebRoute.Search) {
            current.value.replaceSearch(valid) { history.replace(url, it) }
            return
        }
        val values = seed.toMutableMap()
        if (valid is WebRoute.Search) valid.saveSearchInput(values)
        val next = WebEntry(valid, values)
        if (replace) history.replace(url, next.encoded()) else history.push(url, next.encoded())
        current.value.changed = {}
        current.value = next
        attach(next)
    }
    fun close() { if (!closed) { closed = true; current.value.changed = {}; unlisten() } }
}
