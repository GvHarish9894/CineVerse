package com.techgv.cineverse.data.tmdb

import com.techgv.cineverse.domain.MediaType
import io.ktor.client.request.get
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** §3.1 token kind per call, bearer only for api.themoviedb.org, §3.2 per-endpoint parameters. */
class TmdbClientTest {
    // Satisfies every DTO the calls below decode (unknown keys are ignored, required ids present).
    private val tmdb = MockTmdb { ok("""{"id":1,"request_token":"rt","access_token":"a","account_id":"b","session_id":"s"}""") }
    private val api = tmdb.api
    private val last get() = tmdb.last
    private val user = UserBearer(UserAccessToken("user-token"))

    @Test
    fun catalogAuthAndV3SessionCallsUseTheReadToken() = runTest {
        api.configuration()
        assertEquals("Bearer read-token", last.authorization)
        api.createRequestToken(RequestTokenBody("https://www.themoviedb.org/"))
        assertEquals("Bearer read-token", last.authorization)
        assertEquals("""{"redirect_to":"https://www.themoviedb.org/"}""", last.bodyText)
        api.createAccessToken(AccessTokenBody("rt"))
        assertEquals("Bearer read-token", last.authorization)
        api.convertToSession(ConvertSessionBody("uat"))
        assertEquals("Bearer read-token", last.authorization)
        assertEquals("""{"access_token":"uat"}""", last.bodyText)
        api.deleteSession(DeleteSessionBody("sid"))
        assertEquals("Bearer read-token", last.authorization)
        assertEquals(HttpMethod.Delete, last.method)
        assertEquals("""{"session_id":"sid"}""", last.bodyText)
        api.account("sid")
        assertEquals("Bearer read-token", last.authorization)
        assertEquals("sid", last.url.query("session_id"))
        api.setWatchlist(42, "sid", WatchlistBody("movie", 550, true))
        assertEquals("Bearer read-token", last.authorization)
        assertEquals("/3/account/42/watchlist?session_id=sid", last.pathAndQuery)
        assertEquals("""{"media_type":"movie","media_id":550,"watchlist":true}""", last.bodyText)
    }

    @Test
    fun v4AccountListsAndAccessTokenRevocationUseTheUserToken() = runTest {
        api.accountList(user, "aoid", MediaType.Movie, AccountList.Favorites, "en-US", 2)
        assertEquals("Bearer user-token", last.authorization)
        assertEquals("/4/account/aoid/movie/favorites", last.url.encodedPath)
        assertEquals("created_at.desc", last.url.query("sort_by"))
        assertNull(last.url.query("session_id"))

        api.deleteAccessToken(user, DeleteAccessTokenBody("user-token"))
        assertEquals("Bearer user-token", last.authorization)
        assertEquals(HttpMethod.Delete, last.method)
        assertEquals("/4/auth/access_token", last.url.encodedPath)
    }

    @Test
    fun noBearerForOtherHosts() = runTest {
        tmdb.client.get("https://image.tmdb.org/t/p/w342/x.jpg")
        assertNull(last.authorization)
    }

    @Test
    fun perEndpointParameters() = runTest {
        api.tvList(TvList.Popular, "en-US", null, 1)
        assertNull(last.url.query("timezone"))
        api.tvList(TvList.AiringToday, "en-US", "Asia/Kolkata", 1)
        assertEquals("Asia/Kolkata", last.url.query("timezone"))
        api.movieList(MovieList.NowPlaying, "hi-IN", "IN", 3)
        assertEquals("/3/movie/now_playing", last.url.encodedPath)
        assertEquals("IN", last.url.query("region"))
        assertEquals("3", last.url.query("page"))
        api.searchMulti("dune part two", "en-US", false, 1)
        assertEquals("false", last.url.query("include_adult"))
        assertEquals("dune part two", last.url.query("query"))
        api.configuration()
        assertNull(last.url.query("language"))
        api.movieDetail(550, "en-US", "credits,videos", null)
        assertEquals("credits,videos", last.url.query("append_to_response"))
        assertNull(last.url.query("session_id"))
        assertFalse(last.url.parameters.contains("region"))
        api.person(31, "en-US")
        assertEquals("/3/person/31?language=en-US&append_to_response=combined_credits", last.pathAndQuery)
    }
}
