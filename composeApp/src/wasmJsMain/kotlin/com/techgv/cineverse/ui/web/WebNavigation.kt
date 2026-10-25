@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui.web

import com.techgv.cineverse.presentation.StateSaver
import com.techgv.cineverse.presentation.BrowseSelection
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

@JsFun("() => !Object.prototype.hasOwnProperty.call(history.state ?? {}, 'cineverse') ? '' : typeof history.state.cineverse === 'string' ? history.state.cineverse : 'INVALID_HISTORY_STATE'")
private external fun historySaved(): String
@JsFun("(url, saved, replace) => { const state = {...history.state, cineverse:saved}; history[replace ? 'replaceState' : 'pushState'](state, '', url); }")
private external fun historyWrite(url: String, saved: String, replace: Boolean)

class WebEntry internal constructor(val route: WebRoute, private val values: MutableMap<String, String>) : StateSaver {
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
            val values = if (history.saved.isEmpty()) mutableMapOf() else Json.decodeFromString<Map<String, String>>(history.saved).toMutableMap()
            require(values.all { (key, value) -> when {
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
    fun navigate(route: WebRoute, replace: Boolean = false) {
        if (closed) return
        val url = route.url()
        val valid = parseWebRoute(url)
        if (valid == current.value.route) return
        val next = WebEntry(valid, mutableMapOf())
        if (replace) history.replace(url, next.encoded()) else history.push(url, next.encoded())
        current.value.changed = {}
        current.value = next
        attach(next)
    }
    fun close() { if (!closed) { closed = true; current.value.changed = {}; unlisten() } }
}
