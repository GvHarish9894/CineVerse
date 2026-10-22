package com.techgv.cineverse.data.tmdb

import io.ktor.client.HttpClientConfig

/** Release builds never log HTTP (§4.3). */
@Suppress("UnusedReceiverParameter")
fun HttpClientConfig<*>.installDebugHttpLogging() = Unit
