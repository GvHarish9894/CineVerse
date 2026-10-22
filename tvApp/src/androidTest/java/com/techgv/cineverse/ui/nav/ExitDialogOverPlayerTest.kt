package com.techgv.cineverse.ui.nav

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.techgv.cineverse.AppContainer
import com.techgv.cineverse.data.tmdb.GenreDto
import com.techgv.cineverse.data.tmdb.GenreListDto
import com.techgv.cineverse.data.tmdb.MediaItemDto
import com.techgv.cineverse.data.tmdb.MovieList
import com.techgv.cineverse.data.tmdb.PageDto
import com.techgv.cineverse.data.tmdb.TmdbApi
import com.techgv.cineverse.data.tmdb.TrendingWindow
import com.techgv.cineverse.data.tmdb.TvList
import com.techgv.cineverse.data.tmdb.VideoDto
import com.techgv.cineverse.data.tmdb.VideosDto
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.ui.components.DesignCanvas
import com.techgv.cineverse.ui.theme.CineVerseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/** Unfaked calls fail as TmdbError.Malformed (an unchecked exception, so the Proxy does not wrap it). */
private val notFaked = Proxy.newProxyInstance(TmdbApi::class.java.classLoader, arrayOf(TmdbApi::class.java)) { _, method, _ ->
    throw SerializationException("TmdbApi.${method.name} not faked")
} as TmdbApi

/** Catalog lists answer at once; `/videos` waits for [videosGate] (a slow network). */
private class SlowVideosApi : TmdbApi by notFaked {
    val videosGate = CompletableDeferred<Unit>()

    private fun page(label: String) = PageDto(results = (0 until 3).map { MediaItemDto(id = it + 1, mediaType = "movie", title = "$label $it") }, totalPages = 1)

    override suspend fun genres(media: MediaType, language: String) = GenreListDto(listOf(GenreDto(28, "Action")))
    override suspend fun trending(window: TrendingWindow, language: String, page: Int) = page("Trending")
    override suspend fun movieList(list: MovieList, language: String, region: String, page: Int) = page("$list")
    override suspend fun tvList(list: TvList, language: String, timezone: String?, page: Int) = page("$list")
    override suspend fun popularPeople(language: String, page: Int) = page("Person")
    override suspend fun videos(media: MediaType, id: Int, language: String): VideosDto {
        videosGate.await()
        return VideosDto(listOf(VideoDto(key = "dQw4w9WgXcQ", name = PLAYER_KICKER, site = "YouTube", type = "Trailer")))
    }
}

private const val PLAYER_KICKER = "Slow Official Trailer"

/**
 * Final review #1: guest Home → OK on "Play trailer" → BACK before `/videos` answers. The Exit dialog must never be
 * shown while the Player route is visible (it used to open over the playing video).
 */
@RunWith(AndroidJUnit4::class)
class ExitDialogOverPlayerTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = SlowVideosApi()

    @Test
    fun trailerArrivingAfterTheExitDialogOpenedDoesNotOpenThePlayer() {
        openHomeOnPlayTrailer()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(TIMEOUT) { exists("Exit CineVerse?") }
        api.videosGate.complete(Unit)
        assertNeverExitOverPlayer()
        compose.onNodeWithText("Exit CineVerse?").assertExists()
        assertFalse(exists(PLAYER_KICKER))
    }

    @Test
    fun trailerArrivingWithTheBackPressNeverShowsExitOverThePlayer() {
        openHomeOnPlayTrailer()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // BACK and the response in one main-thread turn: the navigation can win the race with the dialog's window focus.
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            api.videosGate.complete(Unit)
        }
        assertNeverExitOverPlayer()
    }

    private fun openHomeOnPlayTrailer() {
        val container = AppContainer(compose.activity.applicationContext, api)
        assertNull("needs a guest (signed-out) install", runBlocking { container.authRepository.session.first() })
        compose.setContent { DesignCanvas { CineVerseTheme { AppNav(container) } } }
        compose.waitUntil(TIMEOUT) { exists("Continue as guest") }
        compose.onNodeWithText("Continue as guest").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(TIMEOUT) { exists("Trending this week") }
        // BACK on Home away from the origin focuses "Play trailer" (§1.2 step 6).
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(TIMEOUT) { exists("Play trailer") }
        compose.onNodeWithText("Play trailer").assertIsFocused()
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    private fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun assertNeverExitOverPlayer() {
        val end = System.currentTimeMillis() + OBSERVE_MILLIS
        while (System.currentTimeMillis() < end) {
            compose.waitForIdle()
            assertFalse("Exit dialog shown over the Player", exists("Exit CineVerse?") && exists(PLAYER_KICKER))
            Thread.sleep(50)
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val OBSERVE_MILLIS = 2_000L
    }
}
