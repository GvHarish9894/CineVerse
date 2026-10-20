package com.techgv.cineverse.data.tmdb

import okhttp3.OkHttpClient

/** Release builds never log HTTP (§4.3). */
fun OkHttpClient.Builder.addDebugHttpLogging(): OkHttpClient.Builder = this
