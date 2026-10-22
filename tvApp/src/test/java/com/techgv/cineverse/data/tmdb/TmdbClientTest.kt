package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.domain.MediaType
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbClientTest {
    private val requests = mutableListOf<Request>()

    /** Real Retrofit + bearer interceptor; a terminal interceptor records the request instead of the network. */
    private val api = TmdbClient.api(
        OkHttpClient.Builder()
            .addInterceptor(TmdbBearerInterceptor(ReadToken("read-token")))
            .addInterceptor(Interceptor { chain ->
                requests += chain.request()
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(OK_BODY.toResponseBody())
                    .build()
            })
            .build(),
    )

    private val last get() = requests.last()
    private val user = UserBearer(UserAccessToken("user-token"))

    @Test
    fun `catalog, auth and v3 session calls use the read token`() = runTest {
        api.configuration()
        assertEquals("Bearer read-token", last.header("Authorization"))
        api.createRequestToken(RequestTokenBody("https://www.themoviedb.org/"))
        assertEquals("Bearer read-token", last.header("Authorization"))
        api.createAccessToken(AccessTokenBody("rt"))
        assertEquals("Bearer read-token", last.header("Authorization"))
        api.convertToSession(ConvertSessionBody("uat"))
        assertEquals("Bearer read-token", last.header("Authorization"))
        api.deleteSession(DeleteSessionBody("sid"))
        assertEquals("Bearer read-token", last.header("Authorization"))
        api.account("sid")
        assertEquals("Bearer read-token", last.header("Authorization"))
        assertEquals("sid", last.url.queryParameter("session_id"))
        api.setWatchlist(42, "sid", WatchlistBody("movie", 550, true))
        assertEquals("Bearer read-token", last.header("Authorization"))
    }

    @Test
    fun `v4 account lists and access-token revocation use the user token`() = runTest {
        api.accountList(user, "aoid", MediaType.Movie, AccountList.Favorites, "en-US", 2)
        assertEquals("Bearer user-token", last.header("Authorization"))
        assertEquals("/4/account/aoid/movie/favorites", last.url.encodedPath)
        assertEquals("created_at.desc", last.url.queryParameter("sort_by"))
        assertNull(last.url.queryParameter("session_id"))

        api.deleteAccessToken(user, DeleteAccessTokenBody("user-token"))
        assertEquals("Bearer user-token", last.header("Authorization"))
        assertEquals("DELETE", last.method)
    }

    @Test
    fun `only the user-token endpoints take a UserBearer tag`() {
        val tagged = TmdbApi::class.java.methods
            .filter { m -> !java.lang.reflect.Modifier.isStatic(m.modifiers) } // skip Kotlin `$default` bridges
            .filter { m -> m.parameterTypes.any { it == UserBearer::class.java } }
            .map { it.name }
            .toSet()
        assertEquals(setOf("accountList", "deleteAccessToken"), tagged)
    }

    @Test
    fun `no bearer for other hosts`() {
        var seen: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(TmdbBearerInterceptor(ReadToken("read-token")))
            .addInterceptor(Interceptor { chain ->
                seen = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("".toResponseBody()).build()
            })
            .build()
        client.newCall(Request.Builder().url("https://image.tmdb.org/t/p/w342/x.jpg").build()).execute().close()
        assertNull(seen!!.header("Authorization"))
    }

    @Test
    fun `per-endpoint parameters`() = runTest {
        api.tvList(TvList.Popular, "en-US", null, 1)
        assertNull(last.url.queryParameter("timezone"))
        api.tvList(TvList.AiringToday, "en-US", "Asia/Kolkata", 1)
        assertEquals("Asia/Kolkata", last.url.queryParameter("timezone"))
        api.movieList(MovieList.NowPlaying, "hi-IN", "IN", 3)
        assertEquals("/3/movie/now_playing", last.url.encodedPath)
        assertEquals("IN", last.url.queryParameter("region"))
        assertEquals("3", last.url.queryParameter("page"))
        api.searchMulti("dune", "en-US", false, 1)
        assertEquals("false", last.url.queryParameter("include_adult"))
        api.configuration()
        assertNull(last.url.queryParameter("language"))
        api.movieDetail(550, "en-US", "credits,videos", null)
        assertNull(last.url.queryParameter("session_id"))
        assertFalse(last.url.queryParameterNames.contains("region"))
    }

    private companion object {
        // Satisfies every DTO the calls above decode (unknown keys are ignored, required ids present).
        const val OK_BODY = """{"id":1,"request_token":"rt","access_token":"a","account_id":"b","session_id":"s"}"""
    }
}
