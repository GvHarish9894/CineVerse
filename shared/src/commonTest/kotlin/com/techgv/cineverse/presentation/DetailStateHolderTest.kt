package com.techgv.cineverse.presentation

import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.MovieDetailDto
import com.techgv.cineverse.data.tmdb.SeasonSummaryDto
import com.techgv.cineverse.data.tmdb.TvDetailDto
import com.techgv.cineverse.data.tmdb.UnusedTmdbApi
import com.techgv.cineverse.data.tmdb.VideoDto
import com.techgv.cineverse.data.tmdb.VideosDto
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.domain.LibraryActions
import com.techgv.cineverse.domain.LibraryKind
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.domain.Title
import com.techgv.cineverse.util.GenerationCounter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §7.4 / D7 (moved from tvApp DetailViewModelTest) plus the account-state and button rules. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailStateHolderTest {
    private val dispatcher = StandardTestDispatcher()
    private val calls = mutableListOf<String>()
    private val settings = MutableStateFlow(Settings())
    private val generation = GenerationCounter()
    private var movieGate: CompletableDeferred<Unit>? = null

    private val api = object : UnusedTmdbApi() {
        override suspend fun movieDetail(id: Int, language: String, append: String, sessionId: String?): MovieDetailDto {
            calls += "movie/$language"
            movieGate?.await()
            return if (language == ENGLISH) {
                MovieDetailDto(id, title = "Title", tagline = "English tagline", overview = "English overview")
            } else {
                MovieDetailDto(id, title = "Title", tagline = "", overview = "स्थानीय")
            }
        }

        override suspend fun videos(media: MediaType, id: Int, language: String): VideosDto {
            calls += "videos/$language"
            return VideosDto(listOf(VideoDto(key = "yt1", site = "YouTube", type = "Trailer", official = true)))
        }
    }
    private val repo = catalog(api, generation, settings)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.holder(library: LibraryActions? = null): DetailStateHolder =
        DetailStateHolder(MediaRef(MediaType.Movie, 550), repo, settings, generation, library).also { advanceUntilIdle() }

    private fun DetailStateHolder.detail() = (state.value.detail as SectionState.Content).value

    @Test fun enUsMakesOneCatalogRequestAndNoFallbacks() = runTest(dispatcher) {
        val holder = holder()
        assertEquals(listOf("movie/en-US"), calls)
        assertEquals("English tagline", holder.detail().tagline)
        assertFalse(holder.detail().taglineInEnglish)
        assertTrue(holder.detail().videos.isEmpty()) // no en-US refetch when the language already is en-US
    }

    @Test fun nonEnUsFillsBlankTextFromEnglishLabelledAndRefetchesEmptyVideosInEnglish() = runTest(dispatcher) {
        settings.value = Settings(language = "hi-IN")
        val detail = holder().detail()
        assertEquals("English tagline", detail.tagline)
        assertTrue(detail.taglineInEnglish)
        assertEquals("स्थानीय", detail.overview)
        assertEquals(listOf("yt1"), detail.videos.map { it.key })
        assertEquals(listOf("movie/hi-IN", "movie/en-US", "videos/en-US"), calls)
    }

    @Test fun appGenerationBumpWithoutASettingsEmissionReloadsTheDroppedResult() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>().also { movieGate = it }
        val holder = holder()
        assertEquals(SectionState.Loading, holder.state.value.detail)
        generation.bump() // sign-in / sign-out: no new settings value reaches the screen
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(holder.state.value.detail is SectionState.Content)
        assertEquals(listOf("movie/en-US", "movie/en-US"), calls)
    }

    @Test fun languageChangeReloadsTheVisibleDetail() = runTest(dispatcher) {
        holder()
        settings.value = Settings(language = "es-ES")
        advanceUntilIdle()
        assertTrue("movie/es-ES" in calls)
    }

    @Test fun accountStateIsReadSeparatelyFromTheLibrary() = runTest(dispatcher) {
        val known = LibraryState(known = true, watchlist = true, favourite = false, rating = 8.0, pending = emptySet())
        val library = object : LibraryActions {
            override fun state(ref: MediaRef): Flow<LibraryState> = MutableStateFlow(if (ref.id == 550) known else LibraryState.Unknown)
            override fun toggleWatchlist(title: Title) = Unit
            override fun toggleFavourite(title: Title) = Unit
            override fun openRate(title: Title) = Unit
        }
        val holder = holder(library)
        assertEquals(LibraryState.Unknown, holder.accountState.value) // not collected yet
        backgroundScope.launch { holder.accountState.collect {} }
        runCurrent()
        assertEquals(known, holder.accountState.value)
        assertEquals(LibraryState.Unknown, holder().accountState.value) // no library: stays Unknown
    }

    @Test fun libraryButtonsNeedALibraryAKnownStateAndNoPendingWriteOfTheirKind() {
        val known = LibraryState(known = true, watchlist = false, favourite = false, rating = null, pending = setOf(LibraryKind.FAVOURITE))
        assertFalse(LibraryState.Unknown.canToggle(LibraryKind.WATCHLIST))
        assertTrue(known.canToggle(LibraryKind.WATCHLIST))
        assertFalse(known.canToggle(LibraryKind.FAVOURITE))
        assertFalse(known.canToggle(LibraryKind.RATING, hasLibrary = false))
    }

    @Test fun trailerIsTheFirstYouTubeVideoAndEpisodesTheFirstListedSeason() {
        val trailer = VideoDto(key = "yt1", name = "", site = "YouTube", type = "Trailer", official = true)
        val movie = MovieDetailDto(550, title = "Fight Club", videos = VideosDto(listOf(trailer))).toDetail("IN")
        assertEquals(TrailerEvent.Play("yt1", "Fight Club", "Trailer"), movie.trailer())
        assertNull(MovieDetailDto(1, videos = VideosDto(listOf(trailer.copy(site = "Vimeo")))).toDetail("IN").trailer())
        assertEquals(1, TvDetailDto(7, seasons = listOf(SeasonSummaryDto(10, 0), SeasonSummaryDto(11, 1))).toDetail("IN").episodesSeason())
        assertNull(TvDetailDto(8).toDetail("IN").episodesSeason())
    }
}
