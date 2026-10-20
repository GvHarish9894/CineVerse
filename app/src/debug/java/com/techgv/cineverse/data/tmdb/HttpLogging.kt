package com.techgv.cineverse.data.tmdb

import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

/** Debug only (§4.3): Authorization and session_id redacted; auth endpoint bodies never logged. */
fun OkHttpClient.Builder.addDebugHttpLogging(): OkHttpClient.Builder =
    addInterceptor(tmdbLoggingInterceptor { Log.d("TmdbHttp", it) })

fun tmdbLoggingInterceptor(logger: HttpLoggingInterceptor.Logger): Interceptor {
    fun logging(level: HttpLoggingInterceptor.Level) = HttpLoggingInterceptor(logger).apply {
        this.level = level
        redactHeader("Authorization")
        redactQueryParams("session_id")
    }
    val withBodies = logging(HttpLoggingInterceptor.Level.BODY)
    val headersOnly = logging(HttpLoggingInterceptor.Level.HEADERS)
    return Interceptor { chain ->
        (if (isAuthEndpoint(chain.request().url)) headersOnly else withBodies).intercept(chain)
    }
}
