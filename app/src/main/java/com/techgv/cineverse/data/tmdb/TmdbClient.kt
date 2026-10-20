package com.techgv.cineverse.data.tmdb

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Retrofit `@Tag` marking a call that must use the user access token instead of the read token (§3.1). */
class UserBearer(val token: UserAccessToken)

@OptIn(ExperimentalSerializationApi::class)
val TmdbJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

const val TMDB_HOST = "api.themoviedb.org"

/** Attaches a bearer only for [TMDB_HOST]: the call's [UserBearer] tag if present, else the read token. */
class TmdbBearerInterceptor(private val readToken: ReadToken) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != TMDB_HOST) return chain.proceed(request)
        val bearer = request.tag(UserBearer::class.java)?.token?.value ?: readToken.value
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $bearer").build())
    }
}

/** Auth endpoints carry tokens / session ids in their bodies; debug logging never prints those bodies. */
fun isAuthEndpoint(url: HttpUrl): Boolean =
    url.encodedPath.startsWith("/4/auth/") || url.encodedPath.startsWith("/3/authentication/")

object TmdbClient {
    fun create(readToken: ReadToken): TmdbApi = api(
        OkHttpClient.Builder()
            .addInterceptor(TmdbBearerInterceptor(readToken))
            .addDebugHttpLogging()
            .build(),
    )

    fun api(http: OkHttpClient): TmdbApi =
        Retrofit.Builder()
            .baseUrl("https://$TMDB_HOST/")
            .client(http)
            .addConverterFactory(TmdbJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmdbApi::class.java)
}
