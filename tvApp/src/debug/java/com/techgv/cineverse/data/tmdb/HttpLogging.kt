package com.techgv.cineverse.data.tmdb

import android.util.Log
import io.ktor.client.HttpClientConfig

private const val TAG = "TmdbHttp"

/**
 * Debug only (§4.3): [TmdbHttpLogging] to logcat. Response bodies are opt-in (`adb shell setprop log.tag.TmdbHttp
 * VERBOSE`, then restart the app) and cut to [BODY_PREVIEW_BYTES]; auth endpoint bodies are never logged.
 */
fun HttpClientConfig<*>.installDebugHttpLogging() {
    install(TmdbHttpLogging) {
        log = { Log.d(TAG, it) }
        bodyPreviewBytes = if (Log.isLoggable(TAG, Log.VERBOSE)) BODY_PREVIEW_BYTES else 0L
    }
}
