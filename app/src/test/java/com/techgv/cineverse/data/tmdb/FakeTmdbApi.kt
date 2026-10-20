package com.techgv.cineverse.data.tmdb

import java.lang.reflect.Proxy

/** Delegate for a test fake: any TmdbApi method the fake does not override fails loudly. */
val unusedTmdbApi: TmdbApi = Proxy.newProxyInstance(TmdbApi::class.java.classLoader, arrayOf(TmdbApi::class.java)) { _, method, _ ->
    throw UnsupportedOperationException("TmdbApi.${method.name} not faked")
} as TmdbApi
