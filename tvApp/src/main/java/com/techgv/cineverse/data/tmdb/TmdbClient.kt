package com.techgv.cineverse.data.tmdb

import io.ktor.client.engine.okhttp.OkHttp

object TmdbClient {
    /** The authenticated TMDB API on the OkHttp engine; debug builds add the redacted HTTP log (§4.3). */
    fun create(readToken: ReadToken): TmdbApi =
        KtorTmdbApi(tmdbHttpClient(OkHttp.create(), Transport.Direct(readToken)) { installDebugHttpLogging() })
}
