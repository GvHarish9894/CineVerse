package com.techgv.cineverse.data.tmdb

import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

private const val TAG = "TmdbHttp"

/** Body preview size when opted in: logging whole bodies (~880 KB TV details) stalled loads on device. */
const val BODY_PREVIEW_BYTES = 2048L

/**
 * Debug only (§4.3): request / response headers, Authorization and session_id redacted. Response bodies are
 * opt-in (`adb shell setprop log.tag.TmdbHttp VERBOSE`, then restart the app) and cut to [BODY_PREVIEW_BYTES];
 * auth endpoint bodies are never logged.
 */
fun OkHttpClient.Builder.addDebugHttpLogging(): OkHttpClient.Builder {
    val preview = if (Log.isLoggable(TAG, Log.VERBOSE)) BODY_PREVIEW_BYTES else 0L
    return addInterceptor(tmdbLoggingInterceptor(bodyPreviewBytes = preview) { Log.d(TAG, it) })
}

/** [bodyPreviewBytes] > 0 also logs the first that many bytes of each non-auth response body. */
fun tmdbLoggingInterceptor(bodyPreviewBytes: Long = 0, logger: HttpLoggingInterceptor.Logger): Interceptor {
    val headers = HttpLoggingInterceptor(logger).apply {
        level = HttpLoggingInterceptor.Level.HEADERS
        redactHeader("Authorization")
        redactQueryParams("session_id")
    }
    return Interceptor { chain ->
        val response = headers.intercept(chain)
        if (bodyPreviewBytes > 0 && !isAuthEndpoint(chain.request().url)) {
            logger.log("<-- body (first $bodyPreviewBytes bytes): " + response.peekBody(bodyPreviewBytes).string())
        }
        response
    }
}
