@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package com.techgv.cineverse.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.techgv.cineverse.di.WebSessionState
import com.techgv.cineverse.domain.Video
import com.techgv.cineverse.presentation.LibraryWrite
import com.techgv.cineverse.ui.adaptive.ShellStyle
import com.techgv.cineverse.ui.components.CvDialog
import com.techgv.cineverse.ui.components.LocalToasts
import com.techgv.cineverse.ui.components.PillButton
import com.techgv.cineverse.ui.components.ToastState
import com.techgv.cineverse.ui.theme.AppearanceSettings
import com.techgv.cineverse.ui.theme.CineVerseTheme
import kotlinx.browser.document
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val MODAL = "modal-owned-replacement"
private const val HEADER = "header-signin-tab"
private const val NARROW = "narrow-logo-entry"
private const val RATE = "rating-slider-submit"
private const val DISPOSED_RATE = "rating-disposed-replacement"
private const val TRAILER = "trailer-forward-guards"
private const val REVERSE = "header-reverse-entry"
private val CASES = setOf(MODAL, HEADER, NARROW, RATE, DISPOSED_RATE, TRAILER, REVERSE)

private class HarnessState(val case: String) {
    var mountCount = 0
    var composed = false
    var backgroundActions = 0
    var signIns = 0
    var homeNavigations = 0
    var modalGeneration by mutableStateOf(if (case == MODAL) 0 else null)
    var ratePrompt: String? = null
    var trailerOpen by mutableStateOf(false)
    val rate = WebAccessibilityRateFixture()

    fun dispatch(command: String, argument: String): String {
        when (command) {
            "set-modal-generation" -> modalGeneration = argument.toInt()
            "open-rate" -> rate.actions.openRate(
                when (argument) {
                    "movie" -> WebAccessibilityRateFixture.MOVIE
                    "other" -> WebAccessibilityRateFixture.OTHER
                    else -> error("Unknown rating fixture '$argument'")
                },
            )
            "close-rate" -> rate.actions.closeRate()
            "open-trailer" -> trailerOpen = true
            else -> error("Unknown accessibility harness command '$command'")
        }
        return snapshot()
    }

    fun snapshot(): String = buildJsonObject {
        put("case", JsonPrimitive(case))
        put("mountCount", JsonPrimitive(mountCount))
        put("composed", JsonPrimitive(composed))
        put("backgroundActions", JsonPrimitive(backgroundActions))
        put("signIns", JsonPrimitive(signIns))
        put("homeNavigations", JsonPrimitive(homeNavigations))
        put("modalGeneration", modalGeneration?.let { JsonPrimitive(it) } ?: JsonNull)
        put("ratePrompt", ratePrompt?.let { JsonPrimitive(it) } ?: JsonNull)
        put("writes", JsonArray(rate.writes.map { JsonPrimitive(it) }))
        put("outcomes", JsonArray(rate.outcomes.map { outcome ->
            JsonPrimitive(when (val write = outcome.write) {
                is LibraryWrite.Rate -> "Rate(${write.value})"
                LibraryWrite.Unrate -> "Unrate"
                LibraryWrite.ToggleFavourite -> "ToggleFavourite"
                LibraryWrite.ToggleWatchlist -> "ToggleWatchlist"
            })
        }))
        put("trailerOpen", JsonPrimitive(trailerOpen))
    }.toString()
}

fun main() {
    val selected = harnessCase()
    check(selected in CASES) { "Unknown accessibility harness case '$selected'" }
    val state = HarnessState(selected)
    installHarnessBridge(state::dispatch, state::snapshot)
    state.mountCount++
    ComposeViewport(document.getElementById("cineverse-root")!!) {
        AccessibilityHarness(state)
    }
}

@Composable
private fun AccessibilityHarness(state: HarnessState) {
    val toasts = remember { ToastState() }
    SideEffect { state.composed = true }
    DisposableEffect(state) { onDispose { state.rate.dispose() } }
    CineVerseTheme(ShellStyle.Web, AppearanceSettings(reduceMotion = true, ambientGlow = false, haptics = false)) {
        CompositionLocalProvider(LocalToasts provides toasts) {
            Box(Modifier.fillMaxSize()) {
                when (state.case) {
                    MODAL -> ModalScene(state)
                    HEADER, REVERSE -> HeaderScene(state, narrow = false)
                    NARROW -> HeaderScene(state, narrow = true)
                    RATE, DISPOSED_RATE -> RateScene(state)
                    TRAILER -> TrailerScene(state)
                }
            }
        }
    }
}

@Composable
private fun ModalScene(state: HarnessState) {
    Box(Modifier.fillMaxSize()) {
        PillButton("Background action", { state.backgroundActions++ }, Modifier.testTag("modal-background"))
        state.modalGeneration?.let { generation ->
            key(generation) {
                CvDialog({ state.modalGeneration = null }, "Modal $generation") {
                    PillButton("Real dialog action", {})
                }
            }
        }
    }
}

@Composable
private fun HeaderScene(state: HarnessState, narrow: Boolean) {
    val home = remember { FocusRequester() }
    val modalFocus = remember(home) { WebModalFocus(home) }
    CompositionLocalProvider(LocalWebModalFocus provides modalFocus) {
        Box(Modifier.width(if (narrow) 390.dp else 1100.dp)) {
            WebHeader(
                route = WebRoute.Home,
                session = WebSessionState.Ready(null),
                searchFocus = remember { FocusRequester() },
                navigate = { _, _ -> state.homeNavigations++ },
                signIn = { state.signIns++ },
                homeFocus = home,
            )
        }
    }
}

@Composable
private fun RateScene(state: HarnessState) {
    val prompt by state.rate.actions.ratePrompt.collectAsState(null)
    SideEffect { state.ratePrompt = prompt?.title?.title }
    WebRateDialog(state.rate.actions, remember { FocusRequester() })
}

private val trailer = Video("aB_09-xYz12", "Official Trailer", "Trailer", official = true)
private val teaser = Video("Zz-9_yX8wV7", "Teaser One", "Teaser", official = false)
private val blankTrailerHost = object : WebTrailerHost {
    override fun create(src: String, title: String, slot: androidx.compose.ui.geometry.Rect) =
        WebTrailerDocumentHost.create("about:blank", title, slot)
}

@Composable
private fun TrailerScene(state: HarnessState) {
    if (state.trailerOpen) {
        WebTrailerDialog(
            videos = listOf(trailer, teaser),
            initial = trailer,
            autoplay = false,
            onClose = { state.trailerOpen = false },
            restoreFocus = remember { FocusRequester() },
            host = blankTrailerHost,
        )
    }
}

@JsFun("() => new URLSearchParams(location.search).get('case') || ''")
private external fun harnessCase(): String

@JsFun("""(dispatch,snapshot) => {
    globalThis.__cvAccessibilityHarness = {
        dispatch(command, argument) { return dispatch(String(command), argument == null ? '' : String(argument)); },
        snapshot() { return snapshot(); }
    };
}""")
private external fun installHarnessBridge(dispatch: (String, String) -> String, snapshot: () -> String)
