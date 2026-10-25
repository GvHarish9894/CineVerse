@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui.web

import kotlin.js.JsAny
import kotlin.test.*

/** Only Karma's current window is changed; restore its original structured state and URL even on failure. */
class WebBrowserHistoryTest {
    @Test fun absentNamespaceAndValidStringRestoreNormally() {
        val snapshot = captureHistory()
        try {
            installHistoryFixture("absent")
            assertEquals("", BrowserHistory().saved)
            val absent = WebNavigation(BrowserHistory())
            try { assertEquals(WebRoute.Browse("movie", "upcoming"), absent.entry.value.route) }
            finally { absent.close() }
            installHistoryFixture("valid")
            val valid = WebNavigation(BrowserHistory())
            try {
                assertEquals(WebRoute.Browse("movie", "upcoming"), valid.entry.value.route)
                assertEquals(4, valid.entry.value.getInt("browse.index"))
            } finally { valid.close() }
        } finally { restoreHistory(snapshot) }
    }

    @Test fun presentNonStringNamespaceIsInvalidAndNeverReset() {
        val snapshot = captureHistory()
        try {
            listOf("null", "undefined", "object", "array", "number", "boolean").forEach { kind ->
                installHistoryFixture(kind)
                assertEquals("INVALID_HISTORY_STATE", BrowserHistory().saved)
                val nav = WebNavigation(BrowserHistory())
                try {
                    assertEquals(WebRoute.NotFound, nav.entry.value.route, kind)
                    assertTrue(historyFixturePreserved(kind), kind)
                    assertEquals("/movies?list=upcoming", BrowserHistory().url)
                } finally { nav.close() }
            }
        } finally { restoreHistory(snapshot) }
    }
}

@JsFun("() => ({state: history.state, url: location.href})")
private external fun captureHistory(): JsAny
@JsFun("snapshot => history.replaceState(snapshot.state, '', snapshot.url)")
private external fun restoreHistory(snapshot: JsAny)
@JsFun("kind => { const values = {valid:'{\"browse.index\":\"4\"}', null:null, undefined:undefined, object:{bad:true}, array:[], number:7, boolean:false}; const state = {fixture:'E3'}; if (kind !== 'absent') state.cineverse = values[kind]; history.replaceState(state, '', '/movies?list=upcoming'); }")
private external fun installHistoryFixture(kind: String)
@JsFun("kind => { const s = history.state; if (s.fixture !== 'E3' || !Object.prototype.hasOwnProperty.call(s, 'cineverse')) return false; const v = s.cineverse; return kind === 'null' ? v === null : kind === 'undefined' ? v === undefined : kind === 'object' ? v?.bad === true : kind === 'array' ? Array.isArray(v) : kind === 'number' ? v === 7 : v === false; }")
private external fun historyFixturePreserved(kind: String): Boolean
