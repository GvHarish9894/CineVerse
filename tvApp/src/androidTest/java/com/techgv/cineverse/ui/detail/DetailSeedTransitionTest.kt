package com.techgv.cineverse.ui.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.techgv.cineverse.data.tmdb.MovieDetailDto
import com.techgv.cineverse.data.tmdb.toDetail
import com.techgv.cineverse.domain.LibraryState
import com.techgv.cineverse.domain.MediaRef
import com.techgv.cineverse.domain.MediaType
import com.techgv.cineverse.presentation.DetailUiState
import com.techgv.cineverse.presentation.SectionState
import com.techgv.cineverse.ui.components.FocusMemory
import com.techgv.cineverse.ui.components.ProvideSharedMediaVisibility
import com.techgv.cineverse.ui.components.ProvideSharedTransition
import com.techgv.cineverse.ui.components.rememberSharedMedia
import com.techgv.cineverse.ui.components.sharedPosterKey
import com.techgv.cineverse.ui.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home card → Detail while the detail is still loading: a seeded route puts the hero poster (same shared key as the
 * card) in Detail's first frame, so the transition pairs; the loaded detail then replaces the seed.
 */
@RunWith(AndroidJUnit4::class)
class DetailSeedTransitionTest {
    @get:Rule
    val compose = createComposeRule()

    private val seeded = Route.Detail(MediaType.Movie, 550, posterPath = "/p.jpg", backdropPath = "/b.jpg", title = "Seed Title")
    private val loaded = MovieDetailDto(550, title = "Loaded Title", overview = "Overview").toDetail("IN")

    @Test
    fun seededDetail_pairsWithTheCardOnFirstFrame_thenTheLoadedDetailReplacesTheSeed() {
        val (scope, setState) = openDetail(seeded)
        assertEquals(true, scope.isTransitionActive)
        // Heading (and the poster's fallback text until its image loads).
        assertTrue(compose.onAllNodesWithText("Seed Title").fetchSemanticsNodes().isNotEmpty())

        setState(DetailUiState(SectionState.Content(loaded)))
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Loaded Title").fetchSemanticsNodes().isNotEmpty())
        compose.onAllNodesWithText("Seed Title").assertCountEquals(0)
    }

    @Test
    fun unseededDetail_hasNoTargetWhileLoading_plainFade() {
        val (scope, _) = openDetail(Route.Detail(MediaType.Movie, 550))
        assertEquals(false, scope.isTransitionActive)
    }

    /** Home card (140 dp, poster key) → Detail in [SectionState.Loading]; returns after Detail's first frames. */
    private fun openDetail(route: Route.Detail): Pair<SharedTransitionScope, (DetailUiState) -> Unit> {
        var detail by mutableStateOf(false)
        var state by mutableStateOf(DetailUiState(SectionState.Loading))
        lateinit var scope: SharedTransitionScope
        val memory = FocusMemory(SavedStateHandle())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SharedTransitionLayout {
                scope = this
                ProvideSharedTransition(this) {
                    AnimatedContent(detail, label = "screen") { showDetail ->
                        ProvideSharedMediaVisibility(this) {
                            if (showDetail) {
                                DetailContent(
                                    state, LibraryState.Unknown, memory, imageBase = "",
                                    DetailActions(onRetry = {}, library = null, onNavigate = {}), seed = route,
                                )
                            } else {
                                Box(rememberSharedMedia(sharedPosterKey(MediaRef(MediaType.Movie, 550))).size(140.dp, 210.dp))
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        detail = true
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        return scope to { state = it }
    }
}
